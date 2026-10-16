//go:build !windows && !linux

package main

import (
	"fmt"
	"os/exec"
)

// Всё, что не Windows и не линукс: вся логика — подписка, правила,
// запуск ядра, вход по коду — работает, а системный прокси трогать
// нечем. Нужно затем, чтобы код собирался где угодно; раздаём мы
// Windows и Ubuntu.

const coreBinaryName = "xray"

func enableSystemProxy() error      { return nil }
func disableSystemProxy() error     { return nil }
func healStaleProxy() (bool, error) { return false, nil }
func hideWindow(cmd *exec.Cmd)      {}
func showError(text string)         { logf("ошибка: %s", text) }
func notify(title, text string)     { logf("%s: %s", title, text) }
func openURL(url string)            { _ = exec.Command("xdg-open", url).Start() }

func openWindow() { openURL(fmt.Sprintf("http://127.0.0.1:%d/", uiPort)) }
