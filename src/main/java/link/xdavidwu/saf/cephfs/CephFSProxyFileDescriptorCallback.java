package link.xdavidwu.saf.cephfs;

import android.os.ProxyFileDescriptorCallback;
import android.system.ErrnoException;
import android.system.OsConstants;

import java.io.IOException;

import com.ceph.fs.CephMount;
import com.ceph.fs.CephStat;

public class CephFSProxyFileDescriptorCallback extends ProxyFileDescriptorCallback {
	private CephFSExecutor executor;
	private CephMount cm;
	private String path;
	private int fd, mode;

	public CephFSProxyFileDescriptorCallback(
			CephFSExecutor executor, CephMount cm, int fd,
			String path, int mode) {
		this.cm = cm;
		this.fd = fd;
		this.executor = executor;
		this.path = path;
		this.mode = mode;
	}

	private <T> CephFSExecutor.Operation<T> reopenIfNeeded(
			CephFSExecutor.Operation<T> op) {
		return cm -> {
			if (cm != this.cm) {
				fd = cm.open(path, mode, 0);
				this.cm = cm;
			};
			return op.execute(cm);
		};
	};

	@Override
	public void onFsync() throws ErrnoException {
		executor.executeWithErrnoException("fsync", cm -> {
			if (cm == this.cm) {
				cm.fsync(fd, false);
			}
			return null;
		});
	}

	@Override
	public long onGetSize() throws ErrnoException {
		CephStat cs = new CephStat();
		executor.executeWithErrnoException("fstat", reopenIfNeeded(cm -> {
			cm.fstat(fd, cs);
			return null;
		}));
		return cs.size;
	}

	@Override
	public int onRead(long offset, int size, byte[] data)
		throws ErrnoException {
		return executor.executeWithErrnoException("read", reopenIfNeeded(cm -> {
			return cm.read(fd, data, size, offset);
		})).intValue();
	}

	@Override
	public void onRelease() {
		cm.close(fd);
	}

	@Override
	public int onWrite(long offset, int size, byte[] data)
		throws ErrnoException {
		return executor.executeWithErrnoException("write", reopenIfNeeded(cm -> {
			return cm.write(fd, data, size, offset);
		})).intValue();
	}
}
