package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
)

// Состояние, которое переживает перезапуск.
//
// Регистрации в приложении нет вовсе: единственное, чем оно себя
// называет, — DeviceID. Он же подставляется в запросы к бэкенду вместо
// Android ID телефона, поэтому и формат тот же: шестнадцать
// шестнадцатеричных знаков.
type state struct {
	DeviceID         string   `json:"device_id"`
	SubscriptionURL  string   `json:"subscription_url"`
	SubscriptionName string   `json:"subscription_name"`
	Servers          []server `json:"servers"`
	SelectedTag      string   `json:"selected"`
	RuDirect         *bool    `json:"ru_direct"`
	// Режим VPN: весь трафик машины через туннель, а не только то, что
	// умеет ходить в системный прокси. По умолчанию выключен — для него
	// нужен пароль администратора, и спрашивать его при первом
	// знакомстве с программой незачем.
	VPNMode        *bool           `json:"vpn_mode,omitempty"`
	RoutingVersion int             `json:"routing_version"`
	Routing        json.RawMessage `json:"routing"`
	DaysLeft       *int            `json:"days_left"`
	Username       string          `json:"username"`

	// Последняя заявка на продление. Храним у себя, чтобы заметить
	// решение, принятое пока программа была закрыта: бэкенд о таком не
	// сообщает, спрашивать приходится самим.
	RenewRequestID     int    `json:"renew_request_id,omitempty"`
	RenewRequestStatus string `json:"renew_request_status,omitempty"`

	// Настройки прокси, которые были до нас. Храним у себя, а не в
	// реестре: так они переживают и перезапуск, и падение программы.
	PrevProxy *savedProxy `json:"prev_proxy,omitempty"`
	// То же для линукса, где настройки прокси живут в gsettings.
	// Значения держим ровно такими, какими их отдала сама gsettings, —
	// тогда вернуть их можно слово в слово, не разбирая типы.
	PrevGnomeProxy map[string]string `json:"prev_gnome_proxy,omitempty"`
}

type savedProxy struct {
	Enabled  bool   `json:"enabled"`
	Server   string `json:"server"`
	Override string `json:"override"`
}

var (
	stateMu sync.Mutex
	st      state
)

// ruDirect — «российские сервисы напрямую». По умолчанию включено: банки,
// госуслуги и доставка не пускают адреса дата-центров, и через сервер
// попросту не открываются.
func ruDirect() bool {
	stateMu.Lock()
	defer stateMu.Unlock()
	if st.RuDirect == nil {
		return true
	}
	return *st.RuDirect
}

// vpnMode — включён ли режим «весь трафик через туннель».
func vpnMode() bool {
	stateMu.Lock()
	defer stateMu.Unlock()
	return st.VPNMode != nil && *st.VPNMode
}

func dataDir() string {
	base, err := os.UserConfigDir()
	if err != nil || base == "" {
		base, _ = os.UserHomeDir()
	}
	dir := filepath.Join(base, brandName)
	_ = os.MkdirAll(dir, 0o700)
	return dir
}

func statePath() string { return filepath.Join(dataDir(), "state.json") }

func loadState() {
	stateMu.Lock()
	defer stateMu.Unlock()

	raw, err := os.ReadFile(statePath())
	if err == nil {
		_ = json.Unmarshal(raw, &st)
	}
	if len(st.DeviceID) != 16 {
		st.DeviceID = newDeviceID()
		saveLocked()
	}
}

func saveLocked() {
	raw, err := json.MarshalIndent(&st, "", "  ")
	if err != nil {
		return
	}
	tmp := statePath() + ".tmp"
	if os.WriteFile(tmp, raw, 0o600) == nil {
		_ = os.Rename(tmp, statePath())
	}
}

func save() {
	stateMu.Lock()
	defer stateMu.Unlock()
	saveLocked()
}

// newDeviceID — случайный, а не из железа: привязываться к серийникам
// незачем, а потерять файл состояния — значит просто войти заново.
func newDeviceID() string {
	buf := make([]byte, 8)
	if _, err := rand.Read(buf); err != nil {
		return "0000000000000000"
	}
	return hex.EncodeToString(buf)
}
