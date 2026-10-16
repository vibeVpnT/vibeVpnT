//go:build linux

package main

import (
	"os"
	"path/filepath"
	"strconv"
	"testing"
)

// Проверка системного прокси идёт по живым настройкам рабочего стола —
// другого способа убедиться, что мы правильно их читаем и возвращаем,
// нет. Поэтому по умолчанию она пропускается: на пару секунд прокси
// указывает в никуда, и у того, кто в этот момент сидит за машиной,
// моргнёт интернет.
//
//	VV_PROXY_TEST=1 go test -run Proxy -v
func TestSystemProxy(t *testing.T) {
	if os.Getenv("VV_PROXY_TEST") != "1" {
		t.Skip("меняет настройки рабочего стола: запускать с VV_PROXY_TEST=1")
	}
	if !haveGSettings() {
		t.Skip("gsettings не нашёлся")
	}

	isolateState(t)

	before := snapshot(t)
	// Что бы ни случилось дальше, настройки человека вернём на место.
	t.Cleanup(func() {
		for _, key := range proxyKeys {
			if value, ok := before[key.String()]; ok {
				_ = gsettingsSet(key, value)
			}
		}
	})

	if err := enableSystemProxy(); err != nil {
		t.Fatalf("включить не вышло: %v", err)
	}
	if !proxyIsOurs() {
		t.Fatal("включили, а прокси не наш")
	}

	// Проверяем не только режим: адрес и список исключений должны
	// доехать целиком, иначе половина трафика пойдёт мимо.
	during := snapshot(t)
	for key, want := range map[string]string{
		"org.gnome.system.proxy.http host":    "'127.0.0.1'",
		"org.gnome.system.proxy.http port":    strconv.Itoa(httpPort),
		"org.gnome.system.proxy.socks port":   strconv.Itoa(socksPort),
		"org.gnome.system.proxy mode":         "'manual'",
		"org.gnome.system.proxy ignore-hosts": bypassLiteral(),
	} {
		if during[key] != want {
			t.Errorf("%s = %s, а ждали %s", key, during[key], want)
		}
	}

	// Второе включение подряд не должно запомнить наши же настройки как
	// чужие: иначе откат вернёт прокси на нас самих.
	if err := enableSystemProxy(); err != nil {
		t.Fatalf("повторное включение: %v", err)
	}
	mode := gsettingKey{"org.gnome.system.proxy", "mode"}.String()
	stateMu.Lock()
	saved := st.PrevGnomeProxy[mode]
	stateMu.Unlock()
	if saved != before[mode] {
		t.Errorf("после второго включения помним %s вместо исходного %s", saved, before[mode])
	}

	if err := disableSystemProxy(); err != nil {
		t.Fatalf("выключить не вышло: %v", err)
	}

	after := snapshot(t)
	for _, key := range proxyKeys {
		if before[key.String()] != after[key.String()] {
			t.Errorf("%s не вернулся: было %s, стало %s",
				key, before[key.String()], after[key.String()])
		}
	}
}

// TestHealStaleProxy — прокси, оставшийся от прибитой программы.
// Случай не выдуманный: именно из-за него на машине пропадает интернет
// целиком, и чинить его программа должна сама, при следующем запуске.
func TestHealStaleProxy(t *testing.T) {
	if os.Getenv("VV_PROXY_TEST") != "1" {
		t.Skip("меняет настройки рабочего стола: запускать с VV_PROXY_TEST=1")
	}
	if !haveGSettings() {
		t.Skip("gsettings не нашёлся")
	}

	isolateState(t)

	before := snapshot(t)
	t.Cleanup(func() {
		for _, key := range proxyKeys {
			if value, ok := before[key.String()]; ok {
				_ = gsettingsSet(key, value)
			}
		}
	})

	if err := enableSystemProxy(); err != nil {
		t.Fatalf("включить не вышло: %v", err)
	}

	// Программу прибили: память потеряна, в настройках остался наш
	// прокси. Ровно так это и выглядит после `kill -9`.
	stateMu.Lock()
	st.PrevGnomeProxy = nil
	stateMu.Unlock()

	healed, err := healStaleProxy()
	if err != nil {
		t.Fatalf("лечение не удалось: %v", err)
	}
	if !healed {
		t.Fatal("не заметили собственный прокси")
	}
	if mode, _ := gsettingsGet(gsettingKey{"org.gnome.system.proxy", "mode"}); mode != "'none'" {
		t.Errorf("после лечения режим %s, а должен быть 'none'", mode)
	}
}

// isolateState — увести файл состояния во временную папку, чтобы тест не
// затоптал настоящий.
//
// Тонкость, на которой этот тест сам и споткнулся: в XDG_CONFIG_HOME
// лежит не только наше состояние, но и база dconf — там, где gsettings
// хранит всё, что читает. Подменишь каталог целиком — записи уходят в
// настоящую базу через службу, а чтения возвращают значения по
// умолчанию из пустой. Поэтому базу оставляем на месте ссылкой.
func isolateState(t *testing.T) {
	t.Helper()

	real, err := os.UserConfigDir()
	if err != nil {
		t.Skip("не найти каталог настроек пользователя")
	}

	temp := t.TempDir()
	if err := os.Symlink(filepath.Join(real, "dconf"), filepath.Join(temp, "dconf")); err != nil {
		t.Fatalf("не подставить базу dconf: %v", err)
	}
	t.Setenv("XDG_CONFIG_HOME", temp)
}

func snapshot(t *testing.T) map[string]string {
	t.Helper()
	out := map[string]string{}
	for _, key := range proxyKeys {
		value, err := gsettingsGet(key)
		if err != nil {
			t.Fatalf("не прочитать %s: %v", key, err)
		}
		out[key.String()] = value
	}
	return out
}
