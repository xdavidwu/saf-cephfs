package org.safcephfs;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.storage.StorageManager;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;
import android.widget.Toast;
import android.util.Log;

import java.io.FileNotFoundException;
import java.io.IOException;
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
			String mime = MimeTypeMap.getSingleton()
				.getMimeTypeFromExtension(
					filename.substring(idx + 1).toLowerCase()
				);
			if (mime != null) {
				return mime;
			}
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

	private interface CephOperation<T> {
		T execute() throws IOException;
	}

	private <T> T doCephOperation(CephOperation<T> op) {
		if (cm == null) {
			setupCeph();
		}
		int r = retries;
		while (r-- != 0) {
			try {
				return op.execute();
			} catch (IOException e) { // from jni
				if (e.getMessage().equals("Cannot send after transport endpoint shutdown")) {
					if (r != 0) {
						Log.e(APP_NAME, "mount died, retrying");
						cm.unmount();
						try {
							cm.mount(path);
						} catch (IOException e2) {
							Message msg = lthread.handler.obtainMessage();
							msg.obj = APP_NAME + ": unable to remount root: " + e2.toString();
							lthread.handler.sendMessage(msg);
						}
					} else {
						Log.e(APP_NAME, "mount died and tried our best");
						throw new IllegalStateException("ESHUTDOWN");
					}
				} else {
					Message msg = lthread.handler.obtainMessage();
					msg.obj = APP_NAME + ": operation failed: " + e.getMessage();
					lthread.handler.sendMessage(msg);
					throw new IllegalStateException(e.getMessage());
				}
			}
		}
		return null;
	}

	private boolean setupCeph() {
		SharedPreferences settings = PreferenceManager
			.getDefaultSharedPreferences(getContext());
		mon = settings.getString("mon", "");
		key = settings.getString("key", "");
		id = settings.getString("id", "");
		path = settings.getString("path", "");
		String timeout = settings.getString("timeout", "");
		timeout = timeout.matches("\\d+") ? timeout : "20";
		cm = new CephMount(id);
		cm.conf_set("mon_host", mon);
		cm.conf_set("key", key);
		cm.conf_set("client_mount_timeout", timeout);
		checkPermissions = settings.getBoolean("permissions", true);
		if (!checkPermissions) {
			cm.conf_set("client_permissions", "false");
		}
		try {
			cm.mount(path);
		} catch (IOException e) { // from jni
			Message msg = lthread.handler.obtainMessage();
			String error = "unable to mount root: " + e.toString();
			msg.obj = APP_NAME + ": " + error;
			lthread.handler.sendMessage(msg);
			Log.e(APP_NAME, error);
			cm = null;
			return false;
		}
		return true;
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
		Log.v(APP_NAME, "create " + parentDocumentId + " " + mimeType + " " + displayName);
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
					Log.e(APP_NAME, "create " + filename + " not found");
					throw new FileNotFoundException(parentDocumentId + "not found");
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
		Log.v(APP_NAME, "open " + mode + " " + documentId);
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
				Log.e(APP_NAME, "open " + documentId + " not found");
				throw new FileNotFoundException(documentId + "not found");
			}
		});
		try {
			return sm.openProxyFileDescriptor(fdmode,
					new CephFSProxyFileDescriptorCallback(cm, fd),
					ioHandler);
		} catch (IOException e) {
			Log.e(APP_NAME, "open " + documentId + " " + e.toString());
			Message msg = lthread.handler.obtainMessage();
			msg.obj = e.toString();
			lthread.handler.sendMessage(msg);
			throw new IllegalStateException("IOException from openProxyFileDescriptor");
		}
	}

	public Cursor queryChildDocuments(String parentDocumentId,
			String[] projection, String sortOrder)
			throws FileNotFoundException {
		MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOC_PROJECTION);
		Log.v(APP_NAME, "qdf " + parentDocumentId);
		String filename = parentDocumentId.substring(parentDocumentId.indexOf("/") + 1);
		String[] res = doCephOperation(() -> {
			try {
				return cm.listdir(filename);
			} catch (FileNotFoundException e) {
				Log.e(APP_NAME, "qdf " + parentDocumentId + " not found");
				throw new FileNotFoundException(parentDocumentId + " not found");
			}
		});
		CephStat cs = new CephStat();
		for (String entry : res) {
			Log.v(APP_NAME, "qdf " + parentDocumentId + " " + entry);
			doCephOperation(() -> {
				try {
					cm.lstat(filename + "/" + entry, cs);
					return null;
				} catch (FileNotFoundException|CephNotDirectoryException e) {
					Log.e(APP_NAME, "qdf " + parentDocumentId + ": " + entry + " not found");
					throw new FileNotFoundException(parentDocumentId + "/" + entry + " not found");
				}
			});
			MatrixCursor.RowBuilder row = result.newRow();
			row.add(Document.COLUMN_DOCUMENT_ID, parentDocumentId + "/" + entry);
			row.add(Document.COLUMN_DISPLAY_NAME, entry);
			if (cs.isDir()) {
				row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR);
				if (!checkPermissions || (getPerm(cs) & PERM_WRITEABLE) == PERM_WRITEABLE) {
					row.add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE);
				}
			} else if (cs.isFile() || cs.isSymlink()) {
				row.add(Document.COLUMN_MIME_TYPE, getMime(entry));
				if (!checkPermissions || (getPerm(cs) & PERM_WRITEABLE) == PERM_WRITEABLE) {
					row.add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_WRITE);
				}
			}
			row.add(Document.COLUMN_SIZE, cs.size);
			row.add(Document.COLUMN_LAST_MODIFIED, cs.m_time);
		}
		return result;
	}

	public Cursor queryDocument(String documentId, String[] projection)
			throws FileNotFoundException {
		MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOC_PROJECTION);
		String filename = documentId.substring(documentId.indexOf("/") + 1);
		CephStat cs = new CephStat();
		Log.v(APP_NAME, "qd " + documentId);
		doCephOperation(() -> {
			try {
				cm.lstat(filename, cs);
				return null;
			} catch (FileNotFoundException|CephNotDirectoryException e) {
				Log.e(APP_NAME, "qd " + documentId + " not found");
				throw new FileNotFoundException(documentId + " not found");
			}
		});
		MatrixCursor.RowBuilder row = result.newRow();
		row.add(Document.COLUMN_DOCUMENT_ID, documentId);
		row.add(Document.COLUMN_DISPLAY_NAME, filename);
		if (cs.isDir()) {
			row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR);
			if (!checkPermissions || (getPerm(cs) & PERM_WRITEABLE) == PERM_WRITEABLE) {
				row.add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE);
			}
		} else if (cs.isFile() || cs.isSymlink()) {
			row.add(Document.COLUMN_MIME_TYPE, getMime(filename));
			if (!checkPermissions || (getPerm(cs) & PERM_WRITEABLE) == PERM_WRITEABLE) {
				row.add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_WRITE);
			}
		}
		row.add(Document.COLUMN_SIZE, cs.size);
		row.add(Document.COLUMN_LAST_MODIFIED, cs.m_time);
		return result;
	}

	public Cursor queryRoots(String[] projection)
			throws FileNotFoundException {
		MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
		if (cm != null) {
			cm.unmount();
		}
		if (!setupCeph()) {
			return result;
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

