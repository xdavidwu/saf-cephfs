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
import java.util.Vector;

import com.ceph.fs.CephMount;
import com.ceph.fs.CephStat;
import com.ceph.fs.CephNotDirectoryException;

public class CephFSDocumentsProvider extends DocumentsProvider {
	private String id, mon, path, key;
	private StorageManager sm;
	private Handler ioHandler;
	private CephMount cm;
	private ToastThread lthread;
	
	private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
		Root.COLUMN_ROOT_ID,
		Root.COLUMN_FLAGS,
		Root.COLUMN_ICON,
		Root.COLUMN_TITLE,
		Root.COLUMN_DOCUMENT_ID,
	};

	private static final String[] DEFAULT_DOC_PROJECTION = new String[]{
		Document.COLUMN_DOCUMENT_ID,
		Document.COLUMN_DISPLAY_NAME,
		Document.COLUMN_MIME_TYPE,
		Document.COLUMN_LAST_MODIFIED,
		Document.COLUMN_SIZE
	};

	private static String getMime(String filename) {
		int idx = filename.lastIndexOf(".");
		if (idx > 0) {
			String mime = MimeTypeMap.getSingleton()
				.getMimeTypeFromExtension(
					filename.substring(idx + 1).toLowerCase()
				);
			if (mime != null) return mime;
		}
		return "application/octet-stream";
	}

	@Override
	public boolean onCreate() {
		sm = (StorageManager) getContext()
			.getSystemService(Context.STORAGE_SERVICE);
		lthread = new ToastThread(getContext());
		lthread.start();
		HandlerThread ioThread = new HandlerThread("IO thread");
		ioThread.start();
		ioHandler = new Handler(ioThread.getLooper());
		SharedPreferences settings = PreferenceManager
			.getDefaultSharedPreferences(getContext());
		mon = settings.getString("mon", "");
		key = settings.getString("key", "");
		id = settings.getString("id", "");
		path = settings.getString("path", "");
		cm = new CephMount(id);
		cm.conf_set("mon_host", mon);
		cm.conf_set("key", key);
		cm.mount(path);
		return true;
	}

	public ParcelFileDescriptor openDocument(String documentId,
			String mode,CancellationSignal cancellationSignal) 
			throws UnsupportedOperationException,
			FileNotFoundException {
		Log.v("CephFS","open " + documentId);
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
		int fd;
		try {
			fd = cm.open(filename, flag, 0);
		} catch (FileNotFoundException e) {
			Log.e("CephFS","open " + documentId + " not found");
			throw new FileNotFoundException(documentId + "not found");
		}
		try {
			return sm.openProxyFileDescriptor(fdmode,
					new CephFSProxyFileDescriptorCallback(cm, fd),
					ioHandler);
		} catch (Exception e) {
			Log.e("CephFS","open " + documentId + " " + e.toString());
			Message msg = lthread.handler.obtainMessage();
			msg.obj = e.toString();
			lthread.handler.sendMessage(msg);
			return null;
		}
	}

	public Cursor queryChildDocuments(String parentDocumentId,
			String[] projection, String sortOrder)
			throws FileNotFoundException {
		MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOC_PROJECTION);
		Log.v("CephFS", "qdf " + parentDocumentId);
		String filename = parentDocumentId.substring(parentDocumentId.indexOf("/") + 1);
		String[] res;
		try {
			res = cm.listdir(filename);
		} catch (FileNotFoundException e) {
			Log.e("CephFS", "qdf " + parentDocumentId + " not found");
			throw new FileNotFoundException(parentDocumentId + " not found");
		}
		CephStat cs = new CephStat();
		for (String entry : res) {
			Log.v("CephFS", "qdf " + parentDocumentId + " " + entry);
			try {
				cm.lstat(filename + "/" + entry, cs);
			} catch (FileNotFoundException|CephNotDirectoryException e) {
				Log.e("CephFS", "qdf " + parentDocumentId + ": " + entry + " not found");
				throw new FileNotFoundException(parentDocumentId + "/" + entry + " not found");
			}
			MatrixCursor.RowBuilder row = result.newRow();
			row.add(Document.COLUMN_DOCUMENT_ID, parentDocumentId + "/" + entry);
			row.add(Document.COLUMN_DISPLAY_NAME, entry);
			if (cs.isDir()) {
				row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR);
			} else if (cs.isFile()) {
				row.add(Document.COLUMN_MIME_TYPE, getMime(entry));
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
		Log.v("CephFS", "qd " + documentId);
		try {
			cm.lstat(filename, cs);
		} catch (FileNotFoundException|CephNotDirectoryException e) {
			Log.e("CephFS", "qd " + documentId + " not found");
			throw new FileNotFoundException(documentId + " not found");
		}
		MatrixCursor.RowBuilder row = result.newRow();
		row.add(Document.COLUMN_DOCUMENT_ID, documentId);
		row.add(Document.COLUMN_DISPLAY_NAME, filename);
		if (cs.isDir()) {
			row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR);
		} else if (cs.isFile()) {
			row.add(Document.COLUMN_MIME_TYPE, getMime(filename));
		}
		row.add(Document.COLUMN_SIZE, cs.size);
		row.add(Document.COLUMN_LAST_MODIFIED, cs.m_time);
		return result;
	}

	public Cursor queryRoots(String[] projection) {
		MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
		SharedPreferences settings = PreferenceManager
			.getDefaultSharedPreferences(getContext());
		mon = settings.getString("mon", "");
		key = settings.getString("key", "");
		id = settings.getString("id", "");
		path = settings.getString("path", "");
		cm = new CephMount(id);
		cm.conf_set("mon_host", mon);
		cm.conf_set("key", key);
		cm.mount(path);
		MatrixCursor.RowBuilder row = result.newRow();
		row.add(Root.COLUMN_ROOT_ID, id + "@" + mon + ":" + path);
		row.add(Root.COLUMN_DOCUMENT_ID, "root/");
		row.add(Root.COLUMN_FLAGS, 0);
		row.add(Root.COLUMN_TITLE,"CephFS " + mon + ":" + path);
		row.add(Root.COLUMN_ICON, R.mipmap.sym_def_app_icon);
		return result;
	}
}

