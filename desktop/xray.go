package main

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"time"
)

const msDuration = time.Millisecond

func nowMillis() int64 { return time.Now().UnixNano() / int64(time.Millisecond) }

// Порты локальных входов. Берём высокие и нестандартные, чтобы не
// столкнуться с другими клиентами, которые обычно живут на 10808/10809.
const (
	socksPort = 10831
	httpPort  = 10832
)

var (
	coreMu    sync.Mutex
	coreCmd   *process
	coreSince int64
	// Остановку затеяли мы сами — об обрыве сообщать не нужно.
	coreStopping bool
)

func configPath() string { return filepath.Join(dataDir(), "config.json") }

// startCore — поднимает ядро на выбранном сервере.
//
// Возвращает ошибку, если ядро не поднялось. Проверяем не по таймеру, а
// по тому, отвечает ли локальный вход: прежняя проверка смотрела на
// cmd.ProcessState и не срабатывала никогда — поле заполняется только
// после Wait, который вызывался строкой ниже.
func startCore(s server, routing *routingAnswer) error {
	stopCore()

	conf, err := buildConfig(s, routing)
	if err != nil {
		return fmt.Errorf("конфигурация не собралась: %w", err)
	}
	if err := os.WriteFile(configPath(), conf, 0o600); err != nil {
		return fmt.Errorf("не записать конфигурацию: %w", err)
	}

	core := corePath()
	if _, err := os.Stat(core); err != nil {
		return fmt.Errorf("рядом с программой нет %s", filepath.Base(core))
	}

	proc, err := startProcess(core, configPath(), assetDir(), filepath.Join(dataDir(), "core.log"))
	if err != nil {
		return fmt.Errorf("ядро не запустилось: %w", err)
	}

	coreMu.Lock()
	coreCmd = proc
	coreSince = nowMillis()
	coreStopping = false
	coreMu.Unlock()

	// За процессом следим отдельно: он может умереть и сам — от плохого
	// конфига, от антивируса, от чего угодно. Молчать об этом нельзя:
	// системный прокси останется включённым, и на машине пропадёт
	// интернет целиком.
	go watchProcess(proc)

	if err := waitCoreReady(6 * time.Second); err != nil {
		stopCore()
		return err
	}
	return nil
}

// waitCoreReady — ждём, пока ядро начнёт принимать подключения.
//
// Мьютекс при этом не держим: интерфейс опрашивает состояние каждую
// секунду, и блокировка здесь подвешивала бы окно на всё время запуска.
func waitCoreReady(limit time.Duration) error {
	deadline := time.Now().Add(limit)

	for time.Now().Before(deadline) {
		if !coreRunning() {
			return fmt.Errorf("ядро закрылось сразу после запуска, подробности в core.log")
		}
		conn, err := net.DialTimeout("tcp", localAddr(httpPort), 300*time.Millisecond)
		if err == nil {
			_ = conn.Close()
			return nil
		}
		time.Sleep(150 * time.Millisecond)
	}
	return fmt.Errorf("ядро не отозвалось за %s", limit)
}

func localAddr(port int) string {
	return net.JoinHostPort("127.0.0.1", strconv.Itoa(port))
}

// watchProcess — дожидается конца процесса и прибирает за ним.
func watchProcess(proc *process) {
	err := proc.wait()

	coreMu.Lock()
	ours := coreCmd == proc
	planned := coreStopping
	if ours {
		coreCmd = nil
		coreSince = 0
	}
	coreMu.Unlock()

	if !ours || planned {
		return
	}

	logf("ядро завершилось само: %v", err)
	// Перехват без ядра — это отсутствие интернета на всей машине: и
	// прокси в никуда, и туннель, которому некому отдавать пакеты.
	releaseTraffic()
	setNotice("Соединение разорвано: ядро закрылось")
}

func stopCore() {
	coreMu.Lock()
	proc := coreCmd
	coreCmd = nil
	coreSince = 0
	coreStopping = true
	coreMu.Unlock()

	if proc != nil {
		proc.kill()
	}
}

func coreRunning() bool {
	coreMu.Lock()
	defer coreMu.Unlock()
	return coreCmd != nil
}

// connectedSeconds — сколько длится соединение. По нему рисуется таймер
// на главной.
func connectedSeconds() int {
	coreMu.Lock()
	defer coreMu.Unlock()
	if coreCmd == nil || coreSince == 0 {
		return 0
	}
	return int((nowMillis() - coreSince) / 1000)
}

