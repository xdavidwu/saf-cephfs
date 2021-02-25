package org.safcephfs;

import android.os.ProxyFileDescriptorCallback;
import android.system.ErrnoException;
import android.system.OsConstants;

import java.io.IOException;

import com.ceph.fs.CephMount;
import com.ceph.fs.CephStat;

public class CephFSProxyFileDescriptorCallback extends ProxyFileDescriptorCallback {
	private CephMount cm;
	private int fd;

	/*
	 * libcephfs_jni throws IOException with message from strerror()
	 * Bionic strerror:
	 * https://android.googlesource.com/platform/bionic/+/refs/heads/master/libc/bionic/strerror.cpp
	 * lazy solution: copy error strings line,
	 * `sed -E 's/    \[([A-Z0-9]+)\] = ([^,]+),/\t\tcase \2:\n\t\t\treturn OsConstants.\1;/'`,
	 * and delete where there are no matching OsConstants
	 */
	private static int cephIOEToOsConstants(IOException e) {
		switch (e.getMessage()) {
		case "Operation not permitted":
			return OsConstants.EPERM;
		case "No such file or directory":
			return OsConstants.ENOENT;
		case "No such process":
			return OsConstants.ESRCH;
		case "Interrupted system call":
			return OsConstants.EINTR;
		case "I/O error":
			return OsConstants.EIO;
		case "No such device or address":
			return OsConstants.ENXIO;
		case "Argument list too long":
			return OsConstants.E2BIG;
		case "Exec format error":
			return OsConstants.ENOEXEC;
		case "Bad file descriptor":
			return OsConstants.EBADF;
		case "No child processes":
			return OsConstants.ECHILD;
		case "Try again":
			return OsConstants.EAGAIN;
		case "Out of memory":
			return OsConstants.ENOMEM;
		case "Permission denied":
			return OsConstants.EACCES;
		case "Bad address":
			return OsConstants.EFAULT;
		case "Device or resource busy":
			return OsConstants.EBUSY;
		case "File exists":
			return OsConstants.EEXIST;
		case "Cross-device link":
			return OsConstants.EXDEV;
		case "No such device":
			return OsConstants.ENODEV;
		case "Not a directory":
			return OsConstants.ENOTDIR;
		case "Is a directory":
			return OsConstants.EISDIR;
		case "Invalid argument":
			return OsConstants.EINVAL;
		case "File table overflow":
			return OsConstants.ENFILE;
		case "Too many open files":
			return OsConstants.EMFILE;
		case "Inappropriate ioctl for device":
			return OsConstants.ENOTTY;
		case "Text file busy":
			return OsConstants.ETXTBSY;
		case "File too large":
			return OsConstants.EFBIG;
		case "No space left on device":
			return OsConstants.ENOSPC;
		case "Illegal seek":
			return OsConstants.ESPIPE;
		case "Read-only file system":
			return OsConstants.EROFS;
		case "Too many links":
			return OsConstants.EMLINK;
		case "Broken pipe":
			return OsConstants.EPIPE;
		case "Math argument out of domain of func":
			return OsConstants.EDOM;
		case "Math result not representable":
			return OsConstants.ERANGE;
		case "Resource deadlock would occur":
			return OsConstants.EDEADLK;
		case "File name too long":
			return OsConstants.ENAMETOOLONG;
		case "No record locks available":
			return OsConstants.ENOLCK;
		case "Function not implemented":
			return OsConstants.ENOSYS;
		case "Directory not empty":
			return OsConstants.ENOTEMPTY;
		case "Too many symbolic links encountered":
			return OsConstants.ELOOP;
		case "No message of desired type":
			return OsConstants.ENOMSG;
		case "Identifier removed":
			return OsConstants.EIDRM;
		case "Device not a stream":
			return OsConstants.ENOSTR;
		case "No data available":
			return OsConstants.ENODATA;
		case "Timer expired":
			return OsConstants.ETIME;
		case "Out of streams resources":
			return OsConstants.ENOSR;
		case "Link has been severed":
			return OsConstants.ENOLINK;
		case "Protocol error":
			return OsConstants.EPROTO;
		case "Multihop attempted":
			return OsConstants.EMULTIHOP;
		case "Not a data message":
			return OsConstants.EBADMSG;
		case "Value too large for defined data type":
			return OsConstants.EOVERFLOW;
		case "Illegal byte sequence":
			return OsConstants.EILSEQ;
		case "Socket operation on non-socket":
			return OsConstants.ENOTSOCK;
		case "Destination address required":
			return OsConstants.EDESTADDRREQ;
		case "Message too long":
			return OsConstants.EMSGSIZE;
		case "Protocol wrong type for socket":
			return OsConstants.EPROTOTYPE;
		case "Protocol not available":
			return OsConstants.ENOPROTOOPT;
		case "Protocol not supported":
			return OsConstants.EPROTONOSUPPORT;
		case "Operation not supported on transport endpoint":
			return OsConstants.EOPNOTSUPP;
		case "Address family not supported by protocol":
			return OsConstants.EAFNOSUPPORT;
		case "Address already in use":
			return OsConstants.EADDRINUSE;
		case "Cannot assign requested address":
			return OsConstants.EADDRNOTAVAIL;
		case "Network is down":
			return OsConstants.ENETDOWN;
		case "Network is unreachable":
			return OsConstants.ENETUNREACH;
		case "Network dropped connection because of reset":
			return OsConstants.ENETRESET;
		case "Software caused connection abort":
			return OsConstants.ECONNABORTED;
		case "Connection reset by peer":
			return OsConstants.ECONNRESET;
		case "No buffer space available":
			return OsConstants.ENOBUFS;
		case "Transport endpoint is already connected":
			return OsConstants.EISCONN;
		case "Transport endpoint is not connected":
			return OsConstants.ENOTCONN;
		case "Connection timed out":
			return OsConstants.ETIMEDOUT;
		case "Connection refused":
			return OsConstants.ECONNREFUSED;
		case "No route to host":
			return OsConstants.EHOSTUNREACH;
		case "Operation already in progress":
			return OsConstants.EALREADY;
		case "Operation now in progress":
			return OsConstants.EINPROGRESS;
		case "Stale NFS file handle":
			return OsConstants.ESTALE;
		case "Quota exceeded":
			return OsConstants.EDQUOT;
		case "Operation Canceled":
			return OsConstants.ECANCELED;
		default:
			return OsConstants.EIO;
		}
	}

