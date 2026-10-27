package main

import (
	"embed"
	"fmt"
	"io/fs"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"sync"
	"syscall"
	"time"
)

//go:embed ui
var uiFiles embed.FS

// Порт интерфейса. По нему же второй запуск понимает, что программа уже
// работает, и просто открывает окно вместо второй копии.
const uiPort = 48713

var (
	uiSeenMu sync.Mutex
	uiSeenAt = time.Now()
)

func main() {
	// Выключить программу снаружи. Под линуксом значка в трее нет, а
	// окно могли и закрыть — тогда без этого остаётся только kill, от
	// которого прокси успевает остаться включённым.
	if len(os.Args) > 1 && os.Args[1] == "--quit" {
		quitRunning()
		return
	}

	// Служба туннеля: тот же файл, но запущенный от root через systemd.
	// Окна у неё нет, состояния тоже — она только держит интерфейс,
	// пока её не попросят остановиться.
	if len(os.Args) > 1 && os.Args[1] == "--tun-run" {
		openLog()
		// Служба тоже должна знать, где лежит ядро: рядом с ним лежит
		// и сам туннель.
		if err := ensureAssets(); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		if err := runTunHelper(); err != nil {
			logf("туннель: %v", err)
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
		return
	}

	// Второй запуск не плодит вторую копию, а показывает окно первой:
	// в меню приложений значок один, и нажимают на него не глядя.
	if alreadyRunning() {
		openWindow()
		return
	}

	loadState()
	openLog()
	logf("запуск, версия %s", appVersion)

	if err := ensureAssets(); err != nil {
		fatal(err.Error())
	}
	logf("ядро и справочники: %s", assetDir())

	// Прокси мог остаться включённым с прошлого раза, если программу
	// закрыли не по-людски: сняли из диспетчера задач или её удалил
	// антивирус. Без этой проверки у человека просто нет интернета.
	if healed, err := healStaleProxy(); err != nil {
		logf("не удалось проверить системный прокси: %v", err)
	} else if healed {
		logf("снят системный прокси, оставшийся с прошлого запуска")
	}

	listener, err := net.Listen("tcp", localAddr(uiPort))
	if err != nil {
		fatal(fmt.Sprintf("Порт %d занят другой программой — окно открыть негде.", uiPort))
	}

	go runWatchdog()
	go runStartupSync()
	go runRenewWatch()

	// Прокси и ядро нельзя оставлять включёнными после выхода: иначе
	// интернет на машине останется перенаправленным в никуда.
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-signals
		shutdown()
	}()

	// Проверочный запуск без окна: так программу гоняют на сборочной
	// машине, где нет ни рабочего стола, ни браузера. Без этого она
	// закрывалась бы через три минуты сама, не дождавшись проверки.
	headless := os.Getenv("VV_HEADLESS") == "1"

	// Значок в трее: иначе программа работает невидимо и выключить её
	// нечем. Если завести значок не вышло — а в линуксе его и не бывает,
	// — за окном следит сторож.
	switch {
	case startTray():
		logf("значок в трее заведён")
	case headless:
		logf("запуск без окна: сторож выключен")
	default:
		go runWindowWatch()
	}

	if !headless {
		go func() {
			time.Sleep(300 * time.Millisecond)
			openWindow()
		}()
	}

	server := &http.Server{Handler: routes()}
	if err := server.Serve(listener); err != nil && err != http.ErrServerClosed {
		logf("интерфейс остановлен: %v", err)
	}
}

func routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/api/state", handleState)
	mux.HandleFunc("/api/login", handleLogin)
	mux.HandleFunc("/api/account/login", handleAccountLogin)
	mux.HandleFunc("/api/account/create", handleAccountCreate)
	mux.HandleFunc("/api/account/confirm", handleAccountConfirm)
	mux.HandleFunc("/api/account/trial", handleAccountTrial)
	mux.HandleFunc("/api/account/logout", handleAccountLogout)
	mux.HandleFunc("/api/connect", handleConnect)
	mux.HandleFunc("/api/disconnect", handleDisconnect)
	mux.HandleFunc("/api/select", handleSelect)
	mux.HandleFunc("/api/ping", handlePing)
	mux.HandleFunc("/api/sort", handleSort)
	mux.HandleFunc("/api/rudirect", handleRuDirect)
	mux.HandleFunc("/api/vpnmode", handleVPNMode)
	mux.HandleFunc("/api/tunwanted", handleTunWanted)
	mux.HandleFunc("/api/source", handleSource)
	mux.HandleFunc("/api/refresh", handleRefresh)
	mux.HandleFunc("/api/renew", handleRenew)
	mux.HandleFunc("/api/renew/receipt", handleReceipt)
	mux.HandleFunc("/api/renew/pay", handlePay)
	mux.HandleFunc("/api/log", handleLog)
	mux.HandleFunc("/api/quit", handleQuit)
	mux.Handle("/", fresh(http.FileServer(http.FS(mustSub(uiFiles, "ui")))))
	return guard(mux)
}

