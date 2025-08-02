# saf-cephfs

Access CephFS from Android Storage Access Framework (SAF), with libcephfs-jni

Currently only for arm64-v8a

## Libraries sources

This project uses a few libraries from Archlinux User Repository (AUR):

- android-aarch64-openssl
- android-aarch64-icu

For exact tested version of those libraries, inspect the container mentioned below.

## Build instructions

The build currently needs libraries from AUR, thus an Archlinux environment is required. The environment used to build this project is packed from `Containerfile` and published as `ghcr.io/xdavidwu/saf-cephfs/build`.

To build with the packed container environment:

```
podman run -v .:/build -v ~/.android:/root/.android ghcr.io/xdavidwu/saf-cephfs/build:latest ./gradlew assembleDebug
```

## Status

Reads and writes works, but some DocumentsProvider features not yet implemented.