// runWatchdog — сторож на случай, если ядро не умерло, а зависло.
//
// Процесс жив, а вход не отвечает: для человека это то же самое, что
// обрыв, только без единого сообщения. Три промаха подряд — считаем
// соединение потерянным и снимаем прокси.
func runWatchdog() {
	misses := 0
	for {
		time.Sleep(5 * time.Second)

		if !coreRunning() {
			misses = 0
			continue
		}
		conn, err := net.DialTimeout("tcp", localAddr(httpPort), time.Second)
		if err == nil {
			_ = conn.Close()
			misses = 0
			continue
		}

		misses++
		if misses < 3 {
			continue
		}
		misses = 0

		logf("ядро не отвечает на %s — снимаю соединение", localAddr(httpPort))
		stopCore()
		if perr := disableSystemProxy(); perr != nil {
			logf("прокси не выключился: %v", perr)
		}
		setNotice("Соединение потеряно: ядро перестало отвечать")
	}
}

// buildConfig — конфиг ядра: входы, выход на выбранный сервер и правила.
func buildConfig(s server, routing *routingAnswer) ([]byte, error) {
	outbound, err := buildOutbound(s)
	if err != nil {
		return nil, err
	}

	direct := map[string]any{"tag": "direct", "protocol": "freedom"}

	// В режиме VPN весь трафик машины идёт в туннель, а туннель — в это
	// же ядро. Пакеты самого ядра так попали бы в кольцо, поэтому метим
	// их: по метке система отправляет их обычным путём, мимо туннеля.
	// Метка нужна обоим выходам — и на сервер, и напрямую.
	if mark := outboundMark(); mark > 0 {
		sockopt := map[string]any{"sockopt": map[string]any{"mark": mark}}
		if stream, ok := outbound["streamSettings"].(map[string]any); ok {
			stream["sockopt"] = map[string]any{"mark": mark}
		} else {
			outbound["streamSettings"] = sockopt
		}
		direct["streamSettings"] = map[string]any{"sockopt": map[string]any{"mark": mark}}
	}

	rules := []any{}
	directDNS := []string{"77.88.8.8"}
	strategy := "IPIfNonMatch"

	if routing != nil {
		if routing.Settings.DomainStrategy != "" {
			strategy = routing.Settings.DomainStrategy
		}
		if len(routing.Settings.DirectDNS) > 0 {
			directDNS = routing.Settings.DirectDNS
		}
		// Порядок решает: выигрывает первое совпавшее правило, поэтому
		// «напрямую» идёт раньше всего остального.
		if ruDirect() {
			for _, raw := range routing.RuDirect {
				if rule := decodeRule(raw); rule != nil {
					rules = append(rules, rule)
				}
			}
		}
		for _, raw := range routing.Common {
			if rule := decodeRule(raw); rule != nil {
				rules = append(rules, rule)
			}
		}
	}

	dnsServers := []any{}
	// Российские домены спрашиваем у российского резолвера напрямую:
	// они и так идут мимо VPN, и гонять их имена через туннель — лишний
	// круг на ровном месте.
	if domains := directDomains(routing); len(domains) > 0 {
		dnsServers = append(dnsServers, map[string]any{
			"address":      directDNS[0],
			"domains":      domains,
			"skipFallback": true,
		})
	}
	dnsServers = append(dnsServers,
		"https://cloudflare-dns.com/dns-query",
		"localhost",
	)

	conf := map[string]any{
		"log": map[string]any{"loglevel": "warning"},
		"dns": map[string]any{
			// Только IPv4: иначе на каждое имя уходит два запроса, а
			// потом ещё попытки достучаться по IPv6, которого у
			// большинства провайдеров нет, — и каждая страница ждёт
			// лишние секунды.
			"queryStrategy": "UseIPv4",
			"servers":       dnsServers,
		},
		"inbounds": []any{
			map[string]any{
				"tag":      "socks",
				"listen":   "127.0.0.1",
				"port":     socksPort,
				"protocol": "socks",
				"settings": map[string]any{"auth": "noauth", "udp": true},
				"sniffing": map[string]any{
					"enabled":      true,
					"destOverride": []string{"http", "tls"},
				},
			},
			map[string]any{
				"tag":      "http",
				"listen":   "127.0.0.1",
				"port":     httpPort,
				"protocol": "http",
				"sniffing": map[string]any{
					"enabled":      true,
					"destOverride": []string{"http", "tls"},
				},
			},
		},
		"outbounds": []any{
			outbound,
			direct,
			map[string]any{"tag": "block", "protocol": "blackhole"},
		},
		"routing": map[string]any{
			"domainStrategy": strategy,
			"rules":          rules,
		},
	}
	return json.MarshalIndent(conf, "", "  ")
}