// fresh — запрещаем браузеру запоминать страницу.
//
// Интерфейс лежит внутри программы и меняется вместе с ней, а отдаётся
// без даты и без ETag: встроенная файловая система их не знает. Браузер
// в таком случае решает сам и после обновления программы спокойно
// показывает старое окно — с прежними кнопками и прежними текстами
// ошибок. Качать тут нечего, всё своё, так что проще не кешировать вовсе.
func fresh(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store, must-revalidate")
		next.ServeHTTP(w, r)
	})
}

// guard — к интерфейсу пускаем только с этой же машины.
//
// Порт слушается на петле, но страница в браузере тоже «с этой машины»:
// чужой сайт не сможет ничего сделать, пока не знает адрес, — поэтому
// отсекаем запросы с чужим Origin, которым браузер помечает такие
// попытки.
func guard(next http.Handler) http.Handler {
	allowed := map[string]bool{
		"": true,
		fmt.Sprintf("http://127.0.0.1:%d", uiPort): true,
		fmt.Sprintf("http://localhost:%d", uiPort): true,
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !allowed[r.Header.Get("Origin")] {
			http.Error(w, "чужой источник", http.StatusForbidden)
			return
		}
		next.ServeHTTP(w, r)
	})
}

// runStartupSync — подписка и правила в фоне: окно должно открыться
// сразу, а не после похода на сервер.
func runStartupSync() {
	reloadRouting()
	migrateLegacy()
	if sessionToken() == "" {
		// Не вошли: серверы прошлого раза ещё годятся, пока жив ключ.
		reloadServers()
		return
	}
	if answer, err := refreshSubscription(); err == nil {
		_ = applySubscription(answer)
	} else {
		if err != nil {
			logf("бэкенд не ответил при запуске: %v", err)
		}
		reloadServers()
	}
}

// runWindowWatch — что делать, когда окно закрыли.
//
// Окно опрашивает состояние каждую секунду; замолчало надолго — значит,
// его закрыли. Порог с запасом: короткий усыплял бы программу вместе с
// ноутбуком, а это лишний обрыв соединения на ровном месте.
//
// Дальше решает соединение. Пока оно держится, уходить нельзя: ради
// него программа и запущена, и выключать VPN оттого, что человек убрал
// окно с глаз, — ровно то поведение, за которое её хочется выбросить.
// Вернуть окно можно тем же значком в меню: второй запуск видит первый
// и просто открывает окно заново. А вот болтаться невидимо без
// соединения незачем — тогда закрываемся.
func runWindowWatch() {
	const silenceLimit = 3 * time.Minute
	told := false

	for {
		time.Sleep(15 * time.Second)

		uiSeenMu.Lock()
		silence := time.Since(uiSeenAt)
		uiSeenMu.Unlock()

		if silence <= silenceLimit {
			told = false
			continue
		}

		if coreRunning() {
			if !told {
				logf("окно закрыто, соединение держу — выйти можно из настроек или `%s --quit`", brandName)
				told = true
			}
			continue
		}

		logf("окно закрыто, соединения нет — выхожу")
		shutdown()
	}
}

func noteUIAlive() {
	uiSeenMu.Lock()
	uiSeenAt = time.Now()
	uiSeenMu.Unlock()
}

func shutdown() {
	releaseTraffic()
	stopCore()
	stopTray()
	logf("выход")
	os.Exit(0)
}

// quitRunning — попросить работающую копию закрыться по-хорошему: снять
// системный прокси, остановить ядро и выйти.
func quitRunning() {
	client := &http.Client{Timeout: 5 * time.Second}
	resp, err := client.Get(fmt.Sprintf("http://127.0.0.1:%d/api/quit", uiPort))
	if err != nil {
		fmt.Printf("%s не запущен\n", brandTitle)
		return
	}
	_ = resp.Body.Close()
	fmt.Printf("%s остановлен\n", brandTitle)
}

func alreadyRunning() bool {
	conn, err := net.DialTimeout("tcp", localAddr(uiPort), 400*time.Millisecond)
	if err != nil {
		return false
	}
	_ = conn.Close()
	return true
}

var assetsAt string

// assetDir — где лежат ядро и справочники. Кладёт их туда ensureAssets,
// и делает это по-разному: в Windows достаёт из себя, в линуксе берёт
// то, что положил пакет.
func assetDir() string { return assetsAt }

func corePath() string { return filepath.Join(assetDir(), coreBinaryName) }

func exeDir() string {
	exe, err := os.Executable()
	if err != nil {
		return "."
	}
	if resolved, err := filepath.EvalSymlinks(exe); err == nil {
		exe = resolved
	}
	return filepath.Dir(exe)
}

func mustSub(f embed.FS, dir string) fs.FS {
	sub, err := fs.Sub(f, dir)
	if err != nil {
		panic(err)
	}
	return sub
}

// fatal — показать ошибку человеку и закрыться: окна консоли у
// программы нет, писать больше некуда.
func fatal(text string) {
	logf("остановка: %s", text)
	showError(text)
	os.Exit(1)
}
