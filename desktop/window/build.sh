#!/bin/sh
# Собирает окно снапа (vv-window) под Ubuntu 22.04 — в docker, чтобы
# glibc и WebKitGTK были ровно те, что у core22 и gnome-42-2204, на
# которых снап работает. Собранное на свежей системе требовало бы
# glibc новее, чем есть в core22, и не запустилось бы.
#
#   window/build.sh ВЫХОДНОЙ_ФАЙЛ
set -eu
OUT=$(realpath -m "$1")
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$(dirname "$OUT")"

docker run --rm -v "$HERE:/src:ro" -v "$(dirname "$OUT"):/out" ubuntu:22.04 sh -euc "
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq >/dev/null
    apt-get install -y -qq --no-install-recommends gcc libc6-dev pkg-config libwebkit2gtk-4.1-dev >/dev/null
    gcc -O2 -Wall -o /out/$(basename "$OUT") /src/vv-window.c \$(pkg-config --cflags --libs webkit2gtk-4.1)
    chown $(id -u):$(id -g) /out/$(basename "$OUT")
"
