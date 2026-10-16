//go:build windows

package main

import (
	"fmt"
	"os/exec"
	"strings"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

const coreBinaryName = "xray.exe"

// Системный прокси Windows.
//
// Туннеля на уровне сетевого адаптера мы не поднимаем: для него нужен
// драйвер и права администратора, а программа должна запускаться
// двойным щелчком. Вместо этого включаем системный прокси — так ходят
// браузеры и всё, что уважает общие настройки Windows.

const proxyKey = `Software\Microsoft\Windows\CurrentVersion\Internet Settings`

// Локальное мимо прокси. Диапазон 172.16–172.31 перечисляем целиком:
// маской `172.16.*` накрывалась только первая его шестнадцатая часть.
const proxyBypass = "<local>;localhost;127.*;10.*;192.168.*;" +
	"172.16.*;172.17.*;172.18.*;172.19.*;172.20.*;172.21.*;172.22.*;172.23.*;" +
	"172.24.*;172.25.*;172.26.*;172.27.*;172.28.*;172.29.*;172.30.*;172.31.*"

func ourProxyAddress() string { return fmt.Sprintf("127.0.0.1:%d", httpPort) }

func enableSystemProxy() error {
	key, err := registry.OpenKey(registry.CURRENT_USER, proxyKey, registry.QUERY_VALUE|registry.SET_VALUE)
	if err != nil {
		return err
	}
	defer key.Close()

	// Чужие настройки запоминаем, чтобы вернуть их при отключении: у
	// человека мог быть свой прокси, и затереть его насовсем — грубо.
	saveForeignProxy(key)

	if err := key.SetStringValue("ProxyServer", ourProxyAddress()); err != nil {
		return err
	}
	if err := key.SetStringValue("ProxyOverride", proxyBypass); err != nil {
		return err
	}
	if err := key.SetDWordValue("ProxyEnable", 1); err != nil {
		return err
	}
	refreshProxy()
	return nil
}

func disableSystemProxy() error {
	key, err := registry.OpenKey(registry.CURRENT_USER, proxyKey, registry.QUERY_VALUE|registry.SET_VALUE)
	if err != nil {
		return err
	}
	defer key.Close()

	// Выключаем только своё: если прокси уже чужой, значит, его успели
	// поменять без нас, и трогать его нельзя.
	if server, _, err := key.GetStringValue("ProxyServer"); err == nil && server != ourProxyAddress() {
		if enabled, _, err := key.GetIntegerValue("ProxyEnable"); err == nil && enabled == 1 {
			return nil
		}
	}

	restored := restoreForeignProxy(key)
	if !restored {
		if err := key.SetDWordValue("ProxyEnable", 0); err != nil {
			return err
		}
	}
	refreshProxy()
	return nil
}

// healStaleProxy — снять прокси, оставшийся с прошлого запуска.
//
// Программу могли снять из диспетчера задач или удалить антивирусом —
// тогда в реестре остаётся включённый прокси на наш порт, где уже никто
// не слушает, и на машине пропадает интернет целиком.
func healStaleProxy() (bool, error) {
	key, err := registry.OpenKey(registry.CURRENT_USER, proxyKey, registry.QUERY_VALUE|registry.SET_VALUE)
	if err != nil {
		return false, err
	}
	defer key.Close()

	enabled, _, err := key.GetIntegerValue("ProxyEnable")
	if err != nil || enabled != 1 {
		return false, nil
	}
	server, _, err := key.GetStringValue("ProxyServer")
	if err != nil || server != ourProxyAddress() {
		return false, nil
	}

	if !restoreForeignProxy(key) {
		if err := key.SetDWordValue("ProxyEnable", 0); err != nil {
			return false, err
		}
	}
	refreshProxy()
	return true, nil
}

// Прежние настройки держим в своём файле состояния, а не в реестре: так
// они переживают и перезапуск, и падение.
func saveForeignProxy(key registry.Key) {
	server, _, err := key.GetStringValue("ProxyServer")
	if err == nil && server == ourProxyAddress() {
		return // уже наше, запоминать нечего
	}
	enabled, _, _ := key.GetIntegerValue("ProxyEnable")
	override, _, _ := key.GetStringValue("ProxyOverride")

	stateMu.Lock()
	st.PrevProxy = &savedProxy{Enabled: enabled == 1, Server: server, Override: override}
	saveLocked()
	stateMu.Unlock()
}

func restoreForeignProxy(key registry.Key) bool {
	stateMu.Lock()
	saved := st.PrevProxy
	st.PrevProxy = nil
	saveLocked()
	stateMu.Unlock()

	if saved == nil || !saved.Enabled || saved.Server == "" {
		return false
	}
	_ = key.SetStringValue("ProxyServer", saved.Server)
	if saved.Override != "" {
		_ = key.SetStringValue("ProxyOverride", saved.Override)
	}
	return key.SetDWordValue("ProxyEnable", 1) == nil
}

// refreshProxy — без этого Windows замечает смену настроек не сразу, а
// когда сама решит: уже открытые программы продолжают ходить напрямую.
func refreshProxy() {
	wininet, err := syscall.LoadDLL("wininet.dll")
	if err != nil {
		return
	}
	defer wininet.Release()

	proc, err := wininet.FindProc("InternetSetOptionW")
	if err != nil {
		return
	}
	const (
		settingsChanged = 39
		refresh         = 37
	)
	_, _, _ = proc.Call(0, settingsChanged, 0, 0)
	_, _, _ = proc.Call(0, refresh, 0, 0)
}

// hideWindow — ядро запускается без собственного окна консоли.
func hideWindow(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
}

var (
	shell32      = windows.NewLazySystemDLL("shell32.dll")
	user32       = windows.NewLazySystemDLL("user32.dll")
	shellExecute = shell32.NewProc("ShellExecuteW")
	messageBox   = user32.NewProc("MessageBoxW")
)

// openURL и showError — своими вызовами, а не через mshta и rundll32.
//
// Те две программы входят в Windows, но ими же пользуются вредоносы,
// чтобы не тащить свой код, — и антивирус смотрит на их запуск очень
// внимательно. Вызвать то же самое напрямую и проще, и не вызывает
// вопросов.
func openURL(url string) {
	verb, _ := syscall.UTF16PtrFromString("open")
	target, _ := syscall.UTF16PtrFromString(url)
	const showNormal = 1
	_, _, _ = shellExecute.Call(0, uintptr(unsafe.Pointer(verb)), uintptr(unsafe.Pointer(target)), 0, 0, showNormal)
}

func showError(text string) {
	body, _ := syscall.UTF16PtrFromString(strings.TrimSpace(text))
	title, _ := syscall.UTF16PtrFromString(brandTitle)
	const iconError = 0x00000010
	_, _, _ = messageBox.Call(0, uintptr(unsafe.Pointer(body)), uintptr(unsafe.Pointer(title)), iconError)
}
