#!/usr/bin/env bash
# Разносит бренд по ресурсам проекта: иконки, цвет фона иконки, чистка
# дублей имени приложения. Запускать после любой правки файла бренда.
#
#   ./rebrand.sh               — бренд по умолчанию (aviavp)
#   BRAND=proxyvv ./rebrand.sh — другой
#
# Ресурсы в проекте одни на всех, поэтому в рабочем дереве лежат картинки
# последнего собранного бренда. Это нормально: build.sh всегда прогоняет
# этот скрипт перед сборкой.
#
# Всё остальное — имя, пакет, версия, цвета экранов, ссылки — читается
# из brand.properties прямо в build.gradle.kts, скрипт для этого не нужен.
set -euo pipefail

cd "$(dirname "$0")"
RES=V2rayNG/app/src/main/res
BRAND=${BRAND:-tv}
BRAND_FILE="brands/$BRAND.properties"

[ -f "$BRAND_FILE" ] || { echo "нет бренда '$BRAND': ожидался $BRAND_FILE"; exit 1; }

prop() {
    local v
    v=$(grep -E "^$1=" "$BRAND_FILE" | head -1 | cut -d= -f2-)
    printf '%s' "$v"
}

# Иконка в шторке и строке состояния. Android красит её сам и видит
# только форму, поэтому у брендов она либо вектором, либо набором
# белых PNG. Ставим ровно одно из двух и чистим другое: одноимённые
# ресурсы в drawable/ и drawable-*dpi/ конфликтуют между собой.
install_notification_icon() {
    local vector="$ANDROID_ICONS/ic_stat_name.xml"
    local pngs="$ANDROID_ICONS/notification"

    if [ -f "$vector" ]; then
        rm -f "$RES"/drawable-*dpi/ic_stat_name.png
        install -D -m 644 "$vector" "$RES/drawable/ic_stat_name.xml"
        echo "Иконка в шторке: вектор"
        return
    fi

    if [ -d "$pngs" ]; then
        rm -f "$RES/drawable/ic_stat_name.xml"
        for dpi in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
            local src="$pngs/ic_stat_name-$dpi.png"
            [ -f "$src" ] && install -D -m 644 "$src" "$RES/drawable-$dpi/ic_stat_name.png"
        done
        echo "Иконка в шторке: набор PNG"
        return
    fi

    echo "Иконка в шторке у бренда не задана — оставлена прежняя"
}

