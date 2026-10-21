#!/usr/bin/env bash
# Сборка настольной версии.
#
#   ./pack.sh        — всё, что раздаём: vv-windows.zip и vv.deb
#   ./pack.sh win    — только Windows
#   ./pack.sh deb    — только Ubuntu
#   ./pack.sh snap   — снап для магазина (нужны snapcraft и lxd)
#
# Раздаются они по-разному, и это не прихоть. В Windows человек скачивает
# один файл и запускает его: ядро и справочники лежат внутри exe и
# попадают на диск один раз, при первом запуске. В Ubuntu есть пакеты —
# там ядро просто лежит рядом, в /opt/vv, и программа ничего никуда не
# распаковывает.
set -euo pipefail

cd "$(dirname "$0")"

# На сборочной машине Go и его кеши живут в /opt/build; на рабочей — где
# обычно, и подменять их не нужно.
if [ -d /opt/build/go/bin ]; then
    export PATH=/opt/build/go/bin:$PATH
    export GOPATH=${GOPATH:-/opt/build/gopath}
    export GOCACHE=${GOCACHE:-/opt/build/gocache}
fi

VERSION=$(sed -n 's/.*appVersion *= *"\(.*\)"/\1/p' brand.go)
OUT=dist
WHAT=${1:-all}

XRAY_TAG=v26.9.30
TUN2SOCKS_TAG=v2.7.0
WINTUN_TAG=0.14.1
AAR=${AAR:-/opt/build/aviavp/V2rayNG/app/libs/libv2ray.aar}

mkdir -p "$OUT" assets

# --- Ядро --------------------------------------------------------------------

fetch_core() {   # fetch_core <windows|linux> <имя файла в assets>
    local os=$1 name=$2 zip
    if [ -f "assets/$name" ]; then return 0; fi

    echo "Качаю ядро $XRAY_TAG для $os"
    zip=assets/xray-$os.zip
    case "$os" in
        windows) curl -sL --max-time 300 -o "$zip" \
            "https://github.com/XTLS/Xray-core/releases/download/$XRAY_TAG/Xray-windows-64.zip"
            unzip -o -q "$zip" xray.exe -d assets ;;
        linux)   curl -sL --max-time 300 -o "$zip" \
            "https://github.com/XTLS/Xray-core/releases/download/$XRAY_TAG/Xray-linux-64.zip"
            unzip -o -q "$zip" xray -d assets
            chmod +x assets/xray ;;
    esac
    rm -f "$zip"
}

# --- Туннель для режима VPN --------------------------------------------------
#
# tun2socks берёт пакеты из сетевого интерфейса и отдаёт их в наш SOCKS,
# то есть в ядро. Бинарник из релиза ни от чего в системе не зависит —
# собирать нечего.
#
# Почему не hev-socks5-tunnel, который работает внутри мобильного
# клиента: его сборки из релиза молча падают с кодом 254 ещё до создания
# интерфейса, и проверить их в чистой системе не удалось.

# То же для Windows: сам туннель и драйвер сетевого адаптера Wintun —
# один файл dll, ставить его отдельно не нужно, он подхватывается рядом
# с программой.
fetch_tunnel_win() {
    if [ -f assets/tun2socks.exe ] && [ -f assets/wintun.dll ]; then return 0; fi

    echo "Качаю туннель $TUN2SOCKS_TAG и Wintun $WINTUN_TAG для Windows"
    curl -sL --max-time 300 -o assets/t2s-win.zip \
        "https://github.com/xjasonlyu/tun2socks/releases/download/$TUN2SOCKS_TAG/tun2socks-windows-amd64.zip"
    unzip -o -q -j assets/t2s-win.zip tun2socks-windows-amd64.exe -d assets
    mv assets/tun2socks-windows-amd64.exe assets/tun2socks.exe

    curl -sL --max-time 300 -o assets/wintun.zip \
        "https://www.wintun.net/builds/wintun-$WINTUN_TAG.zip"
    unzip -o -q -j assets/wintun.zip "wintun/bin/amd64/wintun.dll" -d assets

    rm -f assets/t2s-win.zip assets/wintun.zip
}

fetch_tunnel() {
    if [ -f assets/tun2socks ]; then return 0; fi

    echo "Качаю туннель $TUN2SOCKS_TAG"
    curl -sL --max-time 300 -o assets/tun2socks.zip \
        "https://github.com/xjasonlyu/tun2socks/releases/download/$TUN2SOCKS_TAG/tun2socks-linux-amd64.zip"
    unzip -o -q -j assets/tun2socks.zip tun2socks-linux-amd64 -d assets
    mv assets/tun2socks-linux-amd64 assets/tun2socks
    chmod +x assets/tun2socks
    rm -f assets/tun2socks.zip
}

