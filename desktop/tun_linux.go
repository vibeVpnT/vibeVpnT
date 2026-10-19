//go:build linux

package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

// Режим VPN в линуксе.
//
// Обычный режим — системный прокси: его видит браузер и всё, что умеет
// в libproxy, а остальные программы ходят мимо. Режим VPN заворачивает
// весь трафик машины: поднимается сетевой интерфейс, и ядро системы
// само отдаёт в него пакеты всех программ, даже тех, кто про прокси
// слыхом не слыхивал.
//
// Делает это отдельная мелкая программа — tun2socks. Она берёт пакеты
// из интерфейса и отдаёт их в наш локальный SOCKS, то есть в ядро xray,
// а дальше всё как обычно: правила, выбор сервера, российские сервисы
// напрямую.
//
// Для интерфейса и маршрутов нужен root, а программа запускается из
// меню обычным пользователем. Поэтому вся работа с правами вынесена в
// службу systemd: пакет её ставит, но не включает, а окно просит
// systemd её запустить. Пароль администратора спрашивает сам рабочий
// стол — тем же окошком, что и при установке обновлений.
//
// В снапе всё то же самое, но службой распоряжается не systemd хозяина
// (его оттуда и не видно), а сам snapd: служба объявлена в снапе, а
// окно просит её командой snapctl. Пароль там не спрашивают — снап
// управляет своими службами по праву, а не по разрешению.

const (
	// Имя интерфейса. Коротко, потому что ядро системы длиннее 15
	// знаков не принимает.
	tunDevice = brandName + "-tun"

	// Адрес интерфейса из диапазона 198.18/15 — он отведён под
	// тестирование оборудования и в настоящих сетях не встречается,
	// поэтому ни с чьей домашней сетью не столкнётся.
	tunAddress4 = "198.18.0.1"
	tunAddress6 = "fc00::1"

	// MTU с запасом: пакеты всё равно пересобираются в соединения
	// SOCKS, а крупный кусок обрабатывается дешевле мелкого.
	tunMTU = 8500

	// Метка на пакетах самого ядра xray. По ней они обходят туннель:
	// иначе трафик до сервера шёл бы в тот же туннель, который сам же
	// и обслуживает, — и не ушёл бы никуда.
	tunMark = 438

	// Своя таблица маршрутов и свои номера правил: трогаем только их,
	// чужие настройки остаются нетронутыми.
	tunTable    = 2026
	tunPrefMark = 10
	tunPrefAll  = 20

	tunService = brandName + "-tun.service"
)

// snapService — имя службы туннеля, если программа работает из снапа.
// Пустая строка означает обычную установку из пакета.
func snapService() string {
	name := os.Getenv("SNAP_INSTANCE_NAME")
	if name == "" {
		name = os.Getenv("SNAP_NAME")
	}
	if name == "" {
		return ""
	}
	return name + ".tun"
}

func tunSupported() bool { return true }

// markSupported — может ли ядро пометить свои исходящие пакеты.
//
// Метку ставит сам xray, а на это нужно право CAP_NET_ADMIN: в deb его
// выдаёт setcap при установке. В снапе так нельзя — файлы лежат в
// squashfs, примонтированной nosuid, а там права на файле не
// действуют вовсе. Поэтому в снапе метки нет, и соединение ядра с
// сервером уводит мимо туннеля отдельный маршрут (см. routeServer).
func markSupported() bool { return snapService() == "" }

// outboundMark — метка, которую ядро ставит на свои исходящие пакеты.
//
// По ней они обходят туннель: иначе соединение до сервера само попало
// бы в туннель, который этим же соединением и живёт. Ноль — метить
// незачем или нечем.
func outboundMark() int {
	if !vpnMode() || !markSupported() {
		return 0
	}
	return tunMark
}

// tunActive — поднят ли сейчас туннель.
//
// В снапе смотрим на сам интерфейс, а не на службу: служба там
// работает всегда и ждёт своего часа, так что по ней ничего не
// понять.
func tunActive() bool {
	if snapService() != "" {
		_, err := net.InterfaceByName(tunDevice)
		return err == nil
	}
	out, _ := exec.Command("systemctl", "is-active", tunService).Output()
	return strings.TrimSpace(string(out)) == "active"
}

// startTun — поднять туннель и дождаться интерфейса.
//
// Из пакета службу запускаем сами. В снапе просить некого и незачем:
// она уже работает и сама видит, что понадобилась, — по тому же
// флагу, который мы только что переключили.
//
// Ждём в обоих случаях именно интерфейс, а не слова «служба
// работает»: служба могла подняться и тут же упасть, и тогда человек
// остался бы с тумблером, который «вроде включён», но ничего не
// включил.
func startTun() error {
	if snapService() == "" {
		if err := serviceDo("start"); err != nil {
			return err
		}
	}
	if err := waitDevice(12 * time.Second); err != nil {
		return fmt.Errorf("туннель не поднялся, подробности: %s", tunLogHint())
	}
	return nil
}

