//go:build linux

package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"time"
)

// openWindow — открываем интерфейс отдельным окном.
//
// Сначала ищем браузер на движке Chromium: он умеет режим приложения
// (`--app=`), и окно получается без адресной строки и вкладок — как
// настоящее. Не нашёлся ни один — отдаём ссылку браузеру по умолчанию;
// в Ubuntu это обычно Firefox, и тогда интерфейс откроется вкладкой.
// Выглядит хуже, но работает ровно так же.
func openWindow() {
	url := fmt.Sprintf("http://127.0.0.1:%d/", uiPort)

	// В снапе — своё окно: из строгого снапа браузеров хозяина не видно
	// вовсе, искать их ниже бесполезно (см. window/vv-window.c).
	if snap := os.Getenv("SNAP"); snap != "" {
		window := filepath.Join(snap, "opt", brandName, "vv-window")
		if openAppWindow(window, []string{url}) {
			logf("окно открыто через vv-window")
			return
		}
	}

	// Первая очередь — движок Chromium: он умеет режим приложения, и
	// окно получается без адресной строки и вкладок, как настоящее.
	for _, name := range []string{
		"google-chrome", "google-chrome-stable", "chromium", "chromium-browser",
		"microsoft-edge", "microsoft-edge-stable", "brave-browser", "vivaldi-stable",
	} {
		if tryBrowser(name, url,
			"--app="+url,
			"--window-size=1060,720",
			"--user-data-dir="+filepath.Join(dataDir(), "window"),
			// По этому имени рабочий стол связывает окно со значком
			// программы: иначе в панели задач оно показывается
			// браузером, а не нами (см. StartupWMClass в vv.desktop).
			"--class="+brandName,
			// Без этих двух на свежем профиле показывается приветствие
			// и вопрос про браузер по умолчанию — окно появляется через
			// несколько секунд и не тем, чего ждали.
			"--no-first-run",
			"--no-default-browser-check",
		) {
			return
		}
	}

	// Вторая — Firefox: на обычной Ubuntu другого браузера и нет.
	// Режима приложения у него не бывает, но отдельное окно он открыть
	// умеет — это всё же лучше, чем вкладка среди чужих.
	for _, name := range []string{"firefox", "firefox-esr"} {
		if tryBrowser(name, url, "--new-window", url) {
			return
		}
	}

	// Третья — кто угодно. Вкладка в браузере по умолчанию: выглядит
	// хуже всего, работает ровно так же.
	logf("знакомого браузера не нашлось — открываю ссылкой")
	openURL(url)
}

func tryBrowser(name, url string, args ...string) bool {
	path, err := exec.LookPath(name)
	if err != nil {
		return false
	}
	if !openAppWindow(path, args) {
		return false
	}
	logf("окно открыто через %s", name)
	return true
}

// openAppWindow — запустить браузер окном и убедиться, что он не умер
// сразу.
//
// Одного Start мало: он отвечает «запустил» и в том случае, когда
// браузер тут же падает — например, снапу не дали каталог профиля или
// нет доступа к дисплею. Раньше на этом всё и заканчивалось: программа
// считала, что окно открыто, и человек оставался перед пустым экраном,
// а через три минуты она закрывалась сама, решив, что окно закрыли.
func openAppWindow(path string, args []string) bool {
	cmd := exec.Command(path, args...)
	if err := cmd.Start(); err != nil {
		logf("%s не запустился: %v", filepath.Base(path), err)
		return false
	}

	// Горутина заодно дожидается конца процесса — иначе он остался бы
	// висеть зомби до самого выхода.
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()

	select {
	case err := <-done:
		// Мгновенный выход без ошибки — это не беда, а передача ссылки
		// уже открытому браузеру: он нарисует окно сам. А вот ненулевой
		// код означает, что окна не будет, и надо пробовать дальше.
		if err == nil {
			return true
		}
		logf("%s закрылся сразу: %v", filepath.Base(path), err)
		return false
	case <-time.After(2 * time.Second):
		return true
	}
}
