#!/usr/bin/env bash
# Собирает подписанный APK. Запускается на сборочной машине
# (нидерландский сервер), где уже стоят JDK 21, Android SDK и NDK.
#
#   ./build.sh                      — arm64, им пользуется 95% телефонов
#   ABI=universal ./build.sh        — один APK под все архитектуры
#   BRAND=proxyvv ./build.sh        — другое приложение из этого же кода
#
# Ключ подписи живёт вне репозитория: /opt/build/keystore. Потерять его
# нельзя — без него не выпустить обновление поверх установленного.
set -euo pipefail

cd "$(dirname "$0")"
export ANDROID_HOME=${ANDROID_HOME:-/opt/android/sdk}
export NDK_HOME=${NDK_HOME:-$ANDROID_HOME/ndk/30.0.16248370}
KS=${KEYSTORE_DIR:-/opt/build/keystore}
ABI=${ABI:-arm64-v8a}
BRAND=${BRAND:-tv}
export BRAND

[ -f "$KS/aviavp.jks" ] || { echo "нет ключа подписи в $KS"; exit 1; }
PW=$(cat "$KS/password")

./rebrand.sh

# Нативная часть туннеля: единственное, что компилируется из C.
if [ ! -d V2rayNG/app/libs/arm64-v8a ]; then
    echo "Собираю libhevtun…"
    bash compile-hevtun.sh > /tmp/hevtun.log 2>&1 || { tail -20 /tmp/hevtun.log; exit 1; }
    mkdir -p V2rayNG/app/libs && cp -r libs/* V2rayNG/app/libs/
fi

# Ядро xray — готовый релиз AndroidLibXrayLite. Версия записана в
# core.version: полагаться на git-тег подмодуля нельзя, исходники на
# сборочную машину попадают простой синхронизацией, без истории.
if [ ! -f V2rayNG/app/libs/libv2ray.aar ]; then
    TAG=$(tr -d '[:space:]' < core.version)
    echo "Качаю ядро xray $TAG…"
    wget -q -O V2rayNG/app/libs/libv2ray.aar \
        "https://github.com/2dust/AndroidLibXrayLite/releases/download/${TAG}/libv2ray.aar"
fi

cd V2rayNG
echo "sdk.dir=$ANDROID_HOME" > local.properties
chmod +x gradlew

./gradlew --no-daemon -PABI_FILTERS="$ABI" -Pbrand="$BRAND" assembleRelease \
    -Pandroid.injected.signing.store.file="$KS/aviavp.jks" \
    -Pandroid.injected.signing.store.password="$PW" \
    -Pandroid.injected.signing.key.alias=aviavp \
    -Pandroid.injected.signing.key.password="$PW"

echo
echo "Готово ($BRAND):"
ls -la app/build/outputs/apk/release/*.apk
