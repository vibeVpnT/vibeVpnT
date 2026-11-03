# AppGallery: карточка приложения

Позиционирование: клиент Xray (VLESS, VMess, Trojan, Shadowsocks,
Hysteria2) для своих серверов и подписок. Готовую подписку VibeVPN
можно купить отдельно — это дополнительная возможность, а не условие
работы.

Категория: Инструменты. Возраст: 12+ (или 3+, если позволит анкета).

---

## Русский

**Название:** VibeVPN — клиент Xray

**Краткое описание (до 80 знаков):**
Клиент Xray: VLESS, Trojan, Shadowsocks, Hysteria2. Свои серверы и подписки

**Полное описание:**

VibeVPN — клиент на ядре Xray для Android. Подключайтесь к своим
серверам или подпискам: приложение работает с любым совместимым
поставщиком.

Протоколы:
• VLESS (в том числе Reality и XTLS Vision), VMess, Trojan
• Shadowsocks, Hysteria2, WireGuard, SOCKS, HTTP
• транспорты TCP, WebSocket, gRPC, HTTPUpgrade, XHTTP

Возможности:
• добавление серверов по ссылке, из буфера обмена и по QR-коду
• подписки по ссылке с обновлением в один тап
• проверка задержки всех серверов и сортировка по ней
• раздельное туннелирование: выбор приложений, которые идут через
  туннель или мимо него
• маршрутизация по доменам и IP, правила geosite и geoip
• плитка в шторке быстрых настроек и виджет
• тёмная тема, интерфейс на русском и английском

Готовая подписка (по желанию). Если своего сервера нет, в приложении
можно оформить подписку VibeVPN с пробным периодом. Аккаунт — это
только номер: без почты, телефона и пароля. Одну подписку можно
подключить на нескольких устройствах.

Приложение не показывает рекламу и не собирает историю посещённых
сайтов.

Исходный код основан на v2rayNG (GPL-3.0).

**Что нового (первая версия):** Первая публикация в AppGallery.

---

## English

**Name:** VibeVPN — Xray client

**Short description:**
Xray client: VLESS, Trojan, Shadowsocks, Hysteria2. Your servers & subs

**Full description:**

VibeVPN is an Xray-core client for Android. Connect to your own servers
or subscriptions — it works with any compatible provider.

Protocols:
• VLESS (including Reality and XTLS Vision), VMess, Trojan
• Shadowsocks, Hysteria2, WireGuard, SOCKS, HTTP
• TCP, WebSocket, gRPC, HTTPUpgrade, XHTTP transports

Features:
• add servers from a link, the clipboard or a QR code
• subscriptions with one-tap update
• latency test for all servers, sort by latency
• split tunneling: choose which apps use the tunnel
• routing by domain and IP, geosite and geoip rules
• Quick Settings tile and home screen widget
• dark theme, Russian and English interface

Ready-made subscription (optional). If you don't have a server, you can
get a VibeVPN subscription in the app, with a free trial. The account is
just a number — no email, phone or password. One subscription works on
several devices.

No ads, no browsing history collected.

Based on v2rayNG (GPL-3.0).

---

## Для проверки: зачем VpnService

Русский:
Приложение — клиент прокси-протоколов на ядре Xray. VpnService нужен,
чтобы направить трафик устройства в локальное ядро Xray, которое
передаёт его на сервер, выбранный пользователем. Туннель запускается
только по нажатию пользователя, в шторке всё время видно уведомление.
Трафик не анализируется, история посещений не записывается, реклама не
внедряется.

English:
The app is a proxy client based on Xray-core. VpnService is used to route
device traffic to the local Xray core, which forwards it to the server
chosen by the user. The tunnel starts only when the user taps Connect, and
a persistent notification is shown while it is active. Traffic is not
inspected, browsing history is not logged, no ads are injected.

## Для проверки: тестовый доступ

Приложение работает без аккаунта: проверяющий может добавить любой
свой сервер. Для проверки подписки — номер тестового аккаунта (выдать
перед отправкой, в этот файл не записывать).