// stopTun — выключить туннель.
//
// В снапе службу снаружи не трогаем: она уходит из туннеля сама,
// увидев, что больше не нужна. Нам остаётся дождаться, пока исчезнет
// интерфейс.
func stopTun() error {
	if snapService() == "" {
		return serviceDo("stop")
	}

	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		if !tunActive() {
			return nil
		}
		time.Sleep(300 * time.Millisecond)
	}
	return fmt.Errorf("туннель не выключился")
}

// tunLogHint — где смотреть, если туннель не поднялся.
func tunLogHint() string {
	if name := snapService(); name != "" {
		return "snap logs " + name
	}
	return "journalctl -u " + tunService
}

// serviceDo — попросить systemd запустить или остановить службу
// туннеля. Это путь обычной установки, из пакета.
//
// Сначала обычным systemctl: в рабочем столе он сам покажет окошко с
// паролем. Если агента нет (например, программу запустили по ssh),
// пробуем pkexec — он спросит то же самое своим окном.
func serviceDo(action string) error {
	out, err := exec.Command("systemctl", action, tunService).CombinedOutput()
	if err == nil {
		return nil
	}
	text := strings.TrimSpace(string(out))

	if path, lookErr := exec.LookPath("pkexec"); lookErr == nil {
		out, err = exec.Command(path, "systemctl", action, tunService).CombinedOutput()
		if err == nil {
			return nil
		}
		text = strings.TrimSpace(string(out))
	}

	if text == "" {
		text = err.Error()
	}
	if strings.Contains(text, "not found") || strings.Contains(text, "не найден") {
		return fmt.Errorf("служба туннеля не установлена — переустановите пакет")
	}
	return fmt.Errorf("%s", text)
}

// --- Всё, что ниже, выполняется от root внутри службы ------------------------

// runTunHelper — сама служба: поднять туннель и держать его.
//
// Из пакета она живёт ровно столько, сколько нужен туннель: её
// запускает окно по тумблеру и останавливает systemd, когда тумблер
// выключили.
//
// В снапе так не выходит: службами снапа командует только root, а
// программа работает от обычного пользователя — snapd на просьбу
// запустить службу отвечает отказом и даже пароля не спрашивает.
// Поэтому там служба работает всегда и сама решает, поднимать туннель
// или нет: раз в секунду спрашивает окно, нужна ли она, — ровно как в
// Windows, где остановить её снаружи тоже нечем. Пока не нужна, она
// ничего не делает и ничего не трогает.
func runTunHelper() error {
	if _, err := os.Stat("/dev/net/tun"); err != nil {
		return fmt.Errorf("в системе нет /dev/net/tun: %w", err)
	}

	tunnel := filepath.Join(exeDir(), "tun2socks")
	if _, err := os.Stat(tunnel); err != nil {
		return fmt.Errorf("рядом с программой нет tun2socks")
	}

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGTERM, syscall.SIGINT)

	if snapService() == "" {
		if err := holdTunnel(tunnel, stop); !errors.Is(err, errStopped) {
			return err
		}
		return nil
	}

	for {
		select {
		case <-stop:
			return nil
		case <-time.After(time.Second):
			if !tunnelWanted() {
				continue
			}
			err := holdTunnel(tunnel, stop)
			if errors.Is(err, errStopped) {
				return nil
			}
			if err != nil {
				logf("туннель: %v", err)
				// Не получилось — не долбимся каждую секунду: окно уже
				// показало ошибку, а человек вернётся к тумблеру сам.
				time.Sleep(5 * time.Second)
			}
		}
	}
}

// errStopped — службу попросили остановиться совсем. Отличается от
// «туннель больше не нужен»: после первого ждать нечего, служба
// заканчивается вместе с ним.
var errStopped = errors.New("служба остановлена")

