package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"sync"
	"time"
)

// Чем ловим трафик.
//
// Способов два, и человек выбирает их тумблером в настройках.
//
// Системный прокси — тот, что живёт в настройках сети. Его слушают
// браузеры и всё, что умеет спрашивать систему, а программы, которые не
// умеют, ходят мимо. Прав администратора не нужно, поэтому так и
// работает по умолчанию.
//
// Режим VPN — сетевой интерфейс, в который система отдаёт пакеты всех
// программ без разбора. Для него нужен root, поэтому поднимает его
// служба, а окно только просит её запустить.

// Нужен ли туннель прямо сейчас. Отдельный флаг, а не вычисление по
// состоянию: при отключении кнопкой туннель надо снять раньше, чем
// остановится ядро, — иначе служба, которая спрашивает «я ещё нужна?»,
// получит «да» и останется, а мы будем ждать её ухода.
var (
	wantMu     sync.Mutex
	wantTunnel bool
)

func setWantTunnel(value bool) {
	wantMu.Lock()
	wantTunnel = value
	wantMu.Unlock()
}

func tunnelNeeded() bool {
	wantMu.Lock()
	defer wantMu.Unlock()
	return wantTunnel && coreRunning()
}

// catchTraffic — направить трафик в поднятое ядро.
func catchTraffic() error {
	if vpnMode() && tunSupported() {
		setWantTunnel(true)
		// Системный прокси в этом режиме только мешает: браузер ходил
		// бы в него, а всё остальное — в туннель, и правила применялись
		// бы дважды к одному и тому же.
		if err := disableSystemProxy(); err != nil {
			logf("прокси не выключился: %v", err)
		}
		return startTun()
	}

	setWantTunnel(false)
	stopTunIfActive()
	return enableSystemProxy()
}

// releaseTraffic — вернуть машине обычный ход вещей.
//
// Зовётся отовсюду, где соединение заканчивается: по кнопке, при
// выходе, при внезапной смерти ядра. Молчать об ошибках тут нельзя, но
// и останавливаться на первой — тоже: не вышло с одним, пробуем второе,
// иначе машина останется без интернета.
func releaseTraffic() {
	setWantTunnel(false)
	stopTunIfActive()
	if err := disableSystemProxy(); err != nil {
		logf("прокси не выключился: %v", err)
	}
}

// tunnelWanted — нужен ли туннель прямо сейчас.
//
// Спрашивает служба, которая его держит: в Windows она работает с
// правами администратора, и выключить её снаружи нечем — убить процесс
// с правами выше своих нельзя. Поэтому она сама раз в пару секунд
// заглядывает сюда и уходит, когда режим выключили, когда ядро
// остановилось или когда спрашивать стало не у кого.
func tunnelWanted() bool {
	client := &http.Client{Timeout: 3 * time.Second}
	resp, err := client.Get(fmt.Sprintf("http://127.0.0.1:%d/api/tunwanted", uiPort))
	if err != nil {
		return false
	}
	defer resp.Body.Close()

	var answer struct {
		Wanted bool `json:"wanted"`
	}
	if json.NewDecoder(resp.Body).Decode(&answer) != nil {
		return false
	}
	return answer.Wanted
}

func stopTunIfActive() {
	if !tunSupported() || !tunActive() {
		return
	}
	if err := stopTun(); err != nil {
		logf("туннель не выключился: %v", err)
		setNotice("Туннель не выключился: " + err.Error())
	}
}
