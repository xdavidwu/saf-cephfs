package org.safcephfs;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Vector;

import com.ceph.fs.CephMount;
import com.ceph.fs.CephStat;
import com.ceph.fs.CephStatVFS;
import com.ceph.fs.CephNotDirectoryException;

public class CephFSDocumentsProvider extends DocumentsProvider {
	private String id, mon, path, key;
	private StorageManager sm;
	private Handler ioHandler;
	private CephMount cm = null;
	private int uid;
	private ToastThread lthread;

	private boolean checkPermissions = true;

	private static final int retries = 2;

	private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
		Root.COLUMN_ROOT_ID,
		Root.COLUMN_FLAGS,
		Root.COLUMN_ICON,
		Root.COLUMN_TITLE,
		Root.COLUMN_DOCUMENT_ID,
		Root.COLUMN_SUMMARY,
		Root.COLUMN_CAPACITY_BYTES,
		Root.COLUMN_AVAILABLE_BYTES
	};

	private static final String[] DEFAULT_DOC_PROJECTION = new String[]{
		Document.COLUMN_DOCUMENT_ID,
		Document.COLUMN_DISPLAY_NAME,
		Document.COLUMN_MIME_TYPE,
		Document.COLUMN_LAST_MODIFIED,
		Document.COLUMN_SIZE,
		Document.COLUMN_FLAGS
	};

	private static int PERM_READABLE = 1;
	private static int PERM_WRITEABLE = 2;

	private static String APP_NAME;

	private static String getMime(String filename) {
		int idx = filename.lastIndexOf(".");
		if (idx > 0) {
			String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
				filename.substring(idx + 1).toLowerCase());
			if (mime != null) {
				return mime;
			}
		}
		return "application/octet-stream";
	}

	private static final int S_IFMT = 0170000, S_IFSOCK = 0140000,
		S_IFBLK = 0060000, S_IFCHR = 0020000, S_IFIFO = 0010000;

	private static String getMimeFromMode(int mode) {
		switch (mode & S_IFMT) {
		case S_IFSOCK:
			return "inode/socket";
		case S_IFBLK:
			return "inode/blockdevice";
		case S_IFCHR:
			return "inode/chardevice";
		case S_IFIFO:
			return "inode/fifo";
		}
		return "application/octet-stream";
	}

	private int getPerm(CephStat cs) {
		int perm = 0;
		if (cs.uid == uid) {
			if ((cs.mode & 0400) == 0400) {
				perm |= PERM_READABLE;
			}
			if ((cs.mode & 0200) == 0200) {
				perm |= PERM_WRITEABLE;
			}
		} else if (cs.gid == uid) {
			if ((cs.mode & 0040) == 0040) {
				perm |= PERM_READABLE;
			}
			if ((cs.mode & 0020) == 0020) {
				perm |= PERM_WRITEABLE;
			}
		} else {
			if ((cs.mode & 0004) == 0004) {
				perm |= PERM_READABLE;
			}
			if ((cs.mode & 0002) == 0002) {
				perm |= PERM_WRITEABLE;
			}
		}
		return perm;
	}

	private void toast(String message) {
		Message msg = lthread.handler.obtainMessage();
		msg.obj = APP_NAME + ": " + message;
		lthread.handler.sendMessage(msg);
	}

	// Wrapper to make IOException from JNI catchable
	private interface CephOperation<T> {
		T execute() throws IOException;
	}

	private void throwOrAddErrorExtra(String error, Cursor cursor) {
		if (cursor != null) {
			Bundle extra = new Bundle();
			extra.putString(DocumentsContract.EXTRA_ERROR, error);
			cursor.setExtras(extra);
		} else {
			toast(error);
			throw new IllegalStateException(error);
		}
	}

	private <T> T doCephOperation(CephOperation<T> op) {
		return doCephOperation(op, null);
	}

	private <T> T doCephOperation(CephOperation<T> op, Cursor cursor) {
		if (cm == null) {
			try {
				cm = setupCeph();
			} catch (IOException e) {
				throwOrAddErrorExtra("Unable to mount root: " + e.getMessage(),
					cursor);
				return null;
			}
		}
		int r = retries;
		while (r-- != 0) {
			try {
				return op.execute();
			} catch (IOException e) {
				if (e.getMessage().equals("Cannot send after transport endpoint shutdown")) {
					if (r != 0) {
						Log.e(APP_NAME, "Mount died, " + r + "attempts remaining, retrying");
						cm.unmount();
						try {
							new CephOperation<Void>() {
								@Override
								public Void execute() throws IOException {
									cm.mount(path);
									return null;
								}
							}.execute();
						} catch (IOException e2) {
							toast("Unable to remount root: " + e2);
						}
					} else {
						throwOrAddErrorExtra("Operation failed: " +
							e.getMessage(), cursor);
						return null;
					}
				} else {
					throwOrAddErrorExtra("Operation failed: " + e.getMessage(),
						cursor);
					return null;
				}
			}
		}
		return null;
	}

	private CephMount setupCeph() throws IOException {
		SharedPreferences settings = PreferenceManager
			.getDefaultSharedPreferences(getContext());
		mon = settings.getString("mon", "");
		key = settings.getString("key", "");
		id = settings.getString("id", "");
		path = settings.getString("path", "");
		String timeout = settings.getString("timeout", "");
		timeout = timeout.matches("\\d+") ? timeout : "20";
		CephMount newMount = new CephMount(id);
		newMount.conf_set("mon_host", mon);
		newMount.conf_set("key", key);
		newMount.conf_set("client_mount_timeout", timeout);
		newMount.conf_set("client_dirsize_rbytes", "false");
		checkPermissions = settings.getBoolean("permissions", true);
		if (!checkPermissions) {
			newMount.conf_set("client_permissions", "false");
		}
		newMount.mount(path); // IOException if fails
		return newMount;
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
		return true;
	}

	public String createDocument(String parentDocumentId, String mimeType,
			String displayName) throws FileNotFoundException {
		Log.v(APP_NAME, "createDocument " + parentDocumentId + " " + mimeType + " " + displayName);
		String filename = parentDocumentId.substring(parentDocumentId.indexOf("/") + 1)
				+ "/" + displayName;
		if (mimeType.equals(Document.MIME_TYPE_DIR)) {
			doCephOperation(() -> {
				cm.mkdir(filename, 0700);
				return null;
			});
		} else {
			doCephOperation(() -> {
				try {
					int fd = cm.open(filename, CephMount.O_WRONLY | CephMount.O_CREAT | CephMount.O_EXCL, 0700);
					cm.close(fd);
					return null;
				} catch (FileNotFoundException e) {
					Log.e(APP_NAME, "Create " + filename + " not found");
					throw new FileNotFoundException(parentDocumentId + " not found");
				}
			});
		}
		return parentDocumentId + "/" + displayName;
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
		String filename = documentId.substring(documentId.indexOf("/") + 1);
		int fd = doCephOperation(() -> {
			try {
				return cm.open(filename, flag, 0);
			} catch (FileNotFoundException e) {
				Log.e(APP_NAME, "Open " + documentId + " not found");
				throw new FileNotFoundException(documentId + "not found");
			}
		});
		try {
			return sm.openProxyFileDescriptor(fdmode,
					new CephFSProxyFileDescriptorCallback(cm, fd),
					ioHandler);
		} catch (IOException e) {
			throw new IllegalStateException("openProxyFileDescriptor: " + e.toString());
		}
	}

	private String getXDGThumbnailFilePath(String base, String name) {
		MessageDigest md5;
		try {
			md5 = MessageDigest.getInstance("MD5");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException();
		}
		md5.update(("./" + name).getBytes());
		byte[] digest = md5.digest();
		String hex = String.format("%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x%02x", digest[0], digest[1], digest[2], digest[3], digest[4], digest[5], digest[6], digest[7], digest[8], digest[9], digest[10], digest[11], digest[12], digest[13], digest[14], digest[15]);

		return base + ".sh_thumbnails/normal/" + hex + ".png";
	}

	@Override
	public AssetFileDescriptor openDocumentThumbnail(String documentId,
			Point sizeHint, CancellationSignal signal)
			throws FileNotFoundException {
		int dirIndex = documentId.lastIndexOf("/");
		String filename = documentId.substring(dirIndex + 1);
		String dir = documentId.substring(0, dirIndex + 1);
		String id = getXDGThumbnailFilePath(dir, filename);
		ParcelFileDescriptor fd = openDocument(id, "r", signal);
		return new AssetFileDescriptor(fd, 0, fd.getStatSize());
	}

	private void lstatBuildDocumentRow(String dir, String displayName,
			String documentId, MatrixCursor result)
			throws FileNotFoundException {
		CephStat cs = new CephStat();
		doCephOperation(() -> {
			try {
				cm.lstat(dir + displayName, cs);
				return null;
			} catch (FileNotFoundException|CephNotDirectoryException e) {
				Log.e(APP_NAME, "lstat: " + dir + displayName + " not found");
				throw new FileNotFoundException(documentId + " not found");
			}
		});
		MatrixCursor.RowBuilder row = result.newRow();
		row.add(Document.COLUMN_DOCUMENT_ID, documentId);
		row.add(Document.COLUMN_DISPLAY_NAME, displayName);
		row.add(Document.COLUMN_SIZE, cs.size);
		row.add(Document.COLUMN_LAST_MODIFIED, cs.m_time);
		if (cs.isSymlink()) {
			doCephOperation(() -> {
				try {
					cm.stat(dir + displayName, cs);
					return null;
				} catch (FileNotFoundException|CephNotDirectoryException e) {
					Log.e(APP_NAME, "stat: " + dir + displayName + " not found");
					return null;
				}
			});
		}
		if (cs.isDir()) {
			row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR);
			if (!checkPermissions ||
					(getPerm(cs) & PERM_WRITEABLE) == PERM_WRITEABLE) {
				row.add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE);
			}
		} else if (cs.isFile()) {
			String mimeType = getMime(displayName);
			row.add(Document.COLUMN_MIME_TYPE, mimeType);
			int flags = 0;
			if (MetadataReader.isSupportedMimeType(mimeType) ||
					MediaMetadataReader.isSupportedMimeType(mimeType) &&
					(!checkPermissions ||
					(getPerm(cs) & PERM_READABLE) == PERM_READABLE)) {
				flags |= Document.FLAG_SUPPORTS_METADATA;
			}
			if (!checkPermissions ||
					(getPerm(cs) & PERM_WRITEABLE) == PERM_WRITEABLE) {
				flags |= Document.FLAG_SUPPORTS_WRITE;
			}

			String thubmailPath = getXDGThumbnailFilePath(dir, displayName);
			boolean thumnailFound = doCephOperation(() -> {
				try {
					cm.stat(thubmailPath, cs);
					return true;
				} catch (FileNotFoundException|CephNotDirectoryException e) {
					return false;
				}
			});
			if (thumnailFound) {
				flags |= Document.FLAG_SUPPORTS_THUMBNAIL;
			}
			row.add(Document.COLUMN_FLAGS, flags);
		} else if (cs.isSymlink()) {
			row.add(Document.COLUMN_MIME_TYPE, "inode/symlink");
		} else {
			row.add(Document.COLUMN_MIME_TYPE, getMimeFromMode(cs.mode));
		}
	}

	public Cursor queryChildDocuments(String parentDocumentId,
			String[] projection, String sortOrder)
			throws FileNotFoundException {
		MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOC_PROJECTION);
		Log.v(APP_NAME, "queryChildDocuments " + parentDocumentId);
		String filename = parentDocumentId.substring(parentDocumentId.indexOf("/") + 1);
		String[] res = doCephOperation(() -> {
			try {
				return cm.listdir(filename);
			} catch (FileNotFoundException e) {
				Log.e(APP_NAME, "queryChildDocuments " + parentDocumentId + " not found");
				throw new FileNotFoundException(parentDocumentId + " not found");
			}
		}, result);
		if (res == null) {
			return result;
		}
		for (String entry : res) {
			lstatBuildDocumentRow(filename + "/", entry,
					parentDocumentId + "/" + entry, result);
		}
		return result;
	}

	public Cursor queryDocument(String documentId, String[] projection)
			throws FileNotFoundException {
		Log.v(APP_NAME, "queryDocument " + documentId);
		MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOC_PROJECTION);
		int dirIndex = documentId.lastIndexOf("/");
		String filename = documentId.substring(dirIndex + 1);
		String dir = documentId.substring(0, dirIndex + 1);
		lstatBuildDocumentRow(dir.substring(dir.indexOf("/") + 1), filename,
				documentId, result);
		return result;
	}

	public Bundle getDocumentMetadata(String documentId)
			throws FileNotFoundException {
		Log.v(APP_NAME, "getDocumentMetadata " + documentId);
		String mimeType = getDocumentType(documentId);
		if (MetadataReader.isSupportedMimeType(mimeType)) {
			ParcelFileDescriptor fd = openDocument(documentId, "r", null);
			AutoCloseInputStream stream = new AutoCloseInputStream(fd);

			Bundle metadata = new Bundle();
			try {
				MetadataReader.getMetadata(metadata, stream, mimeType, null);
			} catch (IOException e) {
				Log.e(APP_NAME, "getMetadata: ", e);
				return null;
			}
			return metadata;
		} else if (MediaMetadataReader.isSupportedMimeType(mimeType)) {
			ParcelFileDescriptor fd = openDocument(documentId, "r", null);

			Bundle metadata = new Bundle();
			MediaMetadataReader.getMetadata(metadata, fd.getFileDescriptor(),
				mimeType);
			try {
				fd.close();
			} catch (IOException e) {
				Log.e(APP_NAME, "getMetadata: video fd close: ", e);
				return null;
			}
			return metadata;
		}
		return null;
	}

	public Cursor queryRoots(String[] projection)
			throws FileNotFoundException {
		MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
		if (cm != null) {
			cm.unmount();
			cm = null;
		}
		CephStatVFS csvfs = new CephStatVFS();
		doCephOperation(() -> {
			try {
				cm.statfs("/", csvfs);
				return null;
			} catch (FileNotFoundException e) {
				throw new FileNotFoundException("/ not found");
			}
		});
		MatrixCursor.RowBuilder row = result.newRow();
		row.add(Root.COLUMN_ROOT_ID, id + "@" + mon + ":" + path);
		row.add(Root.COLUMN_DOCUMENT_ID, "root/");
		row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_IS_CHILD);
		row.add(Root.COLUMN_TITLE, mon + ":" + path);
		row.add(Root.COLUMN_ICON, R.mipmap.sym_def_app_icon);
		row.add(Root.COLUMN_SUMMARY, "CephFS with user: " + id);
		row.add(Root.COLUMN_CAPACITY_BYTES, csvfs.blocks * csvfs.frsize);
		row.add(Root.COLUMN_AVAILABLE_BYTES, csvfs.bavail * csvfs.frsize);
		return result;
	}
}

