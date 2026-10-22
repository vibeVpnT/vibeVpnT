# Сборочная среда окна снапа: Ubuntu 22.04 (на ней основан core22) и
# WebKitGTK для разработки. Образ собирается один раз и дальше берётся
# из кеша docker — пакетов тут на пару сотен мегабайт, и тянуть их на
# каждую сборку окна незачем.
FROM ubuntu:22.04

# Зеркало Яндекса: archive.ubuntu.com отсюда отдаёт по паре сотен
# килобайт в секунду, и одна установка шла четверть часа.
ARG MIRROR=http://mirror.yandex.ru/ubuntu
RUN sed -i "s|http://archive.ubuntu.com/ubuntu|$MIRROR|; s|http://security.ubuntu.com/ubuntu|$MIRROR|" /etc/apt/sources.list \
 && apt-get update -qq \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends \
        gcc libc6-dev pkg-config libwebkit2gtk-4.1-dev \
 && rm -rf /var/lib/apt/lists/*