// holdTunnel — поднять туннель и держать, пока он нужен.
func holdTunnel(tunnel string, stop chan os.Signal) error {
	// Длинные ключи пишутся двумя дефисами: с одним tun2socks разбирает
	// их по буквам, и `-device` превращается в устройство с именем
	// «evice» — молча и без ошибки.
	cmd := exec.Command(tunnel,
		"--device", "tun://"+tunDevice,
		"--proxy", "socks5://"+localAddr(socksPort),
		"--mtu", fmt.Sprint(tunMTU),
		"--loglevel", "warning",
	)
	cmd.Stdout, cmd.Stderr = os.Stdout, os.Stderr
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("туннель не запустился: %w", err)
	}

	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()

	// Интерфейс появляется не мгновенно: его создаёт сам туннель уже
	// после запуска. Адрес и маршруты до этого прописывать некуда.
	if err := waitDevice(5 * time.Second); err != nil {
		_ = cmd.Process.Kill()
		return err
	}

	restore := applyRouting()
	defer restore()

	for {
		select {
		case <-stop:
			_ = cmd.Process.Signal(syscall.SIGTERM)
			select {
			case <-done:
			case <-time.After(3 * time.Second):
				_ = cmd.Process.Kill()
			}
			return errStopped

		case err := <-done:
			return fmt.Errorf("туннель завершился: %v", err)

		case <-time.After(2 * time.Second):
			// Режим выключили, ядро исчезло или спрашивать стало не у
			// кого — выходим сами, иначе на машине пропадёт интернет.
			if !tunnelWanted() || !socksAlive() {
				_ = cmd.Process.Signal(syscall.SIGTERM)
				<-done
				return nil
			}
		}
	}
}

func waitDevice(limit time.Duration) error {
	deadline := time.Now().Add(limit)
	for time.Now().Before(deadline) {
		if _, err := net.InterfaceByName(tunDevice); err == nil {
			return nil
		}
		time.Sleep(100 * time.Millisecond)
	}
	return fmt.Errorf("интерфейс %s не появился", tunDevice)
}

func socksAlive() bool {
	conn, err := net.DialTimeout("tcp", localAddr(socksPort), 500*time.Millisecond)
	if err != nil {
		return false
	}
	_ = conn.Close()
	return true
}

// applyRouting — завернуть трафик в туннель. Возвращает откат.
//
// Правило с меткой идёт первым: пакеты самого ядра уходят обычным
// путём, всё остальное — в туннель. Так же это устроено у самого
// hev-socks5-tunnel в примерах, и так же — у мобильных клиентов.
func applyRouting() func() {
	type step struct{ up, down []string }

	steps := []step{
		// Сам интерфейс: туннель его только создал, адрес и состояние
		// задаём мы. Исчезает он вместе с туннелем, поэтому убирать
		// адрес отдельно не нужно.
		{up: []string{"ip", "addr", "add", tunAddress4 + "/15", "dev", tunDevice}},
		{up: []string{"ip", "-6", "addr", "add", tunAddress6 + "/64", "dev", tunDevice}},
		{up: []string{"ip", "link", "set", "dev", tunDevice, "up"}},

		// Проверку обратного пути выключаем: пакеты приходят из
		// туннеля, а уходят в обычный интерфейс, и ядро системы без
		// этого считает их подделкой и выбрасывает.
		{up: sysctlArgs("net.ipv4.conf.all.rp_filter", "0")},
		{up: sysctlArgs("net.ipv4.conf."+tunDevice+".rp_filter", "0")},

		{up: []string{"ip", "rule", "add", "fwmark", fmt.Sprint(tunMark), "lookup", "main", "pref", fmt.Sprint(tunPrefMark)},
			down: []string{"ip", "rule", "del", "pref", fmt.Sprint(tunPrefMark)}},
		{up: []string{"ip", "route", "add", "default", "dev", tunDevice, "table", fmt.Sprint(tunTable)},
			down: []string{"ip", "route", "flush", "table", fmt.Sprint(tunTable)}},
		{up: []string{"ip", "rule", "add", "lookup", fmt.Sprint(tunTable), "pref", fmt.Sprint(tunPrefAll)},
			down: []string{"ip", "rule", "del", "pref", fmt.Sprint(tunPrefAll)}},

		// То же для IPv6: иначе у провайдера с IPv6 часть трафика
		// обойдёт туннель стороной и уйдёт с домашнего адреса.
		{up: []string{"ip", "-6", "rule", "add", "fwmark", fmt.Sprint(tunMark), "lookup", "main", "pref", fmt.Sprint(tunPrefMark)},
			down: []string{"ip", "-6", "rule", "del", "pref", fmt.Sprint(tunPrefMark)}},
		{up: []string{"ip", "-6", "route", "add", "default", "dev", tunDevice, "table", fmt.Sprint(tunTable)},
			down: []string{"ip", "-6", "route", "flush", "table", fmt.Sprint(tunTable)}},
		{up: []string{"ip", "-6", "rule", "add", "lookup", fmt.Sprint(tunTable), "pref", fmt.Sprint(tunPrefAll)},
			down: []string{"ip", "-6", "rule", "del", "pref", fmt.Sprint(tunPrefAll)}},
	}

	undo := []func(){}

	// Где метки нет (снап), соединение ядра с сервером уводим мимо
	// туннеля отдельным маршрутом: иначе оно уйдёт в туннель, который
	// этим же соединением и живёт.
	if !markSupported() {
		undo = append(undo, routeServer())
	}

	for _, s := range steps {
		if err := run(s.up); err != nil {
			logf("не вышло: %s (%v)", strings.Join(s.up, " "), err)
			continue
		}
		if len(s.down) == 0 {
			continue
		}
		down := s.down
		undo = append(undo, func() {
			if err := run(down); err != nil {
				logf("откат не вышел: %s (%v)", strings.Join(down, " "), err)
			}
		})
	}

	undo = append(undo, applyDNS())

	return func() {
		// В обратном порядке: сначала снимаем правила, потом
		// маршруты — иначе на миг остаётся правило, ведущее в
		// опустевшую таблицу.
		for i := len(undo) - 1; i >= 0; i-- {
			undo[i]()
		}
	}
}

