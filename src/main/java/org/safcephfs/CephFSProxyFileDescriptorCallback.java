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

	public CephFSProxyFileDescriptorCallback(CephMount cm, int fd) {
		this.cm = cm;
		this.fd = fd;
	}

	@Override
	public void onFsync() throws ErrnoException {
		CephFSOperations.translateToErrnoException("fsync", () -> {
			cm.fsync(fd, false);
			return null;
		});
	}

	@Override
	public long onGetSize() throws ErrnoException {
		CephStat cs = new CephStat();
		CephFSOperations.translateToErrnoException("fstat", () -> {
			cm.fstat(fd, cs);
			return null;
		});
		return cs.size;
	}

	@Override
	public int onRead(long offset, int size, byte[] data)
		throws ErrnoException {
		return CephFSOperations.translateToErrnoException("read", () -> {
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
		return CephFSOperations.translateToErrnoException("write", () -> {
			return cm.write(fd, data, size, offset);
		}).intValue();
	}
}
