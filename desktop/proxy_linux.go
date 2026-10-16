//go:build linux

package main

import (
	"fmt"
	"os/exec"
	"strconv"
	"strings"
)

const coreBinaryName = "xray"

// Системный прокси в линуксе.
//
// Туннель на уровне сетевого интерфейса не поднимаем: для него нужен
// root, а программа должна запускаться из меню обычным пользователем.
// Вместо этого переключаем общесистемные настройки прокси — те самые,
// что живут в «Параметры → Сеть → Прокси». Их читают GNOME, Firefox (у
// него «использовать системные настройки» стоит по умолчанию), Chrome и
// всё, что ходит через libproxy.
//
// Правим через gsettings, а не записью в dconf напрямую: так же, как
// это делает сам рабочий стол, и так же откатывается.

type gsettingKey struct{ schema, key string }

func (k gsettingKey) String() string { return k.schema + " " + k.key }

// Порядок важен и при записи, и при откате: режим идёт последним. Пока
// он не переключён, остальные ключи ни на что не влияют, — и состояния
// «прокси уже включён, а адрес ещё чужой» не возникает.
var proxyKeys = []gsettingKey{
	{"org.gnome.system.proxy.http", "host"},
	{"org.gnome.system.proxy.http", "port"},
	{"org.gnome.system.proxy.https", "host"},
	{"org.gnome.system.proxy.https", "port"},
	{"org.gnome.system.proxy.socks", "host"},
	{"org.gnome.system.proxy.socks", "port"},
	{"org.gnome.system.proxy", "ignore-hosts"},
	{"org.gnome.system.proxy", "mode"},
}

// Локальное мимо прокси. В линуксе список задаётся сетями, а не
// масками, поэтому 172.16/12 пишется одной строкой — в отличие от
// Windows, где тот же диапазон приходится перечислять по частям.
var proxyBypass = []string{
	"localhost", "127.0.0.0/8", "::1",
	"10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
}

func enableSystemProxy() error {
	if !haveGSettings() {
		// Рабочего стола может не быть вовсе — например, на сервере.
		// Рвать из-за этого соединение незачем: ядро работает, и
		// программы можно направить на него руками.
		logf("gsettings не нашёлся — системный прокси не трогаю")
		setNotice(fmt.Sprintf(
			"Настроить системный прокси нечем — укажите в программах SOCKS 127.0.0.1:%d", socksPort))
		return nil
	}

	// Чужие настройки запоминаем, чтобы вернуть их при отключении: у
	// человека мог быть свой прокси, и затереть его насовсем — грубо.
	saveForeignProxy()

	values := map[gsettingKey]string{
		{"org.gnome.system.proxy.http", "host"}:    "'127.0.0.1'",
		{"org.gnome.system.proxy.http", "port"}:    strconv.Itoa(httpPort),
		{"org.gnome.system.proxy.https", "host"}:   "'127.0.0.1'",
		{"org.gnome.system.proxy.https", "port"}:   strconv.Itoa(httpPort),
		{"org.gnome.system.proxy.socks", "host"}:   "'127.0.0.1'",
		{"org.gnome.system.proxy.socks", "port"}:   strconv.Itoa(socksPort),
		{"org.gnome.system.proxy", "ignore-hosts"}: bypassLiteral(),
		{"org.gnome.system.proxy", "mode"}:         "'manual'",
	}
	for _, key := range proxyKeys {
		if err := gsettingsSet(key, values[key]); err != nil {
			return err
		}
	}
	return nil
}

func disableSystemProxy() error {
	if !haveGSettings() {
		return nil
	}
	// Выключаем только своё: если прокси уже чужой, значит, его успели
	// поменять без нас, и трогать его нельзя.
	if !proxyIsOurs() {
		return nil
	}
	return restoreForeignProxy()
}

// healStaleProxy — снять прокси, оставшийся с прошлого запуска.
//
// Программу могли прибить сигналом или выключить машину кнопкой — тогда
// в настройках остаётся включённый прокси на наш порт, где уже никто не
// слушает, и на машине пропадает интернет целиком.
func healStaleProxy() (bool, error) {
	if !haveGSettings() || !proxyIsOurs() {
		return false, nil
	}
	return true, restoreForeignProxy()
}

// proxyIsOurs — настроено ли сейчас именно на нас.
func proxyIsOurs() bool {
	mode, err := gsettingsGet(gsettingKey{"org.gnome.system.proxy", "mode"})
	if err != nil || strings.Trim(mode, "'") != "manual" {
		return false
	}
	host, _ := gsettingsGet(gsettingKey{"org.gnome.system.proxy.socks", "host"})
	port, _ := gsettingsGet(gsettingKey{"org.gnome.system.proxy.socks", "port"})
	return strings.Trim(host, "'") == "127.0.0.1" && port == strconv.Itoa(socksPort)
}

