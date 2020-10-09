# Compile instruction for Ceph libraries used in this project

Ceph libraries in this project is built by:

Install android-ndk android-cmake android-aarch64-boost android-aarch64-openssl AUR packages on Archlinux.

Under Ceph 15.2.5 source with ceph.patch applied:

```sh
mkdir build && cd build
android-aarch64-cmake -DWITH_MANPAGE=OFF -DWITH_RDMA=OFF -DWITH_LEVELDB=OFF -DWITH_KVS=OFF -DWITH_FUSE=OFF -DWITH_BLUESTORE=OFF -DWITH_XFS=OFF -DWITH_RBD=OFF -DWITH_OPENLDAP=OFF -DWITH_RADOSGW=OFF -DWITH_LZ4=OFF -DWITH_KRBD=OFF -DWITH_LTTNG=OFF -DWITH_MGR=OFF -DWITH_BABELTRACE=OFF -DWITH_CEPHFS=OFF -DWITH_LIBRADOSSTRIPER=OFF -DWITH_TESTS=OFF -DWITH_REENTRANT_STRSIGNAL=ON -DWITH_SYSTEMD=OFF -DWITH_MGR_DASHBOARD_FRONTEND=OFF -DWITH_RADOSGW_KAFKA_ENDPOINT=OFF -D WITH_RADOSGW_AMQP_ENDPOINT=OFF -DWITH_RADOSGW_BEAST_OPENSSL=OFF -DWITH_RADOSGW_BEAST_FRONTEND=OFF -DDEBUG_GATHER=OFF -DWITH_CEPHFS_JAVA=ON -DOPENSSL_INCLUDE_DIR:FILEPATH=/opt/android-libs/aarch64/include/ -DOPENSSL_CRYPTO_LIBRARY=/opt/android-libs/aarch64/lib/libcrypto.so -DWITH_SYSTEM_BOOST=ON -DBoost_INCLUDE_DIR=/opt/android-libs/aarch64/include/ -DBoost_LIBRARY_DIR=/opt/android-libs/aarch64/lib/ -DWITH_BOOST_CONTEXT=OFF ..
make java
```

ceph.patch contains some hacks to avoid dependency of unused targets, to use boost::filesystem instead of std::filesystem as needed library is not in NDK yet, and to fix build with Android bionic libc.
