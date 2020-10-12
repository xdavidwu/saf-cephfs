package org.safcephfs;

import android.os.ProxyFileDescriptorCallback;
import android.system.ErrnoException;

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
		cm.fsync(fd, false);
	}

	@Override
	public long onGetSize() throws ErrnoException {
		CephStat cs = new CephStat();
		cm.fstat(fd, cs);
		return cs.size;
	}

	@Override
	public int onRead(long offset, int size, byte[] data)
		throws ErrnoException {
		return (int) cm.read(fd, data, size, offset);
	}

	@Override
	public void onRelease() {
		cm.close(fd);
	}

	@Override
	public int onWrite(long offset, int size, byte[] data)
		throws ErrnoException {
		return (int) cm.write(fd, data, size, offset);
	}
}