	private interface CephFDOperation<T> {
		T execute() throws IOException;
	}

	private <T> T doCephFDOperation(String funcName, CephFDOperation<T> op)
			throws ErrnoException {
		try {
			return op.execute();
		} catch (IOException e) {
			throw new ErrnoException(funcName, cephIOEToOsConstants(e));
		}
	}

	public CephFSProxyFileDescriptorCallback(CephMount cm, int fd) {
		this.cm = cm;
		this.fd = fd;
	}

	@Override
	public void onFsync() throws ErrnoException {
		doCephFDOperation("fsync", () -> {
			cm.fsync(fd, false);
			return null;
		});
	}

	@Override
	public long onGetSize() throws ErrnoException {
		CephStat cs = new CephStat();
		doCephFDOperation("fstat", () -> {
			cm.fstat(fd, cs);
			return null;
		});
		return cs.size;
	}

	@Override
	public int onRead(long offset, int size, byte[] data)
		throws ErrnoException {
		return doCephFDOperation("read", () -> {
			return cm.read(fd, data, size, offset);
		}).intValue();
	}

	@Override
	public void onRelease() {
		cm.close(fd);
	}

	@Override
	public int onWrite(long offset, int size, byte[] data)
		throws ErrnoException {
		return doCephFDOperation("write", () -> {
			return cm.write(fd, data, size, offset);
		}).intValue();
	}
}
