package org.safcephfs;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.AutoCloseInputStream;
import android.os.Process;
import android.os.storage.StorageManager;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;
import android.widget.Toast;
import android.util.Log;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;

import com.ceph.fs.CephMount;
import com.ceph.fs.CephStat;
import com.ceph.fs.CephStatVFS;
import com.ceph.fs.CephNotDirectoryException;

public class CephFSDocumentsProvider extends DocumentsProvider {
	private StorageManager sm;
	private Handler ioHandler;
	private CephFSExecutor executor;
	private int uid;
	private ToastThread lthread;

	private boolean checkPermissions = true;

	private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
		Root.COLUMN_ROOT_ID,
		Root.COLUMN_FLAGS,
		Root.COLUMN_ICON,
		Root.COLUMN_TITLE,
		Root.COLUMN_DOCUMENT_ID,
		Root.COLUMN_CAPACITY_BYTES,
		Root.COLUMN_AVAILABLE_BYTES
	};

	private static final String[] DEFAULT_DOC_PROJECTION = new String[]{
		Document.COLUMN_DOCUMENT_ID,
		Document.COLUMN_DISPLAY_NAME,
		Document.COLUMN_MIME_TYPE,
		Document.COLUMN_LAST_MODIFIED,
		Document.COLUMN_SIZE,
		Document.COLUMN_FLAGS,
		Document.COLUMN_SUMMARY,
		Document.COLUMN_ICON,
	};

	private static String APP_NAME;

	private static String getMimeFromName(String filename) {
		int idx = filename.lastIndexOf(".");
		if (idx > 0) {
			String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
				filename.substring(idx + 1));
			if (mime != null) {
				return mime;
			}
		}
		return "application/octet-stream";
	}

	private static final int S_IFMT = 0170000, S_IFSOCK = 0140000,
		S_IFLNK = 0120000, S_IFREG = 0100000, S_IFBLK = 0060000,
		S_IFDIR = 0040000, S_IFCHR = 0020000, S_IFIFO = 0010000;

	private static String getMime(int mode, String name) {
		return switch (mode & S_IFMT) {
		case S_IFSOCK -> "inode/socket";
		case S_IFLNK -> "inode/symlink";
		case S_IFREG -> getMimeFromName(name);
		case S_IFBLK -> "inode/blockdevice";
		case S_IFDIR -> Document.MIME_TYPE_DIR;
		case S_IFCHR -> "inode/chardevice";
		case S_IFIFO -> "inode/fifo";
		default -> "application/octet-stream";
		};
	}

	private static int S_IR = 4, S_IW = 2, S_IX = 1;

	private int getPermissions(CephStat cs) {
		return (!checkPermissions ? 7 :
				cs.uid == uid ? cs.mode >> 6 :
				cs.gid == uid ? cs.mode >> 3 : cs.mode) & 7;
	}

	private boolean mayRead(CephStat cs) {
		return (getPermissions(cs) & S_IR) == S_IR;
	}

	private boolean mayWrite(CephStat cs) {
		return (getPermissions(cs) & S_IW) == S_IW;
	}

	private void toast(String message) {
		Message msg = lthread.handler.obtainMessage();
		msg.obj = APP_NAME + ": " + message;
		lthread.handler.sendMessage(msg);
	}

	private String pathFromDocumentId(String documentId) {
		return Uri.parse(documentId).getPath();
	}

	private String documentIdFromPath(String path) {
		return executor.config.getRootUri().buildUpon().path(path).build().toString();
	}

	@Override
	public boolean onCreate() {
		APP_NAME = getContext().getString(R.string.app_name);
		sm = (StorageManager) getContext()
			.getSystemService(Context.STORAGE_SERVICE);
		lthread = new ToastThread(getContext());
		lthread.start();
		HandlerThread ioThread = new HandlerThread("IO thread");
		ioThread.start();
		ioHandler = new Handler(ioThread.getLooper());
		uid = Process.myUid();

		SharedPreferences settings = PreferenceManager
			.getDefaultSharedPreferences(getContext());
		SharedPreferences.OnSharedPreferenceChangeListener l = (sp, key) -> {
			var id = sp.getString("id", "");
			var path = sp.getString("path", "");
			var timeout = sp.getString("timeout", "");
			timeout = timeout.matches("\\d+") ? timeout : "20";
			checkPermissions = sp.getBoolean("permissions", true);
			var config = new HashMap<String, String>();

			config.put("mon_host", sp.getString("mon", ""));
			config.put("key", sp.getString("key", ""));
			config.put("client_mount_timeout", timeout);
			config.put("client_dirsize_rbytes", "false");
			if (!checkPermissions) {
				config.put("client_permissions", "false");
			}

			config.put("debug_javaclient", "20");
			config.put("debug_ms", "1");
			config.put("debug_client", "10");
			config.put("ms_connection_ready_timeout", "3");

			var c = new CephFSExecutor.CephMountConfig(id, path, config);
			executor = new CephFSExecutor(c);
		};
		settings.registerOnSharedPreferenceChangeListener(l);
		l.onSharedPreferenceChanged(settings, "");
		return true;
	}

	public String createDocument(String parentDocumentId, String mimeType,
			String displayName) throws FileNotFoundException {
		Log.v(APP_NAME, "createDocument " + parentDocumentId + " " + mimeType + " " + displayName);
		var path = pathFromDocumentId(parentDocumentId) + "/" + displayName;
		if (mimeType.equals(Document.MIME_TYPE_DIR)) {
			executor.executeWithUnchecked(cm -> {
				cm.mkdir(path, 0700);
				return null;
			});
		} else {
			executor.executeWithUnchecked(cm -> {
				int fd = cm.open(path, CephMount.O_WRONLY | CephMount.O_CREAT | CephMount.O_EXCL, 0700);
				cm.close(fd);
				return null;
			});
		}
		return documentIdFromPath(path);
	}

	public void deleteDocument(String documentId) throws FileNotFoundException {
		Log.v(APP_NAME, "deleteDocument " + documentId);
		var path = pathFromDocumentId(documentId);
		executor.executeWithUnchecked(cm -> {
			cm.unlink(path);
			return null;
		});
	}

	public boolean isChildDocument(String parentDocumentId, String documentId) {
		return documentId.startsWith(parentDocumentId) &&
			documentId.charAt(parentDocumentId.length()) == '/';
	}

	public ParcelFileDescriptor openDocument(String documentId,
			String mode, CancellationSignal cancellationSignal)
			throws UnsupportedOperationException,
			FileNotFoundException {
		Log.v(APP_NAME, "openDocument " + mode + " " + documentId);
		var path = pathFromDocumentId(documentId);
		int flag, fdmode;
		switch (mode) {
		case "r":
			flag = CephMount.O_RDONLY;
			fdmode = ParcelFileDescriptor.MODE_READ_ONLY;
			break;
		case "rw":
			flag = CephMount.O_RDWR;
			fdmode = ParcelFileDescriptor.MODE_READ_WRITE;
			break;
		case "w":
			flag = CephMount.O_WRONLY;
			fdmode = ParcelFileDescriptor.MODE_WRITE_ONLY;
			break;
		default:
			throw new UnsupportedOperationException("Mode " + mode + " not implemented");
		}

		return executor.executeWithUnchecked(cm -> {
			int fd = cm.open(path, flag, 0);
			return sm.openProxyFileDescriptor(fdmode,
				new CephFSProxyFileDescriptorCallback(executor, cm, fd, path, flag),
				ioHandler);
		});
	}

	private String getXDGThumbnailFile(String name) {
		MessageDigest md5;
		try {
			md5 = MessageDigest.getInstance("MD5");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
		md5.update(("./" + name).getBytes());
		byte[] digest = md5.digest();
		String hex = String.format("%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x", digest[0], digest[1], digest[2], digest[3], digest[4], digest[5], digest[6], digest[7], digest[8], digest[9], digest[10], digest[11], digest[12], digest[13], digest[14], digest[15]);

		return hex + ".png";
	}

	@Override
	public AssetFileDescriptor openDocumentThumbnail(String documentId,
			Point sizeHint, CancellationSignal signal)
			throws FileNotFoundException {
		var path = pathFromDocumentId(documentId);
		int dirIndex = path.lastIndexOf("/");
		String filename = path.substring(dirIndex + 1);
		String dir = path.substring(0, dirIndex + 1);
		String thumbnailPath = dir + ".sh_thumbnails/normal/" + getXDGThumbnailFile(filename);
		try {
			ParcelFileDescriptor fd = openDocument(documentIdFromPath(thumbnailPath), "r", signal);
			return new AssetFileDescriptor(fd, 0, fd.getStatSize());
		} catch (FileNotFoundException e) {
		}

		if (ExifInterface.isSupportedMimeType(getDocumentType(documentId))) {
			ParcelFileDescriptor fd = openDocument(documentId, "r", null);

			var stream = new AutoCloseInputStream(fd);

			try {
				var exif = new ExifInterface(stream);
				var range = exif.getThumbnailRange();
				if (range != null) {
					return new AssetFileDescriptor(fd, range[0], range[1]);
				}
				stream.close();
			} catch (IOException e) {
			}
		}

		throw new FileNotFoundException();
	}

	private void buildDocumentRow(String dir, String displayName,
			MatrixCursor result, String[] thumbnails, CephStat parentStat)
			throws FileNotFoundException {
		// TODO consider EXTRA_ERROR?
		var path = dir + displayName;
		CephStat lcs = new CephStat();
		executor.executeWithUnchecked(cm -> {
			try {
				cm.lstat(path, lcs);
				return null;
			} catch (CephNotDirectoryException e) {
				throw new FileNotFoundException(e.getMessage());
			}
		});
		MatrixCursor.RowBuilder row = result.newRow();

		var wasSymlink = lcs.isSymlink();
		CephStat cs = lcs;
		if (wasSymlink) {
			cs = executor.executeWithUnchecked(cm -> {
				try {
					var ncs = new CephStat();
					cm.stat(path, ncs);
					return ncs;
				} catch (FileNotFoundException|CephNotDirectoryException e) {
					Log.e(APP_NAME, "stat: " + dir + displayName + " not found", e);
					return lcs;
				}
			});
		}
		String mimeType = getMime(cs.mode, displayName);

		for (var col : result.getColumnNames()) {
			row.add(col, switch (col) {
			case Document.COLUMN_DISPLAY_NAME -> displayName;
			case Document.COLUMN_DOCUMENT_ID -> documentIdFromPath(path);
			case Document.COLUMN_FLAGS -> {
				int flags = 0;
				switch (cs.mode & S_IFMT) {
				case S_IFLNK:
					flags |= Document.FLAG_PARTIAL;
					break;
				case S_IFDIR:
					if (mayWrite(cs)) {
						flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
					}
					if (mayRead(cs)) {
						flags |= Document.FLAG_SUPPORTS_METADATA;
					}
					break;
				case S_IFREG:
					if ((MetadataReader.isSupportedMimeType(mimeType) ||
							MediaMetadataReader.isSupportedMimeType(mimeType)) &&
							mayRead(cs)) {
						// noinspection InlinedApi
						flags |= Document.FLAG_SUPPORTS_METADATA;
					}
					if (mayWrite(cs)) {
						flags |= Document.FLAG_SUPPORTS_WRITE;
					}

					if (ExifInterface.isSupportedMimeType(mimeType)) {
						// may be available in exif, skip the search (but prefer xdg)
						flags |= Document.FLAG_SUPPORTS_THUMBNAIL;
					} else {
						String thumbnail = getXDGThumbnailFile(displayName);
						boolean thumbnailFound = false;
						if (thumbnails != null) {
							// TODO pre-sort & bsearch?
							for (String c : thumbnails) {
								if (c.equals(thumbnail)) {
									thumbnailFound = true;
									break;
								}
							}
						} else {
							String thubmailPath = dir + ".sh_thumbnails/normal/" + getXDGThumbnailFile(displayName);
							var ncs = new CephStat();
							thumbnailFound = executor.executeWithUnchecked(cm -> {
								try {
									cm.stat(thubmailPath, ncs);
									return true;
								} catch (FileNotFoundException|CephNotDirectoryException e) {
									return false;
								}
							});
						}

						if (thumbnailFound) {
							flags |= Document.FLAG_SUPPORTS_THUMBNAIL;
						}
					}
					break;
				}

				if (parentStat == null) {
					parentStat = executor.executeWithUnchecked(cm -> {
						var st = new CephStat();
						cm.stat(dir, st);
						return st;
					});
				}
				// TODO support recur
				if (mayWrite(parentStat) && !lcs.isDir()) {
					flags |= Document.FLAG_SUPPORTS_DELETE;
				}
				yield flags;
			}
			case Document.COLUMN_ICON -> {
				if (wasSymlink && cs.isDir()) {
					// DocumentsUI grid view is hard-coded to system folder icon
					yield R.drawable.ic_symlink_to_dir;
				}
				if (cs.isSymlink()) {
					yield R.drawable.ic_broken_symlink;
				}
				yield null;
			}
			case Document.COLUMN_LAST_MODIFIED -> lcs.m_time;
			case Document.COLUMN_MIME_TYPE -> getMime(cs.mode, displayName);
			case Document.COLUMN_SIZE -> lcs.size;
			case Document.COLUMN_SUMMARY -> {
				if (cs.isSymlink()) {
					var target = executor.executeWithUnchecked(cm -> cm.readlink(path));
					yield "Broken symlink to " + target;
				}
				yield null;
			}
			default -> null;
			});
		}
	}

	public Cursor queryChildDocuments(String parentDocumentId,
			String[] projection, String sortOrder)
			throws FileNotFoundException {
		var path = pathFromDocumentId(parentDocumentId);
		MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOC_PROJECTION);
		Log.v(APP_NAME, "queryChildDocuments " + parentDocumentId);
		String[] res = executor.executeWithCursorExtra(cm -> {
			return cm.listdir(path);
		}, result);
		if (res == null) {
			return result;
		}

		String[] thumbnails = null;
		try {
			thumbnails = executor.execute(cm -> {
				try {
					return cm.listdir(path + "/.sh_thumbnails/normal");
				} catch (FileNotFoundException e) {
					return new String[0];
				}
			});
		} catch (IOException e) {
			Log.w("Fail to list thumbnails directory, falling back to per-file slow path", e);
		}

		for (String entry : res) {
			buildDocumentRow(path + "/", entry, result, thumbnails, null);
		}
		return result;
	}

	public Cursor queryDocument(String documentId, String[] projection)
			throws FileNotFoundException {
		Log.v(APP_NAME, "queryDocument " + documentId);
		var path = pathFromDocumentId(documentId);
		MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOC_PROJECTION);
		int dirIndex = path.lastIndexOf("/");
		String filename = path.substring(dirIndex + 1);
		String dir = path.substring(0, dirIndex + 1);
		buildDocumentRow(dir, filename, result, null, null);
		return result;
	}

	// default implementation query without projection, which is costly
	// TODO try to upstream the idea?
	public String getDocumentType(String documentId)
			throws FileNotFoundException {
		try (var c = new UncheckedAutoCloseable<Cursor>(queryDocument(
				documentId, new String[]{Document.COLUMN_MIME_TYPE}))) {
			if (c.c().moveToFirst()) {
				return c.c().getString(
					c.c().getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE));
			} else {
				return null;
			}
		}
	}

	private long getXattrULL(String path, String name)
			throws FileNotFoundException {
		var buf = new byte[32];
		var l = executor.executeWithUnchecked(cm -> {
			return cm.getxattr(path, name, buf);
		}).intValue();
		var s = new String(buf, 0, l);
		return Long.parseUnsignedLong(s);
	}

	public Bundle getDocumentMetadata(String documentId)
			throws FileNotFoundException {
		Log.v(APP_NAME, "getDocumentMetadata " + documentId);
		String mimeType = getDocumentType(documentId);
		if (MetadataReader.isSupportedMimeType(mimeType)) {
			ParcelFileDescriptor fd = openDocument(documentId, "r", null);
			Bundle metadata = new Bundle();

			try (var stream = new UncheckedAutoCloseable<AutoCloseInputStream>(
						new AutoCloseInputStream(fd))) {
				MetadataReader.getMetadata(metadata, stream.c(), mimeType, null);
			} catch (IOException e) {
				Log.e(APP_NAME, "getMetadata: ", e);
				return null;
			}
			return metadata;
		} else if (MediaMetadataReader.isSupportedMimeType(mimeType)) {
			Bundle metadata = new Bundle();
			try (var fd = new UncheckedAutoCloseable<ParcelFileDescriptor>(
						openDocument(documentId, "r", null))){
				MediaMetadataReader.getMetadata(metadata,
					fd.c().getFileDescriptor(), mimeType);
			}
			return metadata;
		} else if (mimeType.equals(Document.MIME_TYPE_DIR)) {
			// DocumentsUI does not show this though
			var metadata = new Bundle();
			var path = pathFromDocumentId(documentId);

			metadata.putLong(DocumentsContract.METADATA_TREE_COUNT,
				getXattrULL(path, "ceph.dir.rentries"));
			metadata.putLong(DocumentsContract.METADATA_TREE_SIZE,
				getXattrULL(path, "ceph.dir.rbytes"));
			return metadata;
		}
		return null;
	}

	public Cursor queryRoots(String[] projection)
			throws FileNotFoundException {
		MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
		CephStatVFS csvfs = new CephStatVFS();
		executor.executeWithUnchecked(cm -> {
			cm.statfs(".", csvfs);
			return null;
		});

		var rootUri = executor.config.getRootUri().toString();
		MatrixCursor.RowBuilder row = result.newRow();
		row.add(Root.COLUMN_ROOT_ID, rootUri);
		row.add(Root.COLUMN_DOCUMENT_ID, rootUri);
		row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD);
		row.add(Root.COLUMN_TITLE, executor.config.getTitle());
		row.add(Root.COLUMN_ICON, R.mipmap.sym_def_app_icon);
		// DocumentsUI shows localized and humanized COLUMN_AVAILABLE_BYTES
		// when summary is not present, which is more useful and nicer
		// row.add(Root.COLUMN_SUMMARY, executor.config.getSummary());
		row.add(Root.COLUMN_CAPACITY_BYTES, csvfs.blocks * csvfs.frsize);
		row.add(Root.COLUMN_AVAILABLE_BYTES, csvfs.bavail * csvfs.frsize);
		return result;
	}
}

