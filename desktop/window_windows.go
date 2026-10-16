//go:build windows

package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

// openWindow — открываем интерфейс отдельным окном.
//
// Сначала пробуем Edge в режиме приложения: он есть в любой Windows 10
// и 11, и окно получается без адресной строки и вкладок. Не нашёлся —
// отдаём ссылку браузеру по умолчанию.
func openWindow() {
	url := fmt.Sprintf("http://127.0.0.1:%d/", uiPort)

	if edge := findEdge(); edge != "" {
		cmd := exec.Command(edge,
			"--app="+url,
			"--window-size=1060,720",
			"--user-data-dir="+filepath.Join(dataDir(), "window"),
			// Без этих двух Edge на свежем профиле показывает приветствие
			// и спрашивает про браузер по умолчанию — окно появляется
			// через несколько секунд и не тем, чего ждали.
			"--no-first-run",
			"--no-default-browser-check",
		)
		hideWindow(cmd)
		if err := cmd.Start(); err == nil {
			return
		} else {
			logf("Edge не запустился: %v", err)
		}
	}

	// Запасной путь: отдаём ссылку системе. Но из-под администратора
	// ShellExecute передаёт те же права и браузеру, а браузеры такому
	// не рады. Проводник всегда работает с обычными правами, и
	// открытая через него ссылка достаётся браузеру тоже обычной.
	if elevated() {
		if err := exec.Command("explorer.exe", url).Start(); err == nil {
			return
		}
	}
	openURL(url)
}

// elevated — запущены ли мы с правами администратора. Ярлык, который
// ставит установщик, просит их сразу, поэтому обычно да.
func elevated() bool {
	return windows.Token(windows.GetCurrentProcessToken()).IsElevated()
}

// findEdge — путь к Edge: сперва там, куда его ставят обычно, затем по
// записи в реестре. Установка бывает и пользовательской, тогда в
// Program Files его нет вовсе.
func findEdge() string {
	candidates := []string{
		filepath.Join(os.Getenv("ProgramFiles(x86)"), "Microsoft", "Edge", "Application", "msedge.exe"),
		filepath.Join(os.Getenv("ProgramFiles"), "Microsoft", "Edge", "Application", "msedge.exe"),
	}
	for _, path := range candidates {
		if info, err := os.Stat(path); err == nil && !info.IsDir() {
			return path
		}
	}

	const appPaths = `Software\Microsoft\Windows\CurrentVersion\App Paths\msedge.exe`
	for _, root := range []registry.Key{registry.CURRENT_USER, registry.LOCAL_MACHINE} {
		key, err := registry.OpenKey(root, appPaths, registry.QUERY_VALUE)
		if err != nil {
			continue
		}
		path, _, err := key.GetStringValue("")
		key.Close()
		if err == nil {
			if info, err := os.Stat(path); err == nil && !info.IsDir() {
				return path
			}
		}
	}
	return ""
}
