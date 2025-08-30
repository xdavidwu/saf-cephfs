package link.xdavidwu.saf.cephfs;

import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.system.ErrnoException;
import android.system.OsConstants;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

import com.ceph.fs.CephMount;

public class CephFSExecutor {
	protected record CephMountConfig(
			String id, String path, Map<String, Object> config) {

		protected Uri getRootUri() {
			var builder = new Uri.Builder();
			var uri = builder.scheme("cephfs").authority(id + "@" + config.get("mon_host")).build();
			return uri;
		}

		protected String getTitle() {
			return config.get("mon_host") + ":" + path;
		}

		protected String getSummary() {
			return "CephFS with user: " + id;
		}
	}

	protected CephMountConfig config;
	private CephMount cm;

	protected CephFSExecutor(CephMountConfig config) {
		this.config = config;
	}

	protected interface Operation<T> {
		T execute(CephMount cm) throws IOException;
	}

	protected Operation<CephMount> mount = unused -> {
		CephMount m = new CephMount(config.id);
		config.config.forEach((k, v) -> m.conf_set(k, v.toString()));
		m.mount(config.path);
		return m;
	};

	/*
	 * libcephfs_jni throws IOException with message from strerror()
	 * Bionic strerror:
	 * https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/private/bionic_errdefs.h
	 * sed 's|__BIONIC_ERRDEF(\([^,]*\), \([^)]*\).*|strerrorT.put(\2, OsConstants.\1);|'
	 * and delete where there are no matching OsConstants
	 * OsConstants are not compile-time const, but initialized at runtime via initConstants jni call
	 */
	private static Map<String, Integer> strerrorT = new HashMap<String, Integer>();
	static {
		strerrorT.put("Operation not permitted", OsConstants.EPERM);
		strerrorT.put("No such file or directory", OsConstants.ENOENT);
		strerrorT.put("No such process", OsConstants.ESRCH);
		strerrorT.put("Interrupted system call", OsConstants.EINTR);
		strerrorT.put("I/O error", OsConstants.EIO);
		strerrorT.put("No such device or address", OsConstants.ENXIO);
		strerrorT.put("Argument list too long", OsConstants.E2BIG);
		strerrorT.put("Exec format error", OsConstants.ENOEXEC);
		strerrorT.put("Bad file descriptor", OsConstants.EBADF);
		strerrorT.put("No child processes", OsConstants.ECHILD);
		strerrorT.put("Try again", OsConstants.EAGAIN);
		strerrorT.put("Out of memory", OsConstants.ENOMEM);
		strerrorT.put("Permission denied", OsConstants.EACCES);
		strerrorT.put("Bad address", OsConstants.EFAULT);
		strerrorT.put("Device or resource busy", OsConstants.EBUSY);
		strerrorT.put("File exists", OsConstants.EEXIST);
		strerrorT.put("Cross-device link", OsConstants.EXDEV);
		strerrorT.put("No such device", OsConstants.ENODEV);
		strerrorT.put("Not a directory", OsConstants.ENOTDIR);
		strerrorT.put("Is a directory", OsConstants.EISDIR);
		strerrorT.put("Invalid argument", OsConstants.EINVAL);
		strerrorT.put("File table overflow", OsConstants.ENFILE);
		strerrorT.put("Too many open files", OsConstants.EMFILE);
		strerrorT.put("Inappropriate ioctl for device", OsConstants.ENOTTY);
		strerrorT.put("Text file busy", OsConstants.ETXTBSY);
		strerrorT.put("File too large", OsConstants.EFBIG);
		strerrorT.put("No space left on device", OsConstants.ENOSPC);
		strerrorT.put("Illegal seek", OsConstants.ESPIPE);
		strerrorT.put("Read-only file system", OsConstants.EROFS);
		strerrorT.put("Too many links", OsConstants.EMLINK);
		strerrorT.put("Broken pipe", OsConstants.EPIPE);
		strerrorT.put("Math argument out of domain of func", OsConstants.EDOM);
		strerrorT.put("Math result not representable", OsConstants.ERANGE);
		strerrorT.put("Resource deadlock would occur", OsConstants.EDEADLK);
		strerrorT.put("File name too long", OsConstants.ENAMETOOLONG);
		strerrorT.put("No record locks available", OsConstants.ENOLCK);
		strerrorT.put("Function not implemented", OsConstants.ENOSYS);
		strerrorT.put("Directory not empty", OsConstants.ENOTEMPTY);
		strerrorT.put("Too many symbolic links encountered", OsConstants.ELOOP);
		strerrorT.put("No message of desired type", OsConstants.ENOMSG);
		strerrorT.put("Identifier removed", OsConstants.EIDRM);
		strerrorT.put("Device not a stream", OsConstants.ENOSTR);
		strerrorT.put("No data available", OsConstants.ENODATA);
		strerrorT.put("Timer expired", OsConstants.ETIME);
		strerrorT.put("Out of streams resources", OsConstants.ENOSR);
		if (Build.VERSION.SDK_INT >= 31) {
			strerrorT.put("Machine is not on the network", OsConstants.ENONET);
		}
		strerrorT.put("Link has been severed", OsConstants.ENOLINK);
		strerrorT.put("Protocol error", OsConstants.EPROTO);
		strerrorT.put("Multihop attempted", OsConstants.EMULTIHOP);
		strerrorT.put("Not a data message", OsConstants.EBADMSG);
		strerrorT.put("Value too large for defined data type", OsConstants.EOVERFLOW);
		strerrorT.put("Illegal byte sequence", OsConstants.EILSEQ);
		strerrorT.put("Socket operation on non-socket", OsConstants.ENOTSOCK);
		strerrorT.put("Destination address required", OsConstants.EDESTADDRREQ);
		strerrorT.put("Message too long", OsConstants.EMSGSIZE);
		strerrorT.put("Protocol wrong type for socket", OsConstants.EPROTOTYPE);
		strerrorT.put("Protocol not available", OsConstants.ENOPROTOOPT);
		strerrorT.put("Protocol not supported", OsConstants.EPROTONOSUPPORT);
		strerrorT.put("Operation not supported on transport endpoint", OsConstants.EOPNOTSUPP);
		strerrorT.put("Address family not supported by protocol", OsConstants.EAFNOSUPPORT);
		strerrorT.put("Address already in use", OsConstants.EADDRINUSE);
		strerrorT.put("Cannot assign requested address", OsConstants.EADDRNOTAVAIL);
		strerrorT.put("Network is down", OsConstants.ENETDOWN);
		strerrorT.put("Network is unreachable", OsConstants.ENETUNREACH);
		strerrorT.put("Network dropped connection because of reset", OsConstants.ENETRESET);
		strerrorT.put("Software caused connection abort", OsConstants.ECONNABORTED);
		strerrorT.put("Connection reset by peer", OsConstants.ECONNRESET);
		strerrorT.put("No buffer space available", OsConstants.ENOBUFS);
		strerrorT.put("Transport endpoint is already connected", OsConstants.EISCONN);
		strerrorT.put("Transport endpoint is not connected", OsConstants.ENOTCONN);
		strerrorT.put("Connection timed out", OsConstants.ETIMEDOUT);
		strerrorT.put("Connection refused", OsConstants.ECONNREFUSED);
		strerrorT.put("No route to host", OsConstants.EHOSTUNREACH);
		strerrorT.put("Operation already in progress", OsConstants.EALREADY);
		strerrorT.put("Operation now in progress", OsConstants.EINPROGRESS);
		strerrorT.put("Stale NFS file handle", OsConstants.ESTALE);
		strerrorT.put("Quota exceeded", OsConstants.EDQUOT);
		strerrorT.put("Operation Canceled", OsConstants.ECANCELED);
	}
	private static int cephIOEToOsConstants(IOException e) {
		var errno = strerrorT.get(e.getMessage());
		return errno == null ? OsConstants.EIO : errno;
	}