// applyDNS — имена спрашиваем через туннель.
//
// Без этого запросы уходят резолверу системы, а тот ходит в интернет
// мимо туннеля: имена разрешает провайдер, и всё, что он подменяет,
// остаётся подменённым даже при включённом VPN.
func applyDNS() func() {
	if _, err := exec.LookPath("resolvectl"); err != nil {
		logf("resolvectl не найден — имена останутся на системном резолвере")
		return func() {}
	}

	if err := run([]string{"resolvectl", "dns", tunDevice, "1.1.1.1", "8.8.8.8"}); err != nil {
		logf("не задать DNS туннелю: %v", err)
		return func() {}
	}
	// «~.» означает «все имена сюда»: иначе резолвер отдал бы туннелю
	// только то, чего не знает никто другой.
	if err := run([]string{"resolvectl", "domain", tunDevice, "~."}); err != nil {
		logf("не задать домен туннелю: %v", err)
	}

	// Возвращать настройки интерфейса не нужно: он исчезает вместе с
	// туннелем, а с ним и его DNS. Сбрасываем кеш, чтобы подменённые
	// ответы не пережили выключение.
	return func() { _ = run([]string{"resolvectl", "flush-caches"}) }
}

// routeServer — держать соединение с сервером мимо туннеля.
//
// То же самое, что делает метка на пакетах, только снаружи: один адрес
// ходит прежним путём, всё остальное — в туннель. Адрес один и тот же
// на всё время соединения: при смене сервера ядро перезапускается, а с
// ним и туннель.
func routeServer() func() {
	ip := askServerIP()
	if ip == "" {
		logf("адрес сервера неизвестен — соединение с ним может уйти в туннель")
		return func() {}
	}

	gateway, dev := defaultRoute()
	if gateway == "" {
		logf("не нашёл шлюз по умолчанию — трафик до сервера может пойти в туннель")
		return func() {}
	}

	args := []string{"ip", "route", "add", ip, "via", gateway}
	if dev != "" {
		args = append(args, "dev", dev)
	}
	if err := run(args); err != nil {
		logf("не вышло: %s (%v)", strings.Join(args, " "), err)
		return func() {}
	}
	return func() {
		if err := run([]string{"ip", "route", "del", ip}); err != nil {
			logf("откат не вышел: ip route del %s (%v)", ip, err)
		}
	}
}

// askServerIP — спросить у окна, с каким сервером оно работает.
//
// Службе доводов не передают: ни systemd, ни snapd их не знают. Зато
// окно рядом, на петле, и адрес у него уже разрешён — оно само
// разрешило его, пока обычная сеть ещё работала.
func askServerIP() string {
	client := &http.Client{Timeout: 3 * time.Second}
	resp, err := client.Get(fmt.Sprintf("http://127.0.0.1:%d/api/tunwanted", uiPort))
	if err != nil {
		return ""
	}
	defer resp.Body.Close()

	var answer struct {
		Server string `json:"server"`
	}
	if json.NewDecoder(resp.Body).Decode(&answer) != nil {
		return ""
	}
	if net.ParseIP(answer.Server) == nil {
		return ""
	}
	return answer.Server
}

// defaultRoute — чем машина ходит в интернет сейчас: шлюз и интерфейс.
func defaultRoute() (gateway, dev string) {
	out, err := exec.Command("ip", "-4", "route", "show", "default").Output()
	if err != nil {
		return "", ""
	}
	line := strings.Split(strings.TrimSpace(string(out)), "\n")[0]

	fields := strings.Fields(line)
	for i := 0; i+1 < len(fields); i++ {
		switch fields[i] {
		case "via":
			gateway = fields[i+1]
		case "dev":
			dev = fields[i+1]
		}
	}
	return gateway, dev
}

func sysctlArgs(key, value string) []string {
	return []string{"sysctl", "-qw", key + "=" + value}
}

func run(args []string) error {
	out, err := exec.Command(args[0], args[1:]...).CombinedOutput()
	if err != nil {
		text := strings.TrimSpace(string(out))
		if text == "" {
			return err
		}
		return fmt.Errorf("%s", text)
	}
	return nil
}