// Прежние настройки держим в своём файле состояния, а не где-то в
// системе: так они переживают и перезапуск, и падение. Значения храним
// ровно в том виде, в каком их отдала gsettings, — тогда вернуть их
// можно слово в слово, не разбирая типы.
func saveForeignProxy() {
	if proxyIsOurs() {
		return // уже наше, запоминать нечего
	}

	saved := map[string]string{}
	for _, key := range proxyKeys {
		value, err := gsettingsGet(key)
		if err != nil {
			continue
		}
		saved[key.String()] = value
	}

	stateMu.Lock()
	st.PrevGnomeProxy = saved
	saveLocked()
	stateMu.Unlock()
}

func restoreForeignProxy() error {
	stateMu.Lock()
	saved := st.PrevGnomeProxy
	st.PrevGnomeProxy = nil
	saveLocked()
	stateMu.Unlock()

	// Не помним ничего — просто выключаем: это состояние по умолчанию,
	// и оно заведомо лучше, чем прокси в никуда.
	if len(saved) == 0 {
		return gsettingsSet(gsettingKey{"org.gnome.system.proxy", "mode"}, "'none'")
	}

	for _, key := range proxyKeys {
		value, ok := saved[key.String()]
		if !ok {
			continue
		}
		if err := gsettingsSet(key, value); err != nil {
			logf("не вернуть %s: %v", key, err)
		}
	}
	return nil
}

func bypassLiteral() string {
	quoted := make([]string, len(proxyBypass))
	for i, host := range proxyBypass {
		quoted[i] = "'" + host + "'"
	}
	return "[" + strings.Join(quoted, ", ") + "]"
}

func haveGSettings() bool {
	_, err := exec.LookPath("gsettings")
	return err == nil
}

func gsettingsGet(key gsettingKey) (string, error) {
	out, err := exec.Command("gsettings", "get", key.schema, key.key).Output()
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(string(out)), nil
}

func gsettingsSet(key gsettingKey, value string) error {
	out, err := exec.Command("gsettings", "set", key.schema, key.key, value).CombinedOutput()
	if err != nil {
		return fmt.Errorf("%s = %s: %s", key, value, strings.TrimSpace(string(out)))
	}
	return nil
}

// hideWindow — в линуксе у процесса своего окна и не бывает.
func hideWindow(cmd *exec.Cmd) {}

// openURL — последняя попытка показать человеку окно. Ждём ответа
// xdg-open, а не бросаем его вслепую: если открыть нечем, об этом надо
// сказать, иначе программа работает невидимо и объяснений никаких.
func openURL(url string) {
	if err := exec.Command("xdg-open", url).Run(); err != nil {
		logf("xdg-open не справился: %v", err)
		showError("Не удалось открыть окно.\n\nОткройте в браузере: " + url)
	}
}

// notify — уведомление рабочего стола. В отличие от showError окна не
// ставит и ответа не ждёт: это хорошая новость, а не беда, и человек
// может сидеть в другой программе.
func notify(title, text string) {
	path, err := exec.LookPath("notify-send")
	if err != nil {
		// Уведомлять нечем — пусть остаётся хотя бы в журнале. То же
		// сообщение человек увидит в окне, когда откроет программу.
		logf("notify-send не найден, уведомление не показано: %s", text)
		return
	}
	// Значок свой: пакет кладёт его в hicolor, и уведомление выглядит
	// как от программы, а не от безымянного процесса.
	// Значок остаётся brandName: так он называется в теме оформления.
	_ = exec.Command(path, "-i", brandName, "-a", brandTitle, title, text).Start()
}

// showError — окна консоли у программы нет, и сказать о беде можно
// только своим окошком. Берём то, что найдётся: zenity есть в Ubuntu из
// коробки, notify-send — почти везде.
func showError(text string) {
	text = strings.TrimSpace(text)
	logf("ошибка: %s", text)

	if path, err := exec.LookPath("zenity"); err == nil {
		cmd := exec.Command(path, "--error", "--title="+brandTitle, "--width=420", "--text="+text)
		if cmd.Run() == nil {
			return
		}
	}
	if path, err := exec.LookPath("notify-send"); err == nil {
		_ = exec.Command(path, "-u", "critical", brandTitle, text).Run()
	}
}
