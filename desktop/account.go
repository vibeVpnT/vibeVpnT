package main

import (
	"encoding/json"
	"io"
	"net/http"
	"strings"
)

// Вход в аккаунт из окна: по номеру, новым аккаунтом или по коду
// (handleLogin). Подробно об аккаунтах — ACCOUNTS.md в бэкенде.

// migrateLegacy — старая версия называла себя DeviceID. Если у неё была
// подписка, меняем ID на сессию в аккаунте этой подписки — человеку
// ничего делать не нужно. Без подписки менять нечего: просто забываем.
func migrateLegacy() {
	stateMu.Lock()
	deviceID, token, subURL := st.DeviceID, st.SessionToken, st.SubscriptionURL
	stateMu.Unlock()
	if deviceID == "" || token != "" {
		return
	}

	if subURL == "" {
		stateMu.Lock()
		st.DeviceID = ""
		saveLocked()
		stateMu.Unlock()
		return
	}

	answer, err := migrateSession(deviceID)
	if err != nil {
		// Сети нет или бэкенд ещё старый — попробуем при следующем
		// запуске; подписка тем временем работает как работала.
		logf("переход на аккаунт отложен: %v", err)
		return
	}
	setSession(answer)
	logf("старый ID заменён сессией аккаунта")
}

// readNumber — номер из тела запроса окна: цифры, без пробелов.
func readNumber(r *http.Request) string {
	var in struct {
		Number string `json:"number"`
	}
	_ = json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&in)
	var digits strings.Builder
	for _, ch := range in.Number {
		if ch >= '0' && ch <= '9' {
			digits.WriteRune(ch)
		}
	}
	return digits.String()
}

func handleAccountLogin(w http.ResponseWriter, r *http.Request) {
	number := readNumber(r)
	if len(number) != 16 {
		writeJSON(w, map[string]any{"ok": false, "error": "В номере аккаунта 16 цифр"})
		return
	}

	setBusy("Вхожу…")
	defer setBusy("")

	answer, err := loginByNumber(number)
	if err != nil {
		writeJSON(w, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	// Номер человек ввёл сам — значит, он у него сохранён.
	answer.Confirmed = true
	enterAccount(answer)
	writeJSON(w, map[string]any{"ok": true})
}

func handleAccountCreate(w http.ResponseWriter, r *http.Request) {
	setBusy("Создаю аккаунт…")
	defer setBusy("")

	answer, err := createAccount()
	if err != nil {
		writeJSON(w, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	enterAccount(answer)
	writeJSON(w, map[string]any{"ok": true})
}

// handleAccountConfirm — «Я сохранил номер».
func handleAccountConfirm(w http.ResponseWriter, r *http.Request) {
	_ = confirmNumber()
	stateMu.Lock()
	st.NumberConfirmed = true
	saveLocked()
	stateMu.Unlock()
	writeJSON(w, map[string]any{"ok": true})
}

func handleAccountTrial(w http.ResponseWriter, r *http.Request) {
	setBusy("Получаю пробный период…")
	defer setBusy("")

	answer, err := claimTrial()
	if err != nil {
		writeJSON(w, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	if !applySubscription(answer) {
		writeJSON(w, map[string]any{"ok": false, "error": first(answer.Message, trialRefusal(answer.Status))})
		return
	}
	setNotice("Пробный период включён")
	writeJSON(w, map[string]any{"ok": true})
}

func trialRefusal(status string) string {
	switch status {
	case "rate_limited":
		return "С этого адреса пробный период уже получали"
	case "unavailable":
		return "Выдача подписок сейчас закрыта"
	}
	return "Пробный период не выдан"
}

// handleAccountLogout — выйти из аккаунта на этом компьютере.
func handleAccountLogout(w http.ResponseWriter, r *http.Request) {
	setBusy("Выхожу…")
	defer setBusy("")

	releaseTraffic()
	stopCore()
	logoutSession()
	dropSession()
	logf("выход из аккаунта")
	writeJSON(w, map[string]any{"ok": true})
}
