#!/bin/sh
# Собирает окно снапа (vv-window) под Ubuntu 22.04 — в docker, чтобы
# glibc и WebKitGTK были ровно те, что у core22 и gnome-42-2204, на
# которых снап работает. Собранное на свежей системе требовало бы
# glibc новее, чем есть в core22, и не запустилось бы.
#
#   window/build.sh ВЫХОДНОЙ_ФАЙЛ
#
# Сборочная среда — образ vv-window-build из Dockerfile рядом: первый раз
# он собирается долго, дальше берётся из кеша.
set -eu
OUT=$(realpath -m "$1")
HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$(dirname "$OUT")"

docker build -q -t vv-window-build "$HERE" > /dev/null

docker run --rm -u "$(id -u):$(id -g)" -v "$HERE:/src:ro" -v "$(dirname "$OUT"):/out" vv-window-build \
    sh -euc 'gcc -O2 -Wall -o "/out/$1" /src/vv-window.c $(pkg-config --cflags --libs webkit2gtk-4.1)' \
    sh "$(basename "$OUT")"
