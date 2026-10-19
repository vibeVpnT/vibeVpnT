# Снап

Снап — это та же настольная версия, завёрнутая для магазина
приложений Ubuntu. Внутри лежит готовый `vv.deb`, распакованный один в
один: программа, ядро, справочники и ярлык.

```sh
./pack.sh snap     # соберёт deb и завернёт его в dist/vibevpn_<версия>_amd64.snap
```

Контейнер (lxd) для этого не нужен: собирать нечего, снап только
разворачивает готовый пакет, поэтому сборка идёт прямо на месте
(`--destructive-mode`).

## Почему classic, а не строгий снап

Строгий снап у нас уже был, и в нём не работали ровно две вещи — обе
заметные.

**Окно.** Интерфейс программы — это страница, которую она отдаёт сама
себе на 127.0.0.1, а показывает её браузер человека: `chrome --app=…`
рисует окно без вкладок и адресной строки, и выглядит это как обычное
приложение. Внутри строгого снапа чужих программ не видно вовсе — ни
одного браузера там нет. Остаётся `xdg-open`, то есть «открой ссылку
чем-нибудь»: интерфейс появлялся вкладкой в случайном окне.

**Режим VPN.** Туннель поднимает служба снапа, и ей нужны `ip`,
`sysctl` и `resolvectl` системы. Плюс ядру нужно право помечать свои
пакеты (`CAP_NET_ADMIN`), а в снапе файлы лежат в squashfs,
примонтированной `nosuid`, — права на файле там не действуют. Поэтому
в снапе метки нет, и соединение с сервером уводит мимо туннеля
отдельный маршрут (см. `routeServer` в `tun_linux.go`).

## Служба туннеля

В пакете режим VPN поднимает служба systemd: её запускает окно по
тумблеру, и тогда же рабочий стол спрашивает пароль администратора.

В снапе так нельзя. Службами снапа командует только root, а программа
работает от обычного пользователя — snapd отвечает отказом и даже
пароля не спрашивает:

```
error: cannot use "start" with uid 1000, try with sudo
```

Поэтому в снапе служба (`vibevpn.tun`) работает всегда и решает сама:
раз в секунду спрашивает окно, нужен ли туннель, — и поднимает его или
уходит из него. Ровно так же устроена Windows-версия, где остановить
службу снаружи тоже нечем. Пока туннель не нужен, служба ничего не
делает и ничего не трогает: один запрос на петлю в секунду.

Выбор между двумя путями программа делает по переменной `SNAP_NAME`,
см. `runTunHelper` и `startTun` в `tun_linux.go`.

## Поставить себе и проверить

```sh
vibevpn --quit                # выйти из работающей копии — обязательно
sudo snap remove vibevpn
sudo snap install --dangerous --classic dist/vibevpn_2.6.1_amd64.snap
sudo snap logs vibevpn.tun    # если туннель не поднялся
sudo cat /var/snap/vibevpn/current/vv/vv.log   # лог самой службы
```

Про `--quit` всерьёз: закрытие окна браузера программу не выключает,
она остаётся работать в фоне — и после переустановки продолжает
работать **старым** кодом, из снятого уже снапа. Проверка в таком виде
показывает прошлую сборку и путает следы.

Проверять стоит ровно три вещи: окно открывается отдельным окном, а не
вкладкой; тумблер «Режим VPN» включается без ошибки; после выключения
интернет остаётся на месте.

## Выложить в магазин

```sh
snapcraft upload dist/vibevpn_2.6.1_amd64.snap --release=stable
```

Classic в магазине дают не всем и не сразу: нужна заявка на форуме
Snapcraft, в разделе `store-requests`, и её читают люди. Пока заявку не
одобрили, `snapcraft upload` ответит отказом — classic revision не
принимается. Снап при этом можно раздавать файлом: `--dangerous
--classic` ставится и без магазина.

Текст заявки (создать тему в <https://forum.snapcraft.io/c/store-requests>,
заголовок `Request for classic confinement: vibevpn`):

> **Request for classic confinement: vibevpn**
>
> `vibevpn` is a desktop VPN client (Xray + tun2socks) published by
> `vibevpn`. Its user interface is a local web page that the
> application serves on `127.0.0.1` and opens in the user's own browser
> as an application window — `google-chrome --app=…`, falling back to
> `firefox --new-window`.
>
> Under strict confinement the snap cannot see or launch any browser
> installed on the host: there is no browser inside the snap's mount
> namespace, so the only available option is `xdg-open` through the
> desktop portal. That opens the interface as a tab in whatever browser
> happens to be running, which is not an acceptable presentation for an
> application whose whole window is that page. I am not aware of an
> interface that allows launching an arbitrary host browser as a child
> process.
>
> The VPN mode needs host tooling as well. The snap ships its own
> daemon that brings up a TUN device and configures routing with `ip`,
> `sysctl` and `resolvectl` from the host, and the Xray core marks its
> own packets with `SO_MARK`, which requires `CAP_NET_ADMIN` granted
> through file capabilities. File capabilities do not work from a
> squashfs mounted `nosuid`, so this part cannot work under strict
> confinement even with `network-control` granted.
>
> Requested category: running arbitrary host binaries that are not and
> cannot be shipped inside the snap (the user's browser and the host
> network tooling).
>
> Source: <https://github.com/vibeVpnT/vibeVpnT> (`desktop/`)

Если classic не одобрят, остаются два пути: раздавать снап файлом (он
ставится с `--dangerous --classic`) или оставить магазину строгий снап,
где интерфейс открывается вкладкой, а режим VPN не работает — и тогда
тумблер там придётся прятать, чтобы не обещать несбыточное. Deb при
этом работает как работал: его ничего из этого не касается.
