FROM archlinux

RUN printf '[aurbuild]\nServer = https://aurbuild.xdavidwu.link\n' >> /etc/pacman.conf
RUN curl 'https://keyserver.ubuntu.com/pks/lookup?op=get&search=0xf73f137d4573defaa097dbf09544cff6b08a3fd3' | pacman-key -a - && pacman-key --init && pacman-key --lsign-key f73f137d4573defaa097dbf09544cff6b08a3fd3 && pacman -Syu --noconfirm && pacman -S --noconfirm jdk17-openjdk android-sdk-cmdline-tools-latest android-platform-33 android-sdk-build-tools-33 && mkdir /build

WORKDIR /build

# XXX: use /etc/profile.d/*.sh instead?
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk