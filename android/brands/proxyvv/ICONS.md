# proxyvv — иконки, палитра «Изумруд»

Знак: две вложенные «v» (vv). Для Claude Code: ниже куда что класть.

## Цвета
- Фон иконки: `#0F5C4A`
- Внешняя v: `#F4F5F7`
- Внутренняя v (акцент): `#FFD23F`
- Инвертированная версия: фон `#F4F5F7`, внешняя `#0F5C4A`, внутренняя `#1E9E6B`

## Файлы
- `svg/icon.svg` — основная иконка (скруглённая), исходник для всего остального
- `svg/icon-square.svg` — без скругления (iOS скругляет сам)
- `svg/icon-inverted.svg` — инвертированная версия
- `svg/mark.svg`, `svg/mark-mono-*.svg` — знак без фона (прозрачный)
- `svg/wordmark-on-light.svg`, `svg/wordmark-on-dark.svg` — знак + слово proxyvv (текст в кривых)
- `png/` — иконка в размерах 32–1024
- `ios/` — AppIcon для Assets.xcassets/AppIcon.appiconset (квадратные, без прозрачности; для Xcode 14+ достаточно AppIcon-1024.png)
- `android/` — adaptive icon: `ic_launcher_foreground.svg` (импортировать как Vector Asset) + фон цвета из `ic_launcher_background.txt`; legacy-PNG по плотностям `mipmap-*`; `play-store-512.png` для Google Play
- `web/` — `favicon.ico`, `favicon.svg`, `apple-touch-icon.png`, `icon-192.png`, `icon-512.png` (для manifest)

## Web: вставить в <head>
```html
<link rel="icon" href="/favicon.ico" sizes="any">
<link rel="icon" href="/favicon.svg" type="image/svg+xml">
<link rel="apple-touch-icon" href="/apple-touch-icon.png">
<meta name="theme-color" content="#0F5C4A">
```
