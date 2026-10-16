package main

import (
	"encoding/base64"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// Сервер из подписки.
//
// Храним разобранным, а не ссылкой: по этим же полям считается задержка
// (подключением прямо к адресу, мимо VPN) и собирается конфиг ядра.
type server struct {
	Tag      string `json:"tag"`
	Remark   string `json:"remark"`
	Protocol string `json:"protocol"`
	Address  string `json:"address"`
	Port     int    `json:"port"`
	ID       string `json:"id"`
	Network  string `json:"network"`
	Path     string `json:"path"`
	Host     string `json:"host"`
	Security string `json:"security"`
	SNI      string `json:"sni"`
	Flow     string `json:"flow"`
	Insecure bool   `json:"insecure"`

	// Задержка до сервера, мс. 0 — не мерили, -1 — не отозвался.
	Ping int `json:"ping"`
}

// fetchServers — скачивает подписку и разбирает её.
//
// Заголовок User-Agent выбирает формат на стороне панели: с нашим она
// отдаёт список ссылок в base64, а не json для v2rayNG.
func fetchServers(subURL string) ([]server, error) {
	// Повторяем: первое соединение иногда срывается на ровном месте —
	// у провайдера, в дороге, на фильтре. Сдаться с первой попытки
	// значило бы показать человеку пустой список серверов.
	var lastErr error
	for attempt := 0; attempt < 3; attempt++ {
		if attempt > 0 {
			time.Sleep(1500 * time.Millisecond)
		}

		servers, err := tryFetchServers(subURL)
		if err == nil {
			return servers, nil
		}
		lastErr = err
	}
	return nil, lastErr
}

func tryFetchServers(subURL string) ([]server, error) {
	req, err := http.NewRequest(http.MethodGet, subURL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", brandSubAgent)

	resp, err := httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	raw, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return nil, err
	}
	return parseSubscription(string(raw))
}

func parseSubscription(body string) ([]server, error) {
	text := strings.TrimSpace(body)

	// Панель отдаёт список в base64; если нет — значит уже обычный текст.
	if !strings.Contains(text, "://") {
		for _, enc := range []*base64.Encoding{base64.StdEncoding, base64.RawStdEncoding, base64.URLEncoding, base64.RawURLEncoding} {
			if decoded, err := enc.DecodeString(strings.ReplaceAll(text, "\n", "")); err == nil {
				text = string(decoded)
				break
			}
		}
	}

	var out []server
	for _, line := range strings.Split(text, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		if s, err := parseLink(line); err == nil {
			s.Tag = fmt.Sprintf("srv-%d", len(out))
			out = append(out, *s)
		}
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("в подписке нет ни одного сервера")
	}
	return out, nil
}

func parseLink(link string) (*server, error) {
	u, err := url.Parse(link)
	if err != nil {
		return nil, err
	}

	scheme := strings.ToLower(u.Scheme)
	if scheme != "vless" && scheme != "trojan" {
		return nil, fmt.Errorf("протокол %q не поддерживается", scheme)
	}

	port, err := strconv.Atoi(u.Port())
	if err != nil {
		return nil, fmt.Errorf("нет порта")
	}

	q := u.Query()
	s := &server{
		Protocol: scheme,
		Address:  u.Hostname(),
		Port:     port,
		ID:       u.User.Username(),
		Network:  first(q.Get("type"), "tcp"),
		Path:     q.Get("path"),
		Host:     q.Get("host"),
		Security: first(q.Get("security"), "none"),
		SNI:      q.Get("sni"),
		Flow:     q.Get("flow"),
		Insecure: q.Get("allowInsecure") == "1" || strings.EqualFold(q.Get("allowinsecure"), "true"),
		Remark:   trimRemark(first(u.Fragment, u.Hostname())),
	}
	if s.SNI == "" {
		s.SNI = s.Host
	}
	if s.SNI == "" {
		s.SNI = s.Address
	}
	return s, nil
}

// Логин ключа в скобках: «🇫🇮 (логин_ключа) [vless - ws]».
//
// Панель подставляет его в название каждого сервера. Человеку он ничего
// не говорит, а строку раздувает так, что флаг и протокол перестают
// помещаться. Убираем только скобки без пробелов внутри — у осмысленных
// пояснений вроде «(резерв)» пробел есть, и их не трогаем. Правило то
// же, что в мобильной версии: списки должны выглядеть одинаково.
var keyInName = regexp.MustCompile(`\s*\([A-Za-z0-9_.-]{6,}\)`)
var manySpaces = regexp.MustCompile(`\s{2,}`)

func trimRemark(remark string) string {
	out := keyInName.ReplaceAllString(remark, "")
	return strings.TrimSpace(manySpaces.ReplaceAllString(out, " "))
}

func first(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}

// measurePing — задержка до сервера обычным TCP-подключением.
//
// Меряем именно так, в обход VPN: человеку нужно знать, насколько далеко
// сам сервер, а не сколько идёт ответ через уже поднятый туннель.
func measurePing(s server) int {
	address := net.JoinHostPort(s.Address, strconv.Itoa(s.Port))
	start := nowMillis()
	conn, err := net.DialTimeout("tcp", address, 4000*msDuration)
	if err != nil {
		return -1
	}
	_ = conn.Close()
	elapsed := int(nowMillis() - start)
	if elapsed < 1 {
		elapsed = 1
	}
	return elapsed
}
