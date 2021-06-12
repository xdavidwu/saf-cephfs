# Compile instruction for Ceph libraries used in this project

Ceph libraries in this project are built by:

Install android-ndk android-cmake android-aarch64-boost android-aarch64-openssl AUR packages on Archlinux.

Using https://github.com/xdavidwu/ceph, branch saf-cephfs-pacific. This branch contains (hacky) patches on top of upstream pacific to make it build with ndk.

```sh
mkdir build && cd build
android-aarch64-cmake -DWITH_MANPAGE=OFF -DWITH_RDMA=OFF -DWITH_LEVELDB=OFF -DWITH_KVS=OFF -DWITH_FUSE=OFF -DWITH_BLUESTORE=OFF -DWITH_XFS=OFF -DWITH_RBD=OFF -DWITH_OPENLDAP=OFF -DWITH_RADOSGW=OFF -DWITH_LZ4=OFF -DWITH_KRBD=OFF -DWITH_LTTNG=OFF -DWITH_MGR=OFF -DWITH_BABELTRACE=OFF -DWITH_CEPHFS=OFF -DWITH_LIBRADOSSTRIPER=OFF -DWITH_TESTS=OFF -DWITH_REENTRANT_STRSIGNAL=ON -DWITH_SYSTEMD=OFF -DWITH_MGR_DASHBOARD_FRONTEND=OFF -DWITH_RADOSGW_KAFKA_ENDPOINT=OFF -D WITH_RADOSGW_AMQP_ENDPOINT=OFF -DWITH_RADOSGW_BEAST_OPENSSL=OFF -DWITH_RADOSGW_BEAST_FRONTEND=OFF -DDEBUG_GATHER=OFF -DWITH_CEPHFS_JAVA=ON -DOPENSSL_INCLUDE_DIR:FILEPATH=/opt/android-libs/aarch64/include/ -DOPENSSL_CRYPTO_LIBRARY=/opt/android-libs/aarch64/lib/libcrypto.so -DWITH_SYSTEM_BOOST=ON -DBoost_INCLUDE_DIR=/opt/android-libs/aarch64/include/ -DBoost_LIBRARY_DIR=/opt/android-libs/aarch64/lib/ -DWITH_LIBCEPHSQLITE=OFF ..
make java
```