	protected <T> T execute(Operation<T> op) throws IOException {
		if (cm == null) {
			cm = this.mount.execute(null);
		}
		try {
			return op.execute(cm);
		} catch (IOException e) {
			// ESHUTDOWN
			if (e.getMessage().equals("Cannot send after transport endpoint shutdown")) {
				cm.unmount();
				cm = this.mount.execute(null);
				return op.execute(cm);
			} else {
				throw e;
			}
		}
	}

	protected <T> T executeWithErrnoException(
			String functionName, Operation<T> op) throws ErrnoException {
		try {
			return execute(op);
		} catch (IOException e) {
			throw new ErrnoException(functionName, cephIOEToOsConstants(e));
		}
	}

	protected <T> T executeWithCursorExtra(Operation<T> op, Cursor c)
			throws FileNotFoundException {
		try {
			return execute(op);
		} catch (FileNotFoundException e) {
			throw e;
		} catch (IOException e) {
			var extra = new Bundle();
			var msg = e.getMessage();
			extra.putString(DocumentsContract.EXTRA_ERROR,
				(msg != null && msg.length() != 0) ? msg : e.getClass().getName());
			c.setExtras(extra);
			return null;
		}
	}

	protected <T> T executeWithUnchecked(Operation<T> op) {
		try {
			return execute(op);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	protected <T> T executeWithUncheckedOrFNF(Operation<T> op)
			throws FileNotFoundException {
		try {
			return execute(op);
		} catch (FileNotFoundException e) {
			throw e;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
