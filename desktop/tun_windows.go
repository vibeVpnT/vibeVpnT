//go:build windows

package main

import (
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

// Режим VPN в Windows.
//
// Обычный режим — системный прокси из реестра: его слушают браузеры и
// всё, что ходит через WinINet, а остальные программы идут мимо. Режим
// VPN поднимает сетевой адаптер (Wintun), и Windows отдаёт в него
// пакеты всех программ без разбора; дальше их забирает tun2socks и
// отдаёт в наш SOCKS, то есть в то же ядро и те же правила.
//
// Адаптер и маршруты требуют прав администратора, а программа
// запускается двойным щелчком от обычного пользователя. Поэтому вторая
// копия себя запускается через UAC — то самое окно «Разрешить внести
// изменения?» — и только она занимается адаптером.
//
// Выключить её мы не можем: убить процесс с правами выше своих нельзя.
// Поэтому она сама спрашивает у окна, нужна ли ещё, и уходит, когда
// ответ «нет» или когда спрашивать стало не у кого.

const (
	tunDevice   = brandName + "-tun"
	tunAddress4 = "198.18.0.1"
	tunNetmask  = "255.255.255.0"
	tunMTU      = 8500
)

// tunSupported — есть ли чем поднимать туннель.
//
// Проверяем по файлам: старые сборки раскладывали рядом только ядро, и
// обещать там режим VPN нечем.
func tunSupported() bool {
	for _, name := range []string{"tun2socks.exe", "wintun.dll"} {
		if _, err := os.Stat(filepath.Join(assetDir(), name)); err != nil {
			return false
		}
	}
	return true
}

// outboundMark — в Windows меток на пакетах нет. Соединение ядра с
// сервером обходит туннель не меткой, а отдельным маршрутом до адреса
// сервера — его прописывает сама служба.
func outboundMark() int { return 0 }

// egressInterface — привязывать соединения ядра не к чему: туннель в
// Windows обходит сервер своим маршрутом (см. applyRouting).
func egressInterface() string { return "" }

func tunActive() bool {
	_, err := net.InterfaceByName(tunDevice)
	return err == nil
}

// tunPermission — разрешений спрашивать не у кого: права администратора
// Windows запрашивает сама, при запуске службы.
func tunPermission() *permissionNeeded { return nil }

func startTun() error {
	if !tunSupported() {
		return fmt.Errorf("рядом с программой нет tun2socks.exe — обновите версию")
	}

	s, ok := selectedServer()
	if !ok {
		return fmt.Errorf("не выбран сервер")
	}
	// Адрес сервера нужен службе, чтобы увести его трафик мимо
	// туннеля. Имя разрешаем здесь, пока обычная сеть ещё работает.
	ip := serverAddress(s.Address)
	if net.ParseIP(ip) == nil {
		return fmt.Errorf("не удалось узнать адрес сервера %s", s.Address)
	}

	if err := elevate("--tun-run " + ip); err != nil {
		return err
	}

	// Ждём, пока появится адаптер. Долго: между запросом и согласием
	// человека — окно UAC, и ответить он может не сразу.
	deadline := time.Now().Add(40 * time.Second)
	for time.Now().Before(deadline) {
		if tunActive() {
			return nil
		}
		time.Sleep(400 * time.Millisecond)
	}
	return fmt.Errorf("туннель не поднялся за 40 секунд")
}

// stopTun — просить службу уйти напрямую нечем, но она сама спрашивает
// окно, нужна ли ещё. Состояние к этому моменту уже переключено, так
// что остаётся дождаться, пока адаптер исчезнет.
func stopTun() error {
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		if !tunActive() {
			return nil
		}
		time.Sleep(300 * time.Millisecond)
	}
	return fmt.Errorf("туннель не выключился")
}

// elevate — запустить вторую копию себя с правами администратора.
func elevate(args string) error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}

	verb, _ := syscall.UTF16PtrFromString("runas")
	file, _ := syscall.UTF16PtrFromString(exe)
	params, _ := syscall.UTF16PtrFromString(args)
	dir, _ := syscall.UTF16PtrFromString(filepath.Dir(exe))

	const swHide = 0
	ret, _, _ := shellExecute.Call(0,
		uintptr(unsafe.Pointer(verb)),
		uintptr(unsafe.Pointer(file)),
		uintptr(unsafe.Pointer(params)),
		uintptr(unsafe.Pointer(dir)),
		swHide)

	// Всё, что больше 32, — успех; так устроен ShellExecute.
	switch {
	case ret > 32:
		return nil
	case ret == 5: // ERROR_ACCESS_DENIED
		return fmt.Errorf("без прав администратора туннель не поднять")
	default:
		return fmt.Errorf("не удалось запросить права администратора (код %d)", ret)
	}
}

// --- Всё, что ниже, выполняется в копии с правами администратора -----------

