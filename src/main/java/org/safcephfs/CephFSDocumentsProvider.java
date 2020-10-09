package org.safcephfs;

import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.StrictMode;
import android.preference.PreferenceManager;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;
import android.widget.Toast;
import android.util.Log;

import java.io.IOException;
import java.util.Vector;

import com.ceph.fs.CephMount;
import com.ceph.fs.CephStat;

public class CephFSDocumentsProvider extends DocumentsProvider {
	private String id, mon, path, key;
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
		lthread = new ToastThread(getContext());
		lthread.start();
		return true;
	}

	public ParcelFileDescriptor openDocument(String documentId,
			String mode,CancellationSignal cancellationSignal) {
		throw new UnsupportedOperationException("TODO");
	}

	public Cursor queryChildDocuments(String parentDocumentId,
			String[] projection,String sortOrder) {
		MatrixCursor result=new MatrixCursor(projection != null ? projection : DEFAULT_DOC_PROJECTION);
		Log.v("CephFS","qcf " + parentDocumentId);
		String filename = parentDocumentId.substring(parentDocumentId.indexOf("/") + 1);
		CephMount cm = new CephMount(id);
		cm.conf_set("mon_host", mon);
		cm.conf_set("key", key);
		cm.mount(path);
		try {
			String[] res = cm.listdir(filename);
			for (String entry : res) {
				CephStat cs = new CephStat();
				Log.v("CephFS","qcf " + parentDocumentId + " " + entry);
				cm.lstat(filename + "/" + entry, cs);
				MatrixCursor.RowBuilder row = result.newRow();
				row.add(Document.COLUMN_DOCUMENT_ID, parentDocumentId + '/' + entry);
				row.add(Document.COLUMN_DISPLAY_NAME, entry);
				if (cs.isDir()) {
					row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR);
				} else if (cs.isFile()) {
					row.add(Document.COLUMN_MIME_TYPE, getMime(entry));
				}
				row.add(Document.COLUMN_SIZE, cs.size);
				row.add(Document.COLUMN_LAST_MODIFIED, cs.m_time);
			}
		} catch (Exception e) {
			Log.e("CephFS","qcf " + parentDocumentId + " " + e.toString());
			Message msg = lthread.handler.obtainMessage();
			msg.obj = e.toString();
			lthread.handler.sendMessage(msg);
		}
		cm.unmount();
		return result;
	}

	public Cursor queryDocument(String documentId, String[] projection) {
		MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOC_PROJECTION);
		String filename = documentId.substring(documentId.indexOf("/") + 1);
		CephMount cm = new CephMount(id);
		cm.conf_set("mon_host", mon);
		cm.conf_set("key", key);
		cm.mount(path);
		try {
			CephStat cs = new CephStat();
			Log.v("CephFS","qf " + documentId);
			cm.lstat(filename, cs);
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
		} catch(Exception e){
			Log.e("CephFS","qf " + documentId + " " + e.toString());
			Message msg = lthread.handler.obtainMessage();
			msg.obj = e.toString();
			lthread.handler.sendMessage(msg);
		}
		cm.unmount();
		return result;
	}

	public Cursor queryRoots(String[] projection) {
		MatrixCursor result=new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
		SharedPreferences settings=PreferenceManager
			.getDefaultSharedPreferences(getContext());
		mon = settings.getString("mon", "");
		key = settings.getString("key", "");
		id = settings.getString("id", "");
		path = settings.getString("path", "");
		MatrixCursor.RowBuilder row=result.newRow();
		row.add(Root.COLUMN_ROOT_ID, id + "@" + mon + ":" + path);
		row.add(Root.COLUMN_DOCUMENT_ID, "root/");
		row.add(Root.COLUMN_FLAGS, 0);
		row.add(Root.COLUMN_TITLE,"CephFS " + mon + ":" + path);
		row.add(Root.COLUMN_ICON, R.mipmap.sym_def_app_icon);
		return result;
	}
}

