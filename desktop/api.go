package main

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"runtime"
	"sort"
	"sync"
	"time"

	qrcode "github.com/skip2/go-qrcode"
)

// platformName — для строки «О приложении»: программа одна, систем две,
// и человеку, который присылает снимок экрана, полезно видеть, какая
// именно сборка у него стоит.
func platformName() string {
	switch runtime.GOOS {
	case "windows":
		return "Windows"
	case "linux":
		return "Linux"
	case "darwin":
		return "macOS"
	}
	return runtime.GOOS
}

// Что видит интерфейс. Одним куском: страница опрашивает это раз в
// секунду и перерисовывается целиком — состояний мало, и так проще
// держать картинку в согласии с действительностью.
type uiState struct {
	Authorized bool     `json:"authorized"`
	Connected  bool     `json:"connected"`
	Seconds    int      `json:"seconds"`
	Servers    []server `json:"servers"`
	Selected   string   `json:"selected"`
	DaysLeft   *int     `json:"days_left"`
	RuDirect   bool     `json:"ru_direct"`
	VPNMode    bool     `json:"vpn_mode"`
	// Умеет ли эта система в режим VPN. Пока это только Ubuntu: в
	// Windows для туннеля нужен свой драйвер адаптера.
	VPNSupported bool     `json:"vpn_supported"`
	Version      string   `json:"version"`
	Brand        string   `json:"brand"`
	Platform     string   `json:"platform"`
	Notice       string   `json:"notice"`
	Busy         string   `json:"busy"`
	Login        *loginUI `json:"login"`
	ProxyPorts   [2]int   `json:"proxy_ports"`

	// Вошли в аккаунт, но подписки у него нет: окно предлагает триал.
	Subscribed      bool   `json:"subscribed"`
	SubStatus       string `json:"sub_status"`
	TrialDays       int    `json:"trial_days"`
	AccountNumber   string `json:"account_number"`
	NumberConfirmed bool   `json:"number_confirmed"`
}

type loginUI struct {
	Code    string `json:"code"`
	QR      string `json:"qr"`
	Seconds int    `json:"seconds"`
}

var (
	uiMu    sync.Mutex
	notice  string
	busy    string
	login   *loginUI
	routing *routingAnswer
	// Что бэкенд в последний раз сказал о подписке аккаунта.
	subStatus string
	trialDays int
)

func setNotice(text string) {
	uiMu.Lock()
	notice = text
	uiMu.Unlock()
}

// setBusy — что делает программа прямо сейчас. Пустая строка — ничего.
// Раньше это был просто флаг, который интерфейс не показывал вовсе, и
// долгие действия выглядели зависанием.
func setBusy(what string) {
	uiMu.Lock()
	busy = what
	uiMu.Unlock()
}

func handleState(w http.ResponseWriter, r *http.Request) {
	// Окно живо — по этому же признаку программа понимает, что его
	// закрыли, если значка в трее завести не вышло.
	noteUIAlive()

	stateMu.Lock()
	servers := append([]server(nil), st.Servers...)
	selected := st.SelectedTag
	days := st.DaysLeft
	authorized := st.SessionToken != ""
	subscribed := st.SubscriptionURL != ""
	number, confirmed := st.AccountNumber, st.NumberConfirmed
	stateMu.Unlock()

	uiMu.Lock()
	out := uiState{
		Authorized:   authorized,
		Connected:    coreRunning(),
		Seconds:      connectedSeconds(),
		Servers:      servers,
		Selected:     selected,
		DaysLeft:     days,
		RuDirect:     ruDirect(),
		VPNMode:      vpnMode(),
		VPNSupported: tunSupported(),
		Version:      appVersion,
		Brand:        brandTitle,
		Platform:     platformName(),
		Notice:       notice,
		Busy:         busy,
		Login:        login,
		ProxyPorts:   [2]int{socksPort, httpPort},

		Subscribed:      subscribed,
		SubStatus:       subStatus,
		TrialDays:       trialDays,
		AccountNumber:   number,
		NumberConfirmed: confirmed,
	}
	uiMu.Unlock()

	writeJSON(w, out)
}

