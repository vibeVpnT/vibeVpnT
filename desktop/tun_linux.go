//go:build linux

package main

import (
	"fmt"
	"net"
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

func tunSupported() bool { return true }

// outboundMark — метка, которую ядро ставит на свои исходящие пакеты.
//
// По ней они обходят туннель: иначе соединение до сервера само попало
// бы в туннель, который этим же соединением и живёт. Ноль — метить
// незачем, туннеля нет.
func outboundMark() int {
	if !vpnMode() {
		return 0
	}
	return tunMark
}

// tunActive — работает ли сейчас служба туннеля.
func tunActive() bool {
	out, _ := exec.Command("systemctl", "is-active", tunService).Output()
	return strings.TrimSpace(string(out)) == "active"
}

// startTun — попросить systemd поднять туннель.
//
// Сначала обычным systemctl: в рабочем столе он сам покажет окошко с
// паролем. Если агента нет (например, программу запустили по ssh),
// пробуем pkexec — он спросит то же самое своим окном.
func startTun() error {
	if err := systemctlDo("start"); err != nil {
		return err
	}
	// Служба могла подняться и тут же упасть — скажем об этом сразу, а
	// не оставим человека с тумблером, который «вроде включён».
	time.Sleep(700 * time.Millisecond)
	if !tunActive() {
		return fmt.Errorf("туннель не поднялся, подробности: journalctl -u %s", tunService)
	}
	return nil
}

func stopTun() error { return systemctlDo("stop") }

func systemctlDo(action string) error {
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
// Возвращается, когда systemd просит остановиться, когда падает сам
// туннель или когда исчезает ядро: туннель без ядра — это машина без
// интернета, и оставлять такое нельзя ни на секунду.
func runTunHelper() error {
	if _, err := os.Stat("/dev/net/tun"); err != nil {
		return fmt.Errorf("в системе нет /dev/net/tun: %w", err)
	}

	tunnel := filepath.Join(exeDir(), "tun2socks")
	if _, err := os.Stat(tunnel); err != nil {
		return fmt.Errorf("рядом с программой нет tun2socks")
	}

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

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGTERM, syscall.SIGINT)

	for {
		select {
		case <-stop:
			_ = cmd.Process.Signal(syscall.SIGTERM)
			select {
			case <-done:
			case <-time.After(3 * time.Second):
				_ = cmd.Process.Kill()
			}
			return nil

		case err := <-done:
			return fmt.Errorf("туннель завершился: %v", err)

		case <-time.After(2 * time.Second):
			// Ядро исчезло — дальше туннелю отдавать пакеты некому.
			// Выходим сами, иначе на машине пропадёт интернет.
			if !socksAlive() {
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