// decodeRule — правило с бэкенда в том виде, в каком его понимает ядро.
// Формат совпадает, переводить нечего; выбрасываем только выключенные.
func decodeRule(raw json.RawMessage) map[string]any {
	var rule map[string]any
	if err := json.Unmarshal(raw, &rule); err != nil {
		return nil
	}
	if enabled, ok := rule["enabled"].(bool); ok && !enabled {
		return nil
	}
	delete(rule, "enabled")
	delete(rule, "remarks")
	delete(rule, "id")
	delete(rule, "locked")
	rule["type"] = "field"
	return rule
}

// directDomains — домены, которые идут мимо VPN.
func directDomains(routing *routingAnswer) []string {
	out := []string{}
	if routing == nil || !ruDirect() {
		return out
	}
	for _, raw := range routing.RuDirect {
		var rule struct {
			OutboundTag string   `json:"outboundTag"`
			Domain      []string `json:"domain"`
		}
		if json.Unmarshal(raw, &rule) == nil && rule.OutboundTag == "direct" {
			out = append(out, rule.Domain...)
		}
	}
	return out
}

// serverAddress — адрес сервера для конфига.
//
// В обычном режиме отдаём как есть: имя разрешит само ядро. В режиме
// VPN так нельзя — к моменту, когда ядру понадобится имя, весь трафик
// машины уже заперт в туннеле, а туннель ведёт в это же ядро. Выходит
// кольцо: чтобы подключиться, нужно имя, чтобы узнать имя, нужно
// подключение. Поэтому разрешаем заранее, пока обычная сеть ещё
// работает, и помним ответ — он пригодится при перезапуске ядра, когда
// спросить будет уже некого.
var (
	resolvedMu sync.Mutex
	resolvedIP = map[string]string{}
)

func serverAddress(host string) string {
	if !vpnMode() || net.ParseIP(host) != nil {
		return host
	}

	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()

	addrs, err := net.DefaultResolver.LookupIP(ctx, "ip4", host)
	if err == nil && len(addrs) > 0 {
		resolvedMu.Lock()
		resolvedIP[host] = addrs[0].String()
		resolvedMu.Unlock()
		return addrs[0].String()
	}

	resolvedMu.Lock()
	cached := resolvedIP[host]
	resolvedMu.Unlock()

	if cached != "" {
		logf("имя %s не разрешилось (%v) — беру прошлый адрес %s", host, err, cached)
		return cached
	}
	logf("имя %s не разрешилось: %v", host, err)
	return host
}

func buildOutbound(s server) (map[string]any, error) {
	stream := map[string]any{"network": s.Network}

	// Адрес мог превратиться в цифры — тогда имя для TLS и заголовка
	// Host приходится подставлять отдельно, иначе сервер не узнает, о
	// каком сайте его спрашивают, и оборвёт рукопожатие.
	address := serverAddress(s.Address)
	if address != s.Address {
		s.SNI = first(s.SNI, s.Address)
		s.Host = first(s.Host, s.SNI)
	}

	switch s.Security {
	case "tls":
		stream["security"] = "tls"
		stream["tlsSettings"] = map[string]any{
			"serverName":    s.SNI,
			"allowInsecure": s.Insecure,
		}
	case "reality":
		return nil, fmt.Errorf("reality пока не поддерживается")
	}

	switch s.Network {
	case "ws":
		ws := map[string]any{"path": first(s.Path, "/")}
		if s.Host != "" {
			ws["headers"] = map[string]any{"Host": s.Host}
		}
		stream["wsSettings"] = ws
	case "grpc":
		stream["grpcSettings"] = map[string]any{"serviceName": s.Path}
	}

	switch s.Protocol {
	case "vless":
		user := map[string]any{"id": s.ID, "encryption": "none"}
		if s.Flow != "" {
			user["flow"] = s.Flow
		}
		return map[string]any{
			"tag":      "proxy",
			"protocol": "vless",
			"settings": map[string]any{
				"vnext": []any{map[string]any{
					"address": address,
					"port":    s.Port,
					"users":   []any{user},
				}},
			},
			"streamSettings": stream,
		}, nil

	case "trojan":
		return map[string]any{
			// Тег один для всех протоколов: правила ссылаются на него,
			// а не на то, чем именно сервер оказался.
			"tag":      "proxy",
			"protocol": "trojan",
			"settings": map[string]any{
				"servers": []any{map[string]any{
					"address":  address,
					"port":     s.Port,
					"password": s.ID,
				}},
			},
			"streamSettings": stream,
		}, nil
	}
	return nil, fmt.Errorf("протокол %q не поддерживается", s.Protocol)
}