func runTunHelper() error {
	ip := ""
	if len(os.Args) > 2 {
		ip = os.Args[2]
	}
	if net.ParseIP(ip) == nil {
		return fmt.Errorf("не передан адрес сервера")
	}

	tunnel := filepath.Join(assetDir(), "tun2socks.exe")
	if _, err := os.Stat(tunnel); err != nil {
		return fmt.Errorf("рядом с программой нет tun2socks.exe")
	}

	cmd := exec.Command(tunnel,
		"--device", "wintun://"+tunDevice,
		"--proxy", "socks5://"+localAddr(socksPort),
		"--mtu", fmt.Sprint(tunMTU),
		"--loglevel", "warning",
	)
	hideWindow(cmd)
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("туннель не запустился: %w", err)
	}

	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()

	iface, err := waitDevice(20 * time.Second)
	if err != nil {
		_ = cmd.Process.Kill()
		return err
	}

	restore := applyRouting(iface, ip)
	defer restore()

	for {
		select {
		case err := <-done:
			return fmt.Errorf("туннель завершился: %v", err)

		case <-time.After(2 * time.Second):
			// Окно закрылось или режим выключили — убираем за собой.
			// Туннель без ядра означает машину без интернета, и
			// оставлять его нельзя ни при каком раскладе.
			if !tunnelWanted() {
				_ = cmd.Process.Kill()
				<-done
				return nil
			}
		}
	}
}

func waitDevice(limit time.Duration) (*net.Interface, error) {
	deadline := time.Now().Add(limit)
	for time.Now().Before(deadline) {
		if iface, err := net.InterfaceByName(tunDevice); err == nil {
			return iface, nil
		}
		time.Sleep(200 * time.Millisecond)
	}
	return nil, fmt.Errorf("адаптер %s не появился", tunDevice)
}

// applyRouting — завернуть трафик в туннель. Возвращает откат.
//
// Две половины вместо одного «по умолчанию»: маршруты 0.0.0.0/1 и
// 128.0.0.0/1 перекрывают любой настоящий маршрут по умолчанию, не
// трогая его самого. Когда туннель уходит, остаётся прежняя картина —
// её не из чего восстанавливать, она и не менялась.
func applyRouting(iface *net.Interface, serverIP string) func() {
	index := fmt.Sprint(iface.Index)

	cmds := [][]string{
		{"netsh", "interface", "ip", "set", "address",
			"name=" + tunDevice, "static", tunAddress4, tunNetmask},
		{"netsh", "interface", "ip", "set", "dns",
			"name=" + tunDevice, "static", "1.1.1.1", "primary"},
		{"netsh", "interface", "ipv4", "set", "subinterface", tunDevice,
			"mtu=" + fmt.Sprint(tunMTU), "store=active"},
	}

	undo := []func(){}
	for _, args := range cmds {
		if err := run(args); err != nil {
			logf("не вышло: %s (%v)", strings.Join(args, " "), err)
		}
	}

	// Сервер — мимо туннеля, через обычный шлюз. Без этого соединение
	// с ним уходило бы в туннель, который этим соединением и живёт.
	if gateway := defaultGateway(); gateway != "" {
		args := []string{"route", "add", serverIP, "mask", "255.255.255.255", gateway, "metric", "1"}
		if err := run(args); err != nil {
			logf("не вышло: %s (%v)", strings.Join(args, " "), err)
		} else {
			undo = append(undo, func() {
				_ = run([]string{"route", "delete", serverIP})
			})
		}
	} else {
		logf("не нашёл шлюз по умолчанию — трафик до сервера может пойти в туннель")
	}

	for _, half := range [][2]string{{"0.0.0.0", "128.0.0.0"}, {"128.0.0.0", "128.0.0.0"}} {
		network, mask := half[0], half[1]
		args := []string{"route", "add", network, "mask", mask, tunAddress4, "metric", "1", "if", index}
		if err := run(args); err != nil {
			logf("не вышло: %s (%v)", strings.Join(args, " "), err)
			continue
		}
		undo = append(undo, func() {
			_ = run([]string{"route", "delete", network, "mask", mask, tunAddress4})
		})
	}

	return func() {
		for i := len(undo) - 1; i >= 0; i-- {
			undo[i]()
		}
	}
}

// defaultGateway — шлюз, которым машина ходит в интернет сейчас.
//
// Берём из таблицы маршрутов: в ней первые два столбца строки «по
// умолчанию» — нули, а третий и есть шлюз. Цифры в таблице те же на
// любом языке системы, поэтому разбираем их, а не заголовки.
func defaultGateway() string {
	out, err := exec.Command("route", "print", "-4").Output()
	if err != nil {
		return ""
	}
	for _, line := range strings.Split(string(out), "\n") {
		fields := strings.Fields(line)
		if len(fields) >= 3 && fields[0] == "0.0.0.0" && fields[1] == "0.0.0.0" {
			if ip := net.ParseIP(fields[2]); ip != nil && !ip.IsUnspecified() {
				return fields[2]
			}
		}
	}
	return ""
}

func run(args []string) error {
	cmd := exec.Command(args[0], args[1:]...)
	hideWindow(cmd)
	out, err := cmd.CombinedOutput()
	if err != nil {
		text := strings.TrimSpace(string(out))
		if text == "" {
			return err
		}
		return fmt.Errorf("%s", text)
	}
	return nil
}