// handleLogin — показать код. Его сканирует устройство, уже вошедшее в
// аккаунт, или код вводят в веб-кабинете.
func handleLogin(w http.ResponseWriter, r *http.Request) {
	answer, err := requestJoinCode()
	if err != nil {
		logf("код входа не выдан: %v", err)
		setNotice("Не получилось получить код: " + err.Error())
		writeJSON(w, map[string]any{"ok": false, "error": err.Error()})
		return
	}

	payload := brandScheme + "://join?c=" + answer.Code
	png, err := qrcode.Encode(payload, qrcode.Medium, 512)
	if err != nil {
		writeJSON(w, map[string]any{"ok": false})
		return
	}

	uiMu.Lock()
	login = &loginUI{
		Code:    answer.Code,
		QR:      "data:image/png;base64," + base64.StdEncoding.EncodeToString(png),
		Seconds: answer.TTLSeconds,
	}
	uiMu.Unlock()

	go waitForJoin(answer.Code, answer.WaitSecret, answer.TTLSeconds)
	writeJSON(w, map[string]any{"ok": true})
}

// waitForJoin — ждём, пока код отсканируют. Уведомить нас бэкенд не
// может, поэтому спрашиваем сами, пока код жив.
func waitForJoin(code, secret string, ttl int) {
	deadline := time.Now().Add(time.Duration(ttl) * time.Second)

	for time.Now().Before(deadline) {
		time.Sleep(3 * time.Second)

		uiMu.Lock()
		current := login
		uiMu.Unlock()
		if current == nil || current.Code != code {
			return // человек запросил новый код
		}

		uiMu.Lock()
		if login != nil {
			login.Seconds = int(time.Until(deadline).Seconds())
		}
		uiMu.Unlock()

		answer, err := joinStatus(code, secret)
		if err != nil || answer.Token == "" {
			continue
		}

		uiMu.Lock()
		login = nil
		uiMu.Unlock()
		enterAccount(answer)
		return
	}

	uiMu.Lock()
	if login != nil && login.Code == code {
		login.Seconds = 0
	}
	uiMu.Unlock()
}

// enterAccount — вошли: запоминаем сессию и спрашиваем подписку.
func enterAccount(answer *sessionAnswer) {
	setSession(answer)
	logf("вход в аккаунт выполнен")
	if me, err := refreshSubscription(); err == nil {
		if applySubscription(me) {
			setNotice("Подписка получена")
		}
	} else {
		setNotice("Вошли, но подписку получить не вышло: " + err.Error())
	}
	reloadRouting()
}

// applySubscription — записываем подписку аккаунта и тянем серверы.
// false — подписки нет или серверы скачать не вышло; во втором случае
// об этом уже сказано человеку.
func applySubscription(answer *startAnswer) bool {
	uiMu.Lock()
	subStatus, trialDays = answer.Status, answer.TrialDays
	uiMu.Unlock()

	stateMu.Lock()
	if answer.AccountNumber != "" {
		st.AccountNumber = answer.AccountNumber
		st.NumberConfirmed = answer.Confirmed
	}
	if answer.SubscriptionURL == "" {
		// Ключа у аккаунта нет: пропал из панели или его не было вовсе.
		st.SubscriptionURL = ""
		st.Servers = nil
		st.SelectedTag = ""
		st.DaysLeft = nil
		saveLocked()
		stateMu.Unlock()
		return false
	}
	st.SubscriptionURL = answer.SubscriptionURL
	st.SubscriptionName = first(answer.SubscriptionName, brandSubName)
	st.Username = answer.Username
	st.DaysLeft = answer.DaysLeft
	stateMu.Unlock()

	ok := reloadServers()
	reloadRouting()
	save()
	return ok
}

func reloadServers() bool {
	stateMu.Lock()
	subURL := st.SubscriptionURL
	stateMu.Unlock()
	if subURL == "" {
		return false
	}

	servers, err := fetchServers(subURL)
	if err != nil {
		logf("подписка не скачалась: %v", err)
		setNotice("Не удалось загрузить список серверов: " + err.Error())
		return false
	}

	stateMu.Lock()
	// Замеры задержки переносим на обновлённый список: сервер тот же,
	// а список перечитан — иначе цифры пропадали бы при каждом обновлении.
	old := map[string]int{}
	for _, s := range st.Servers {
		old[s.Address] = s.Ping
	}
	for i := range servers {
		servers[i].Ping = old[servers[i].Address]
	}
	st.Servers = servers
	if st.SelectedTag == "" && len(servers) > 0 {
		st.SelectedTag = servers[0].Tag
	}
	stateMu.Unlock()
	save()
	return true
}

