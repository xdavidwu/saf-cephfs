package org.safcephfs;

import android.database.Cursor;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.system.ErrnoException;
import android.system.OsConstants;

import java.io.IOException;
import java.io.UncheckedIOException;

public class CephFSOperations {
	protected interface Operation<T> {
		T execute() throws IOException;
	}

	/*
	 * libcephfs_jni throws IOException with message from strerror()
	 * Bionic strerror:
	 * https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/private/bionic_errdefs.h
	 * sed 's|__BIONIC_ERRDEF(\([^,]*\), \([^)]*\).*|case \2 -> OsConstants.\1;|'
	 * and delete where there are no matching OsConstants
	 */
	private static int cephIOEToOsConstants(IOException e) {
		return switch (e.getMessage()) {
		case "Operation not permitted" -> OsConstants.EPERM;
		case "No such file or directory" -> OsConstants.ENOENT;
		case "No such process" -> OsConstants.ESRCH;
		case "Interrupted system call" -> OsConstants.EINTR;
		case "I/O error" -> OsConstants.EIO;
		case "No such device or address" -> OsConstants.ENXIO;
		case "Argument list too long" -> OsConstants.E2BIG;
		case "Exec format error" -> OsConstants.ENOEXEC;
		case "Bad file descriptor" -> OsConstants.EBADF;
		case "No child processes" -> OsConstants.ECHILD;
		case "Try again" -> OsConstants.EAGAIN;
		case "Out of memory" -> OsConstants.ENOMEM;
		case "Permission denied" -> OsConstants.EACCES;
		case "Bad address" -> OsConstants.EFAULT;
		case "Device or resource busy" -> OsConstants.EBUSY;
		case "File exists" -> OsConstants.EEXIST;
		case "Cross-device link" -> OsConstants.EXDEV;
		case "No such device" -> OsConstants.ENODEV;
		case "Not a directory" -> OsConstants.ENOTDIR;
		case "Is a directory" -> OsConstants.EISDIR;
		case "Invalid argument" -> OsConstants.EINVAL;
		case "File table overflow" -> OsConstants.ENFILE;
		case "Too many open files" -> OsConstants.EMFILE;
		case "Inappropriate ioctl for device" -> OsConstants.ENOTTY;
		case "Text file busy" -> OsConstants.ETXTBSY;
		case "File too large" -> OsConstants.EFBIG;
		case "No space left on device" -> OsConstants.ENOSPC;
		case "Illegal seek" -> OsConstants.ESPIPE;
		case "Read-only file system" -> OsConstants.EROFS;
		case "Too many links" -> OsConstants.EMLINK;
		case "Broken pipe" -> OsConstants.EPIPE;
		case "Math argument out of domain of func" -> OsConstants.EDOM;
		case "Math result not representable" -> OsConstants.ERANGE;
		case "Resource deadlock would occur" -> OsConstants.EDEADLK;
		case "File name too long" -> OsConstants.ENAMETOOLONG;
		case "No record locks available" -> OsConstants.ENOLCK;
		case "Function not implemented" -> OsConstants.ENOSYS;
		case "Directory not empty" -> OsConstants.ENOTEMPTY;
		case "Too many symbolic links encountered" -> OsConstants.ELOOP;
		case "No message of desired type" -> OsConstants.ENOMSG;
		case "Identifier removed" -> OsConstants.EIDRM;
		case "Device not a stream" -> OsConstants.ENOSTR;
		case "No data available" -> OsConstants.ENODATA;
		case "Timer expired" -> OsConstants.ETIME;
		case "Out of streams resources" -> OsConstants.ENOSR;
		case "Machine is not on the network" -> OsConstants.ENONET;
		case "Link has been severed" -> OsConstants.ENOLINK;
		case "Protocol error" -> OsConstants.EPROTO;
		case "Multihop attempted" -> OsConstants.EMULTIHOP;
		case "Not a data message" -> OsConstants.EBADMSG;
		case "Value too large for defined data type" -> OsConstants.EOVERFLOW;
		case "Illegal byte sequence" -> OsConstants.EILSEQ;
		case "Socket operation on non-socket" -> OsConstants.ENOTSOCK;
		case "Destination address required" -> OsConstants.EDESTADDRREQ;
		case "Message too long" -> OsConstants.EMSGSIZE;
		case "Protocol wrong type for socket" -> OsConstants.EPROTOTYPE;
		case "Protocol not available" -> OsConstants.ENOPROTOOPT;
		case "Protocol not supported" -> OsConstants.EPROTONOSUPPORT;
		case "Operation not supported on transport endpoint" -> OsConstants.EOPNOTSUPP;
		case "Address family not supported by protocol" -> OsConstants.EAFNOSUPPORT;
		case "Address already in use" -> OsConstants.EADDRINUSE;
		case "Cannot assign requested address" -> OsConstants.EADDRNOTAVAIL;
		case "Network is down" -> OsConstants.ENETDOWN;
		case "Network is unreachable" -> OsConstants.ENETUNREACH;
		case "Network dropped connection because of reset" -> OsConstants.ENETRESET;
		case "Software caused connection abort" -> OsConstants.ECONNABORTED;
		case "Connection reset by peer" -> OsConstants.ECONNRESET;
		case "No buffer space available" -> OsConstants.ENOBUFS;
		case "Transport endpoint is already connected" -> OsConstants.EISCONN;
		case "Transport endpoint is not connected" -> OsConstants.ENOTCONN;
		case "Connection timed out" -> OsConstants.ETIMEDOUT;
		case "Connection refused" -> OsConstants.ECONNREFUSED;
		case "No route to host" -> OsConstants.EHOSTUNREACH;
		case "Operation already in progress" -> OsConstants.EALREADY;
		case "Operation now in progress" -> OsConstants.EINPROGRESS;
		case "Stale NFS file handle" -> OsConstants.ESTALE;
		case "Quota exceeded" -> OsConstants.EDQUOT;
		case "Operation Canceled" -> OsConstants.ECANCELED;
		default -> OsConstants.EIO;
		};
	}

	protected static <T> T translateToErrnoException(
			String functionName, Operation<T> op) throws ErrnoException {
		try {
			return op.execute();
		} catch (IOException e) {
			throw new ErrnoException(functionName, cephIOEToOsConstants(e));
		}
	}

	protected static <T> T translateToCursorExtra(Operation<T> op, Cursor c) {
		try {
			return op.execute();
		} catch (IOException e) {
			var extra = new Bundle();
			extra.putString(DocumentsContract.EXTRA_ERROR, e.getMessage());
			c.setExtras(extra);
			return null;
		}
	}

	protected static <T> T translateToUnchecked(Operation<T> op) {
		try {
			return op.execute();
		// TODO preserve IOE subclasses
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	protected static <T> Operation<T> retryOnESHUTDOWN(
			Operation<Object> setup, Operation<T> op) {
		return () -> {
			try {
				return op.execute();
			} catch (IOException e) {
				if (e.getMessage().equals("Cannot send after transport endpoint shutdown")) {
					setup.execute();
					return op.execute();
				} else {
					throw e;
				}
			}
		};
	}
}