# --- Справочники -------------------------------------------------------------
#
# Берём их из aar мобильного клиента, а не из релиза Xray: в официальном
# наборе нет российских отраслевых категорий, на которые опираются наши
# правила, а ссылка на отсутствующую категорию роняет ядро целиком.
# Дальше обрезаем до того, чем пользуемся: 27 мегабайт превращаются в
# четыреста килобайт.

fetch_geo() {
    if [ -f assets/geosite-vv.dat ] && [ -f assets/geoip-vv.dat ]; then return 0; fi

    if [ ! -f assets/geosite.dat ]; then
        if [ ! -f "$AAR" ]; then
            echo "нет $AAR — справочники брать неоткуда" >&2
            exit 1
        fi
        unzip -o -q -j "$AAR" assets/geosite.dat assets/geoip.dat -d assets
    fi

    # Список категорий — из тех же правил, что раздаёт бэкенд: так он не
    # разъедется с тем, что придёт программе по сети.
    local routing=""
    for path in ../../sahrabot/apps/routing/default.json /opt/sahrabot/apps/routing/default.json; do
        if [ -f "$path" ]; then routing=$path; break; fi
    done
    python3 trim_geo.py assets $routing
}

# --- Windows -----------------------------------------------------------------

# --- Установщик для Windows --------------------------------------------------
#
# Кладётся в тот же архив рядом с программой: кому нужны ярлыки и запись
# в «Установку и удаление программ» — запускает setup.exe, остальным
# хватает vv.exe из папки.
#
# Собирает makensis. Ставить его на машину не нужно: берём из образа.
# Если ни makensis, ни docker не нашлись — архив соберётся без
# установщика, и это сказано вслух, а не молча.

build_setup() {   # build_setup <папка с файлами>
    local folder=$1 nsis=""

    if command -v makensis >/dev/null 2>&1; then
        nsis="makensis"
    elif command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
        nsis="docker"
    else
        echo "Ни makensis, ни docker не нашлись — архив будет без установщика" >&2
        return 0
    fi

    echo "Собираю установщик"

    # Пути makensis считает от самого скрипта, а не от текущей папки,
    # поэтому все они — от корня проекта.
    local args="-DNAME=$TITLE -DSLUG=$BRAND -DVERSION=$VERSION"
    args="$args -DSRC=$folder -DICON=assets/vv.ico -DOUTFILE=$folder/setup.exe"

    if [ "$nsis" = docker ]; then
        docker run --rm -v "$PWD:/work" -w /work debian:12 sh -c "
            apt-get update -qq >/dev/null 2>&1
            apt-get install -y -qq nsis >/dev/null 2>&1
            makensis $args installer.nsi" | tail -3
    else
        makensis $args installer.nsi | tail -3
    fi

    [ -f "$folder/setup.exe" ] || echo "установщик не собрался" >&2
}

build_win() {
    fetch_core windows xray.exe
    fetch_tunnel_win
    fetch_geo

    echo "Собираю $BRAND.exe $VERSION"
    if [ ! -f rsrc_windows_amd64.syso ]; then
        go run github.com/akavel/rsrc@latest -ico assets/vv.ico -arch amd64 -o rsrc_windows_amd64.syso
    fi

    local folder=$OUT/$BRAND
    rm -rf "$folder"
    mkdir -p "$folder"

    GOOS=windows GOARCH=amd64 go build -trimpath -ldflags "-s -w -H windowsgui" -o "$folder/$BRAND.exe" .

    # Всё рядом, ничего не прячем внутрь. Так браузер не удаляет
    # скачанное, а антивирус не видит, как программа достаёт из себя
    # второй исполняемый файл и запускает его.
    cp assets/xray.exe          "$folder/xray.exe"
    cp assets/tun2socks.exe     "$folder/tun2socks.exe"
    cp assets/wintun.dll        "$folder/wintun.dll"
    cp assets/geosite-vv.dat    "$folder/geosite.dat"
    cp assets/geoip-vv.dat      "$folder/geoip.dat"

    # Читается в блокноте, поэтому переводы строк windows-овские.
    sed 's/$/\r/' > "$folder/Как запустить.txt" <<EOF
$TITLE $VERSION — настольная версия

Распакуйте папку целиком. Дальше на выбор:

  setup.exe — установить как обычную программу: файлы уедут
              в Program Files, появятся ярлыки на рабочем столе
              и в меню «Пуск», удаляется через «Установку и
              удаление программ». Спросит права администратора.

  $BRAND.exe    — запустить прямо из папки, ничего не устанавливая.

Файлы рядом — это ядро, туннель и справочники; поодиночке
программа не работает.

Windows покажет предупреждение о неизвестном издателе:
программа не подписана. «Подробнее» → «Выполнить в любом случае».

Обновление: закройте программу (значок в трее → «Выйти») и
запустите setup.exe из нового архива — он поставит поверх.
Если работаете из папки, просто распакуйте новый архив.

Режим VPN включается в настройках. Он просит права
администратора — без них сетевой адаптер не создать.
EOF

    build_setup "$folder"

    rm -f "$OUT/$BRAND-windows.zip"
    (cd "$OUT" && zip -q -r "$BRAND-windows.zip" "$BRAND")
    rm -rf "$folder"
}