func reloadRouting() {
	answer, err := fetchRouting()
	if err != nil {
		return
	}
	uiMu.Lock()
	routing = answer
	uiMu.Unlock()
}

func currentRouting() *routingAnswer {
	uiMu.Lock()
	defer uiMu.Unlock()
	return routing
}

func selectedServer() (server, bool) {
	stateMu.Lock()
	defer stateMu.Unlock()
	for _, s := range st.Servers {
		if s.Tag == st.SelectedTag {
			return s, true
		}
	}
	if len(st.Servers) > 0 {
		return st.Servers[0], true
	}
	return server{}, false
}

func handleConnect(w http.ResponseWriter, r *http.Request) {
	s, ok := selectedServer()
	if !ok {
		setNotice("Нет ни одного сервера")
		writeJSON(w, map[string]any{"ok": false})
		return
	}

	// Режим VPN включён, а разрешение у снапа отобрали: подключаться
	// бессмысленно, а сказать, что делать, — можно.
	if vpnMode() {
		if p := tunPermission(); p != nil {
			writePermission(w, p)
			return
		}
	}

	setBusy("Подключаюсь…")
	defer setBusy("")

	if err := startCore(s, currentRouting()); err != nil {
		logf("подключение не вышло: %v", err)
		setNotice("Не подключилось: " + err.Error())
		writeJSON(w, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	if err := catchTraffic(); err != nil {
		stopCore()
		logf("трафик не перехвачен: %v", err)
		setNotice("Соединение есть, но трафик в него не пошёл: " + err.Error())
		writeJSON(w, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	logf("подключено: %s", s.Remark)
	setNotice("")
	writeJSON(w, map[string]any{"ok": true})
}

func handleDisconnect(w http.ResponseWriter, r *http.Request) {
	setBusy("Отключаюсь…")
	defer setBusy("")

	releaseTraffic()
	stopCore()
	logf("отключено по кнопке")
	setNotice("")
	writeJSON(w, map[string]any{"ok": true})
}

func handleSelect(w http.ResponseWriter, r *http.Request) {
	tag := r.URL.Query().Get("tag")

	stateMu.Lock()
	st.SelectedTag = tag
	stateMu.Unlock()
	save()

	// На ходу меняем сервер: иначе выбор пришлось бы подтверждать
	// переподключением вручную.
	if coreRunning() {
		if s, ok := selectedServer(); ok {
			_ = startCore(s, currentRouting())
		}
	}
	writeJSON(w, map[string]any{"ok": true})
}

// handlePing — «Пинг всех»: меряем параллельно, иначе десяток серверов
// проверялся бы полминуты.
func handlePing(w http.ResponseWriter, r *http.Request) {
	setBusy("Проверяю серверы…")
	defer setBusy("")

	stateMu.Lock()
	servers := append([]server(nil), st.Servers...)
	stateMu.Unlock()

	var wg sync.WaitGroup
	results := make([]int, len(servers))
	for i := range servers {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			results[i] = measurePing(servers[i])
		}(i)
	}
	wg.Wait()

	stateMu.Lock()
	for i := range st.Servers {
		for j := range servers {
			if st.Servers[i].Tag == servers[j].Tag {
				st.Servers[i].Ping = results[j]
			}
		}
	}
	stateMu.Unlock()
	save()

	writeJSON(w, map[string]any{"ok": true})
}

func handleSort(w http.ResponseWriter, r *http.Request) {
	by := r.URL.Query().Get("by")

	stateMu.Lock()
	sort.SliceStable(st.Servers, func(i, j int) bool {
		a, b := st.Servers[i], st.Servers[j]
		switch by {
		case "name":
			return a.Remark < b.Remark
		default: // по задержке: не отозвавшиеся уходят вниз
			ap, bp := a.Ping, b.Ping
			if ap <= 0 {
				ap = 1 << 30
			}
			if bp <= 0 {
				bp = 1 << 30
			}
			return ap < bp
		}
	})
	stateMu.Unlock()
	save()

	writeJSON(w, map[string]any{"ok": true})
}

func handleRuDirect(w http.ResponseWriter, r *http.Request) {
	value := r.URL.Query().Get("on") == "1"

	stateMu.Lock()
	st.RuDirect = &value
	stateMu.Unlock()
	save()

	// Правила живут в конфиге ядра, поэтому на ходу их меняет только
	// перезапуск соединения.
	if coreRunning() {
		if s, ok := selectedServer(); ok {
			_ = startCore(s, currentRouting())
		}
	}
	writeJSON(w, map[string]any{"ok": true})
}

// handleTunWanted — ответ службе туннеля: нужна ли она ещё.
//
// Спрашивает она сама, раз в пару секунд. Так устроено потому, что в
// Windows служба работает с правами администратора, и остановить её
// снаружи нечем. «Нет» она получает, когда режим выключили или когда
// ядро больше не работает; а если окна не стало вовсе, запрос не
// дойдёт — и это тоже ответ «нет».
//
// Заодно отдаём адрес сервера: службе он нужен, чтобы увести соединение
// ядра мимо туннеля, а самой ей разрешать имя уже поздно — к её запуску
// весь трафик машины заперт в туннеле. Здесь адрес давно разрешён: с
// ним же собирался конфиг ядра.
func handleTunWanted(w http.ResponseWriter, r *http.Request) {
	address := ""
	if s, ok := selectedServer(); ok {
		address = knownServerAddress(s.Address)
	}
	writeJSON(w, map[string]any{"wanted": tunnelNeeded(), "server": address})
}

// handleVPNMode — переключить режим: туннель или системный прокси.
//
// На ходу это не переключается: метка на пакетах ядра живёт в его
// конфиге, а он читается только при запуске. Поэтому при включённом
// соединении перезапускаем ядро и заново берём трафик — уже другим
// способом. Если новый способ не задался (например, человек отказался
// вводить пароль), честно откатываемся на прежний, а не оставляем
// машину без интернета.
func handleVPNMode(w http.ResponseWriter, r *http.Request) {
	value := r.URL.Query().Get("on") == "1"

	if value && !tunSupported() {
		writeJSON(w, map[string]any{"ok": false, "error": "режим VPN пока только в Ubuntu"})
		return
	}
	// До всякого перезапуска ядра: без разрешения режим всё равно не
	// включится, а соединение моргнуло бы зря.
	if value {
		if p := tunPermission(); p != nil {
			writePermission(w, p)
			return
		}
	}

	was := vpnMode()
	stateMu.Lock()
	st.VPNMode = &value
	stateMu.Unlock()
	save()

	if !coreRunning() {
		writeJSON(w, map[string]any{"ok": true})
		return
	}

	setBusy("Переключаю режим…")
	defer setBusy("")

	releaseTraffic()

	s, ok := selectedServer()
	if !ok {
		writeJSON(w, map[string]any{"ok": true})
		return
	}
	if err := startCore(s, currentRouting()); err != nil {
		logf("перезапуск для смены режима не вышел: %v", err)
		setNotice("Не вышло переключить режим: " + err.Error())
		writeJSON(w, map[string]any{"ok": false, "error": err.Error()})
		return
	}

	if err := catchTraffic(); err != nil {
		logf("режим не включился: %v", err)

		stateMu.Lock()
		st.VPNMode = &was
		stateMu.Unlock()
		save()

		// Возвращаем прежний способ: ядро работает, и оставлять его без
		// перехвата — значит молча лишить человека соединения.
		if err := startCore(s, currentRouting()); err == nil {
			_ = catchTraffic()
		}
		setNotice("Режим VPN не включился: " + err.Error())
		writeJSON(w, map[string]any{"ok": false, "error": err.Error()})
		return
	}

	setNotice("")
	writeJSON(w, map[string]any{"ok": true})
}

// permissionNeeded — режиму VPN не хватает разрешения, которое человек
// даёт сам, одной командой. Окно показывает её отдельной плашкой с
// кнопкой «Скопировать», а не строчкой в общем потоке сообщений.
type permissionNeeded struct {
	command string
}

func (p *permissionNeeded) Error() string {
	return "режиму VPN нужно разрешение: " + p.command
}

func writePermission(w http.ResponseWriter, p *permissionNeeded) {
	logf("режиму VPN не хватает разрешения: %s", p.command)
	writeJSON(w, map[string]any{"ok": false, "permission": p.command})
}

// handleSource — открыть исходный код в браузере.
func handleSource(w http.ResponseWriter, r *http.Request) {
	openURL(brandSource)
	writeJSON(w, map[string]any{"ok": true})
}

func handleRefresh(w http.ResponseWriter, r *http.Request) {
	setBusy("Обновляю подписку…")
	defer setBusy("")

	if answer, err := refreshSubscription(); err == nil {
		if applySubscription(answer) {
			setNotice("Подписка обновлена")
		}
	} else if errors.Is(err, errUnauthorized) {
		setNotice(err.Error())
	} else {
		reloadServers()
		reloadRouting()
		setNotice("Список серверов обновлён")
	}
	writeJSON(w, map[string]any{"ok": true})
}

// --- Подписка ----------------------------------------------------------

// Ссылка на оплату, какой её прислал бэкенд. Храним, чтобы открывать
// браузером только её: иначе страница в окне превращается в способ
// открыть на машине что угодно.
var payURL struct {
	sync.Mutex
	url string
}

func handleRenew(w http.ResponseWriter, r *http.Request) {
	answer, err := fetchRenew()
	if err != nil {
		logf("экран подписки не собрался: %v", err)
		writeJSON(w, map[string]any{"ok": false, "error": err.Error()})
		return
	}

	payURL.Lock()
	payURL.url = answer.PayURL
	payURL.Unlock()

	// Экран открыт — значит, про решение мы узнаём прямо сейчас, и
	// сторожу повторять эту новость не нужно.
	noteRenewRequest(answer.Request)

	writeJSON(w, map[string]any{"ok": true, "renew": answer})
}

// handleReceipt — чек об оплате уходит тем, кто его разбирает. Дальше
// заявка живёт сама: админ её подтвердит, а если промолчит — она
// применится по времени.
func handleReceipt(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Tier   *int   `json:"tier"`
		Amount *int   `json:"amount"`
		Image  string `json:"image"`
		Name   string `json:"name"`
	}
	// Файл приходит base64, поэтому потолок с запасом к тем полусотне
	// мегабайт, что принимает бэкенд.
	if err := json.NewDecoder(io.LimitReader(r.Body, 70<<20)).Decode(&in); err != nil {
		writeJSON(w, map[string]any{"ok": false, "error": "чек не прочитался"})
		return
	}

	setBusy("Отправляю чек…")
	defer setBusy("")

	answer, err := sendReceipt(in.Tier, in.Amount, in.Image, in.Name)
	if err != nil {
		logf("чек не ушёл: %v", err)
		writeJSON(w, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	if answer.Status != "ok" {
		logf("чек не приняли: %s", first(answer.Message, answer.Status))
		writeJSON(w, map[string]any{"ok": false, "error": first(answer.Message, answer.Status)})
		return
	}

	// Запоминаем заявку: с этого момента за ней следит сторож, и
	// решение догонит человека, даже если он закроет окно.
	noteRenewRequest(answer.Request)

	logf("чек отправлен, ждём решения")
	setNotice("Чек отправлен")
	writeJSON(w, map[string]any{"ok": true, "renew": answer})
}

// handlePay — открыть оплату в браузере по умолчанию. Из окна-приложения
// ссылка наружу не открывается: это отдельное окно без адресной строки,
// и платить человеку удобнее в своём обычном браузере.
func handlePay(w http.ResponseWriter, r *http.Request) {
	payURL.Lock()
	url := payURL.url
	payURL.Unlock()

	if url == "" {
		writeJSON(w, map[string]any{"ok": false, "error": "Ссылки на оплату нет"})
		return
	}
	openURL(url)
	writeJSON(w, map[string]any{"ok": true})
}

// handleLog — последние строки журнала, чтобы человеку было что
// показать, когда «что-то не работает».
func handleLog(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, map[string]any{"lines": recentLog(), "path": logPath()})
}

func handleQuit(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, map[string]any{"ok": true})
	go func() {
		time.Sleep(300 * time.Millisecond)
		shutdown()
	}()
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	_ = json.NewEncoder(w).Encode(v)
}
