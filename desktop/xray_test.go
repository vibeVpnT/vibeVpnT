package main

import (
	"net"
	"os"
	"testing"
	"time"
)

// TestCoreStarts — поднимается ли ядро на том конфиге, который собирает
// программа, и с теми правилами, которые прямо сейчас раздаёт бэкенд.
//
// Проверка не выдуманная: ядро падает сразу и целиком, если в конфиге
// опечатка или если в справочниках нет категории, на которую ссылается
// правило. Для человека это выглядит так, что подключение просто не
// включается, — и понять, почему, без журнала невозможно.
//
// Сервер берём заведомо несуществующий: ядру всё равно, оно слушает
// локальный вход сразу, а соединение наружу открывает только под трафик.
// Системный прокси при этом не трогается вовсе.
//
//	VV_CORE_DIR=/opt/vv go test -run Core -v
func TestCoreStarts(t *testing.T) {
	dir := os.Getenv("VV_CORE_DIR")
	if dir == "" {
		t.Skip("где лежит ядро — в VV_CORE_DIR (например /opt/vv)")
	}
	if _, err := os.Stat(dir + "/" + coreBinaryName); err != nil {
		t.Skipf("в %s нет %s", dir, coreBinaryName)
	}

	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	assetsAt = dir

	// Правила живые, с бэкенда: смысл проверки как раз в том, чтобы
	// заметить расхождение между ними и справочниками в пакете.
	routing, err := fetchRouting()
	if err != nil {
		t.Fatalf("правила не скачались: %v", err)
	}

	sample := server{
		Tag:      "проверка",
		Remark:   "проверка",
		Protocol: "vless",
		Address:  "192.0.2.1", // адрес из диапазона для примеров, его не существует
		Port:     443,
		ID:       "00000000-0000-0000-0000-000000000000",
		Network:  "ws",
		Path:     "/",
		Security: "tls",
		SNI:      "example.com",
	}

	if err := startCore(sample, routing); err != nil {
		t.Fatalf("ядро не поднялось: %v\n\n%s", err, coreLog(t))
	}
	t.Cleanup(stopCore)

	// Оба входа должны принимать подключения: через socks ходят
	// программы, через http — системный прокси.
	for _, port := range []int{socksPort, httpPort} {
		conn, err := net.DialTimeout("tcp", localAddr(port), time.Second)
		if err != nil {
			t.Errorf("вход на %d не отвечает: %v", port, err)
			continue
		}
		_ = conn.Close()
	}
}

func coreLog(t *testing.T) string {
	t.Helper()
	raw, err := os.ReadFile(dataDir() + "/core.log")
	if err != nil {
		return "журнал ядра не прочитался: " + err.Error()
	}
	return string(raw)
}