# --- Ubuntu ------------------------------------------------------------------
#
# Обычный deb: программа и ядро в /opt/vv, ярлык в меню, значок в теме,
# ссылка в /usr/bin. Ставится `sudo apt install ./vv.deb`, снимается
# `sudo apt remove vv` — ничего придумывать человеку не приходится.

build_deb() {
    fetch_core linux xray
    fetch_tunnel
    fetch_geo

    echo "Собираю vv $VERSION для Ubuntu"
    # CGO_ENABLED=0 — чтобы программа ни от чего не зависела.
    #
    # Иначе Go собирает её с библиотекой C той машины, на которой
    # собирали, и на системе постарше она просто не запускается: «нет
    # версии GLIBC_2.42». Собирают у нас на свежей Ubuntu, а ставят
    # люди на какой угодно — и в снапе это тем более заметно, потому
    # что магазином пользуются все подряд.
    CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o "$OUT/vv" .

    local root=$OUT/deb
    rm -rf "$root"
    install -d "$root/opt/$BRAND" "$root/usr/bin" "$root/DEBIAN" \
               "$root/usr/share/applications" \
               "$root/lib/systemd/system" \
               "$root/usr/share/icons/hicolor/512x512/apps"

    install -m 755 "$OUT/vv"      "$root/opt/$BRAND/$BRAND"
    install -m 755 assets/xray    "$root/opt/$BRAND/xray"
    install -m 755 assets/tun2socks "$root/opt/$BRAND/tun2socks"
    install -m 644 assets/geosite-vv.dat "$root/opt/$BRAND/geosite.dat"
    install -m 644 assets/geoip-vv.dat   "$root/opt/$BRAND/geoip.dat"
    install -m 644 assets/icon-src.png   "$root/usr/share/icons/hicolor/512x512/apps/$BRAND.png"
    ln -sf "/opt/$BRAND/$BRAND" "$root/usr/bin/$BRAND"

    # StartupWMClass — чтобы окно браузера, открытое в режиме приложения,
    # показывалось в панели нашим значком, а не чужим.
    cat > "$root/usr/share/applications/$BRAND.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=$BRAND
GenericName=VPN
Comment=Подключение через свой сервер
Exec=/opt/$BRAND/$BRAND
Icon=$BRAND
Terminal=false
Categories=Network;
Keywords=vpn;proxy;xray;
StartupWMClass=$BRAND
Actions=Quit;

[Desktop Action Quit]
Name=Выйти
Exec=/opt/$BRAND/$BRAND --quit
EOF

    # Служба режима VPN. Ставится, но не включается: поднимает её окно
    # по тумблеру, и тогда же рабочий стол спрашивает пароль
    # администратора. Сама по себе, при загрузке машины, она не нужна —
    # без ядра ей всё равно нечего делать, и она сразу выходит.
    cat > "$root/lib/systemd/system/$BRAND-tun.service" <<EOF
[Unit]
Description=$BRAND — режим VPN (туннель)
After=network.target

[Service]
Type=simple
ExecStart=/opt/$BRAND/$BRAND --tun-run
Restart=no
EOF

    # Ярлык и значок появляются в меню и сами, по следящим за каталогом
    # службам, но не мгновенно и не во всех окружениях. Обновить кеши
    # явно стоит одной строки, а сомнений «я поставил, а ничего нет»
    # снимает целый класс.
    for stage in postinst postrm; do
        cat > "$root/DEBIAN/$stage" <<'EOF'
#!/bin/sh
set -e
update-desktop-database -q /usr/share/applications 2>/dev/null || true
gtk-update-icon-cache -q -f /usr/share/icons/hicolor 2>/dev/null || true
systemctl daemon-reload 2>/dev/null || true
EOF
        chmod 755 "$root/DEBIAN/$stage"
    done

    # Право ставить метку на свои пакеты. Ядро работает от обычного
    # пользователя, а без метки в режиме VPN его собственное соединение
    # с сервером уходит в туннель, который этим соединением и живёт, —
    # и не работает вообще ничего. Это не право «делать что угодно с
    # сетью»: capability висит на одном файле ядра, а не на программе.
    cat >> "$root/DEBIAN/postinst" <<EOF
setcap cap_net_admin+ep /opt/$BRAND/xray 2>/dev/null \\
    || echo "Не удалось выдать ядру право на метку пакетов — режим VPN будет недоступен (нужен пакет libcap2-bin)."
EOF

    # Перед удалением гасим туннель: иначе интерфейс и маршруты
    # останутся, а программы, которая умеет их снять, на машине уже не
    # будет — и человек останется без интернета.
    cat > "$root/DEBIAN/prerm" <<EOF
#!/bin/sh
set -e
systemctl stop $BRAND-tun.service 2>/dev/null || true
EOF
    chmod 755 "$root/DEBIAN/prerm"

    # Старую копию просим закрыться: файл мы подменили, а работающая
    # программа осталась прежней — со своими кнопками и своими ошибками.
    # Человек этого не видит и уверен, что поставил новую. Просьба идёт
    # по тому же порту, что и у `vv --quit`, поэтому прокси снимается
    # по-людски, а не остаётся висеть.
    cat >> "$root/DEBIAN/postinst" <<EOF
if [ -x /opt/$BRAND/$BRAND ] && /opt/$BRAND/$BRAND --quit 2>/dev/null | grep -q остановлен; then
    echo "Старая копия $BRAND остановлена — запустите её заново."
fi
EOF

    cat > "$root/DEBIAN/control" <<EOF
Package: $BRAND
Version: $VERSION
Section: net
Priority: optional
Architecture: amd64
Maintainer: $BRAND <noreply@t-forge.online>
Recommends: xdg-utils, zenity, libcap2-bin
Installed-Size: $(du -ks "$root" | cut -f1)
Description: Настольная версия $BRAND
 Те же экраны, что в мобильном приложении: вход сканированием QR с
 телефона, выбор сервера, российские сервисы мимо туннеля.
 Трафик идёт через системный прокси, а в режиме VPN — через сетевой
 интерфейс, и тогда в туннель попадают все программы разом.
EOF

    dpkg-deb --build --root-owner-group -Zxz "$root" "$OUT/$BRAND.deb" > /dev/null
    rm -rf "$root" "$OUT/vv"
}

