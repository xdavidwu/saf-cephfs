# saf-cephfs

[WIP] Access CephFS from Android Storage Access Framework (SAF), with libcephfs-jni

Currently only for arm64-v8a

## Libraries sources

Native libraries bundled are built from:

* libboost_\*.so: Archlinx User Repository (AUR) package `android-aarch64-boost` (1.76.0-1)
* libcrypto\_1\_1.so: AUR package `android-aarch64-openssl` (1.1.1.i-2)
* libc++\_shared.so: copied from NDK r22.b
* libcrc32.so, libcephfs.so, libceph-common.so, libcephfs\_jni.so, libfmt.so: built from Ceph Pacific, see [COMPILING-CEPH.md](COMPILING-CEPH.md) for how I built it

Java libraries com.ceph.\* is copied from Ceph 15.2.5 source code.

## Current features

* Traverse the tree under specified path

## Notes

This is a WIP, and the code is dirty and full of debugging lines.
Many things still don't work.

### What's tested and worked so far

* Listing files, changing directories
