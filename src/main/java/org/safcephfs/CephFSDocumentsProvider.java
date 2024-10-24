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
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Vector;
import java.util.Locale;

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

	private static String APP_NAME;

	private static String getMimeFromName(String filename) {
		int idx = filename.lastIndexOf(".");
		if (idx > 0) {
			String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
				filename.substring(idx + 1).toLowerCase(Locale.ROOT));
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

	private int getPerm(CephStat cs) {
		return (cs.uid == uid ? cs.mode >> 6 :
				cs.gid == uid ? cs.mode >> 3 : cs.mode) & 7;
	}

	private void toast(String message) {
		Message msg = lthread.handler.obtainMessage();
		msg.obj = APP_NAME + ": " + message;
		lthread.handler.sendMessage(msg);
	}

	private CephFSOperations.Operation<CephMount> setupMount = () -> {
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
	};

	// TODO refactor to a executor, and port to CephFSProxyFileDescriptorCallback
	private <T> CephFSOperations.Operation<T> withLazyRetriedMount(
			CephFSOperations.Operation<T> op) {
		return () -> {
			if (cm == null) {
				cm = setupMount.execute();
			}
			return CephFSOperations.retryOnESHUTDOWN(
				() -> {
					cm.unmount();
					Log.e(APP_NAME, "Mount died, retrying");
					cm = setupMount.execute();
					return null;
				},
				op).execute();
		};
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
			CephFSOperations.translateToUnchecked(withLazyRetriedMount(() -> {
				cm.mkdir(filename, 0700);
				return null;
			}));
		} else {
			CephFSOperations.translateToUnchecked(withLazyRetriedMount(() -> {
				int fd = cm.open(filename, CephMount.O_WRONLY | CephMount.O_CREAT | CephMount.O_EXCL, 0700);
				cm.close(fd);
				return null;
			}));
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
		int fd = CephFSOperations.translateToUnchecked(withLazyRetriedMount(() -> {
			return cm.open(filename, flag, 0);
		}));

		try {
			return sm.openProxyFileDescriptor(
				fdmode, new CephFSProxyFileDescriptorCallback(cm, fd), ioHandler);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
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
		int dirIndex = documentId.lastIndexOf("/");
		String filename = documentId.substring(dirIndex + 1);
		String dir = documentId.substring(0, dirIndex + 1);
		String id = dir + ".sh_thumbnails/normal/" + getXDGThumbnailFile(filename);
		ParcelFileDescriptor fd = openDocument(id, "r", signal);
		return new AssetFileDescriptor(fd, 0, fd.getStatSize());
	}

	// TODO bsearch thumbnails
	private void lstatBuildDocumentRow(String dir, String displayName,
			String documentId, String[] thumbnails, MatrixCursor result)
			throws FileNotFoundException {
		// TODO consider EXTRA_ERROR?
		CephStat cs = new CephStat();
		CephFSOperations.translateToUnchecked(withLazyRetriedMount(() -> {
			try {
				cm.lstat(dir + displayName, cs);
				return null;
			} catch (CephNotDirectoryException e) {
				throw new FileNotFoundException(e.getMessage());
			}
		}));
		MatrixCursor.RowBuilder row = result.newRow();
		row.add(Document.COLUMN_DOCUMENT_ID, documentId);
		row.add(Document.COLUMN_DISPLAY_NAME, displayName);
		row.add(Document.COLUMN_SIZE, cs.size);
		row.add(Document.COLUMN_LAST_MODIFIED, cs.m_time);

		if (cs.isSymlink()) {
			CephFSOperations.translateToUnchecked(withLazyRetriedMount(() -> {
				try {
					cm.stat(dir + displayName, cs);
					return null;
				} catch (FileNotFoundException|CephNotDirectoryException e) {
					Log.e(APP_NAME, "stat: " + dir + displayName + " not found", e);
					return null;
				}
			}));
		}
		String mimeType = getMime(cs.mode, displayName);
		row.add(Document.COLUMN_MIME_TYPE, mimeType);

		if (cs.isDir()) {
			if (!checkPermissions ||
					(getPerm(cs) & S_IW) == S_IW) {
				row.add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE);
			}
		} else if (cs.isFile()) {
			int flags = 0;
			if (MetadataReader.isSupportedMimeType(mimeType) ||
					MediaMetadataReader.isSupportedMimeType(mimeType) &&
					(!checkPermissions ||
					(getPerm(cs) & S_IR) == S_IR)) {
				// noinspection InlinedApi
				flags |= Document.FLAG_SUPPORTS_METADATA;
			}
			if (!checkPermissions ||
					(getPerm(cs) & S_IW) == S_IW) {
				flags |= Document.FLAG_SUPPORTS_WRITE;
			}

			String thumbnail = getXDGThumbnailFile(displayName);
			boolean thumbnailFound = false;
			if (thumbnails != null) {
				for (String c : thumbnails) {
					if (c.equals(thumbnail)) {
						thumbnailFound = true;
						break;
					}
				}
			} else {
				String thubmailPath = dir + ".sh_thumbnails/normal/" + getXDGThumbnailFile(displayName);
				thumbnailFound = CephFSOperations.translateToUnchecked(withLazyRetriedMount(() -> {
					try {
						cm.stat(thubmailPath, cs);
						return true;
					} catch (FileNotFoundException|CephNotDirectoryException e) {
						return false;
					}
				}));
			}

			if (thumbnailFound) {
				flags |= Document.FLAG_SUPPORTS_THUMBNAIL;
			}
			row.add(Document.COLUMN_FLAGS, flags);
		}
	}

	public Cursor queryChildDocuments(String parentDocumentId,
			String[] projection, String sortOrder)
			throws FileNotFoundException {
		MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOC_PROJECTION);
		Log.v(APP_NAME, "queryChildDocuments " + parentDocumentId);
		String filename = parentDocumentId.substring(parentDocumentId.indexOf("/") + 1);
		String[] res = CephFSOperations.translateToCursorExtra(withLazyRetriedMount(() -> {
			return cm.listdir(filename);
		}), result);
		if (res == null) {
			return result;
		}

		// TODO make this not fatal instead?
		String[] thumbnails = CephFSOperations.translateToCursorExtra(withLazyRetriedMount(() -> {
			try {
				return cm.listdir(filename + "/.sh_thumbnails/normal");
			} catch (FileNotFoundException e) {
				return new String[0];
			}
		}), result);
		if (res == null) {
			return result;
		}

		for (String entry : res) {
			lstatBuildDocumentRow(filename + "/", entry,
					parentDocumentId + "/" + entry, thumbnails, result);
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
				documentId, null, result);
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
		CephFSOperations.translateToUnchecked(withLazyRetriedMount(() -> {
			cm.statfs("/", csvfs);
			return null;
		}));
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