# Снап — тот же deb, завёрнутый для магазина приложений. Собирается
# отдельной командой, а не вместе со всем: snapcraft есть не на каждой
# машине, а для win и deb он не нужен вовсе.
#
# --destructive-mode: собираем прямо тут, без контейнера. Обычно так не
# делают, но тут нечего собирать — снап только разворачивает готовый
# deb и заворачивает его обратно. Зато не нужен ни lxd, ни права на
# него, ни та же версия Ubuntu, что у base.
#
# Единственное, что компилируется, — окно снапа (window/vv-window.c), и
# его собирает window/build.sh в docker под Ubuntu 22.04: то, что
# собрано здесь, на свежей системе, в core22 не запустилось бы.
#
# Работаем в отдельной папке: snapcraft уносит к себе всё, что лежит
# рядом с описанием, а в dist у нас и архивы, и прошлые сборки.

build_snap() {
    build_deb

    command -v snapcraft > /dev/null || {
        echo "нет snapcraft — снап собирать нечем (sudo snap install snapcraft --classic)" >&2
        exit 1
    }

    echo "Собираю снап $BRAND $VERSION"

    local work=$OUT/snap
    rm -rf "$work"
    mkdir -p "$work"

    sed "s/@VERSION@/$VERSION/" snapcraft.yaml.in > "$work/snapcraft.yaml"
    cp "$OUT/$BRAND.deb" "$work/"
    # Значок витрины: тот же, что на ярлыке, но магазин берёт его
    # только отдельным файлом, рядом с описанием.
    cp assets/icon-src.png "$work/icon.png"
    # Окно: из строгого снапа браузеров хозяина не видно, рисуем своё.
    window/build.sh "$work/window/vv-window"
    cp -r snap-launch "$work/"

    (cd "$work" && snapcraft pack --destructive-mode --verbosity brief)
    mv "$work"/*.snap "$OUT/"
    rm -rf "$work"
}

BRAND=$(sed -n 's/.*brandName *= *"\(.*\)"/\1/p' brand.go)
TITLE=$(sed -n 's/.*brandTitle *= *"\(.*\)"/\1/p' brand.go)

case "$WHAT" in
    win)  build_win ;;
    deb)  build_deb ;;
    snap) build_snap ;;
    all)  build_win; build_deb ;;
    *)    echo "не знаю, что такое «$WHAT»: бывает win, deb, snap или ничего" >&2; exit 1 ;;
esac

echo
ls -la "$OUT"