# Баннер для Android TV: там приложение показывается широкой плиткой,
# а не квадратной иконкой. Собираем из той же картинки бренда.
install_tv_banner() {
    # Если бренд дал знак на прозрачном, берём его: иначе на баннере
    # проступает скруглённая подложка самой иконки.
    local mark="$ANDROID_ICONS/banner_mark.png"
    [ -f "$mark" ] || mark="$ICON"
    [ -f "$mark" ] || return 0

    python3 - "$mark" "$RES" "$ICON_BG" <<'PYBANNER'
import os
import sys

try:
    from PIL import Image
except ImportError:
    sys.exit(0)

src_path, res, bg_hex = sys.argv[1], sys.argv[2], sys.argv[3]
src = Image.open(src_path).convert("RGBA")
bg = tuple(int(bg_hex[i:i + 2], 16) for i in (2, 4, 6)) + (255,)

# Плотности баннера те же, что у лаунчера; базовый размер 320x180.
for dpi, scale in (("xhdpi", 1.0), ("xxhdpi", 1.5), ("xxxhdpi", 2.0)):
    w, h = int(320 * scale), int(180 * scale)

    mark = src.copy()
    mark.thumbnail((int(h * 0.72), int(h * 0.72)), Image.LANCZOS)

    # Передний план адаптивного баннера — на прозрачном: фон кладёт система.
    fg = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    fg.paste(mark, ((w - mark.width) // 2, (h - mark.height) // 2), mark)

    d = os.path.join(res, f"mipmap-{dpi}")
    os.makedirs(d, exist_ok=True)
    fg.save(os.path.join(d, "ic_banner_foreground.png"))

    # Запасной баннер для систем без адаптивных иконок — уже с фоном.
    flat = Image.new("RGBA", (w, h), bg)
    flat.alpha_composite(fg)
    flat.convert("RGB").save(os.path.join(d, "ic_banner.png"))
PYBANNER

    cat > "$RES/values/ic_banner_background.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_banner_background">#${ICON_BG:2}</color>
</resources>
XML
    echo "Баннер для ТВ пересобран"
}

# Настройки Firebase: у каждого бренда свой файл, и он кладётся рядом
# с приложением под именем, которое ждёт плагин. Нет файла — убираем
# прежний, иначе сборка одного бренда утащит push другого.
setup_firebase() {
    local src="brands/$BRAND/google-services.json"
    local dst="V2rayNG/app/google-services.json"

    if [ -f "$src" ]; then
        cp "$src" "$dst"
        echo "Firebase: настройки бренда на месте"
    else
        rm -f "$dst"
        echo "Firebase: у бренда нет настроек — push выключены"
    fi
}

NAME=$(prop brand.name)
APPID=$(prop brand.applicationId)
BG=$(prop brand.color.background)
ICON=$(prop brand.icon.source)
ANDROID_ICONS=$(prop brand.icon.android)
# Фон иконки может отличаться от фона приложения: на иконке он бывает
# насыщенным, а на экранах почти чёрным.
ICON_BG=$(prop brand.color.iconBackground)
ICON_BG=${ICON_BG:-$BG}

echo "Бренд: $NAME ($APPID)"

# 0. Ярлыки на длинное нажатие по иконке. Имя пакета в res/xml подставить
#    на этапе сборки нельзя — только текстом, здесь.
sed -i -E "s/android:targetPackage=\"[^\"]*\"/android:targetPackage=\"$APPID\"/" \
    "$RES/xml/shortcuts.xml"
echo "Пакет в ярлыках: $APPID"

# 1. Имя приложения задаётся в build.gradle.kts через resValue. Если оно
#    осталось и в strings.xml, сборка падает на дубле ресурса — убираем.
removed=0
for f in "$RES"/values*/strings.xml; do
    touched=0
    for key in app_name app_tile_name app_widget_name; do
        if grep -q "<string name=\"$key\"" "$f"; then
            sed -i "/<string name=\"$key\"/d" "$f"
            touched=1
        fi
    done
    removed=$((removed + touched))
done
echo "Имена приложения убраны из strings.xml: $removed файл(ов)"

# 2. Фон адаптивной иконки — тот же, что фон приложения.
cat > "$RES/values/ic_launcher_background.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#${ICON_BG:2}</color>
</resources>
XML
echo "Фон иконки: #${ICON_BG:2}"

# 3a. Готовый набор иконок по плотностям, если дизайнер его прислал.
#     Перерисовывать из квадрата в этом случае нельзя: в наборе передний
#     план и фон уже разведены как надо для адаптивной иконки.
# Проверяем не каталог, а наличие в нём самих иконок лаунчера: каталог
# может существовать ради одной лишь иконки для шторки.
if [ -n "$ANDROID_ICONS" ] && [ -f "$ANDROID_ICONS/ic_launcher-xxxhdpi.png" ]; then
    for dpi in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
        src="$ANDROID_ICONS/ic_launcher-$dpi.png"
        [ -f "$src" ] || continue
        install -D -m 644 "$src" "$RES/mipmap-$dpi/ic_launcher.png"
        install -D -m 644 "$src" "$RES/mipmap-$dpi/ic_launcher_round.png"
    done

    # Передний план адаптивной иконки: один файл 432px на все плотности.
    # Android сам отмасштабирует, а качество на 432 с запасом.
    fg="$ANDROID_ICONS/ic_launcher_foreground-432.png"
    if [ -f "$fg" ]; then
        for dpi in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
            install -D -m 644 "$fg" "$RES/mipmap-$dpi/ic_launcher_foreground.png"
        done
    fi

    echo "Иконки взяты готовыми из $ANDROID_ICONS"
    install_notification_icon
    install_tv_banner
    setup_firebase
    exit 0
fi

# 3b. Иначе собираем все плотности из одного исходника.
if [ ! -f "$ICON" ]; then
    echo "Иконка $ICON не найдена — прежние оставлены без изменений"
    setup_firebase
    exit 0
fi

python3 - "$ICON" "$RES" "$ICON_BG" <<'PY'
import sys, os
try:
    from PIL import Image
except ImportError:
    sys.exit("нужен Pillow: pip install --user pillow")

src_path, res, bg_hex = sys.argv[1], sys.argv[2], sys.argv[3]
src = Image.open(src_path).convert("RGBA")
bg = tuple(int(bg_hex[i:i + 2], 16) for i in (2, 4, 6)) + (255,)

def emblem():
    """Вырезает сам знак. Надпись под знаком отделена от него пустой полосой,
    по ней и режем: идём сверху вниз, пока строки не опустеют надолго."""
    w, h = src.size
    px = src.load()

    def is_mark(x, y):
        r, g, b, _ = px[x, y]
        return b > 90 and (b - r) > 40            # синие пиксели знака

    rows = [sum(1 for x in range(0, w, 2) if is_mark(x, y))
            for y in range(0, h, 2)]
    peak = max(rows)
    if peak == 0:
        return src                                 # знак не распознан — берём целиком

    top = next(i for i, c in enumerate(rows) if c > peak * 0.02)
    gap, bottom = 0, top
    for i in range(top, len(rows)):
        if rows[i] > peak * 0.02:
            bottom, gap = i, 0
        else:
            gap += 1
            if gap > len(rows) * 0.03:             # полоса пустых строк — дальше надпись
                break

    y0, y1 = top * 2, min(h, (bottom + 1) * 2)
    xs = [x for x in range(0, w, 2)
          for y in range(y0, y1, 2) if is_mark(x, y)]
    x0, x1 = min(xs), max(xs)

    pad = int(max(x1 - x0, y1 - y0) * 0.04)
    return src.crop((max(0, x0 - pad), max(0, y0 - pad),
                     min(w, x1 + pad), min(h, y1 + pad)))

mark = emblem()

def square(img, side, scale, background):
    """Знак по центру квадрата; scale — доля стороны под сам знак."""
    canvas = Image.new("RGBA", (side, side), background)
    inner = int(side * scale)
    m = img.copy()
    m.thumbnail((inner, inner), Image.LANCZOS)
    canvas.paste(m, ((side - m.width) // 2, (side - m.height) // 2), m)
    return canvas

def rounded(img):
    from PIL import ImageDraw
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).ellipse((0, 0, img.width - 1, img.height - 1), fill=255)
    out = img.copy()
    out.putalpha(mask)
    return out

# ic_launcher*     — готовая картинка вместе с фоном (старые Android).
# ic_launcher_foreground — слой адаптивной иконки: холст 108dp, знак обязан
#                    уместиться в центральные 66%, края система обрезает.
sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for dpi, side in sizes.items():
    d = os.path.join(res, f"mipmap-{dpi}")
    os.makedirs(d, exist_ok=True)
    legacy = square(mark, side, 0.80, bg)
    legacy.convert("RGB").save(os.path.join(d, "ic_launcher.png"))
    rounded(legacy).save(os.path.join(d, "ic_launcher_round.png"))
    fg_side = side * 108 // 48
    square(mark, fg_side, 0.60, (0, 0, 0, 0)).save(
        os.path.join(d, "ic_launcher_foreground.png"))

print(f"Иконки пересобраны из {src_path}: знак {mark.width}x{mark.height}")
PY

install_notification_icon
install_tv_banner

# Настройки Firebase кладём последними: они не зависят от иконок, а
# видеть их в хвосте вывода удобнее — сразу ясно, будет ли push.
setup_firebase
