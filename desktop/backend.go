package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"runtime"
	"time"
)

// Наш бэкенд: тот же, что у мобильного приложения, и те же ручки.
// Личность — аккаунт: вход по номеру или по коду с другого устройства
// (здесь показывают QR, вошедшее устройство его сканирует). Дальше
// программа предъявляет только токен своей сессии.

var httpClient = &http.Client{Timeout: 25 * time.Second}

// Для чека — свой срок. Обычный разговор с бэкендом укладывается в
// секунды, а чек это файл до полусотни мегабайт: на небыстром канале он
// уезжает минутами, и общие двадцать пять секунд обрывали бы отправку
// на полпути.
var uploadClient = &http.Client{Timeout: 15 * time.Minute}

type startAnswer struct {
	Status           string `json:"status"`
	SubscriptionURL  string `json:"subscription_url"`
	SubscriptionName string `json:"subscription_name"`
	Username         string `json:"username"`
	DaysLeft         *int   `json:"days_left"`
	RoutingVersion   int    `json:"routing_version"`
	AccountNumber    string `json:"account_number"`
	Confirmed        bool   `json:"confirmed"`
	TrialDays        int    `json:"trial_days"`
	Message          string `json:"message"`
}

// Ответ на вход: новая сессия.
type sessionAnswer struct {
	Status        string `json:"status"`
	Token         string `json:"token"`
	AccountNumber string `json:"account_number"`
	Confirmed     bool   `json:"confirmed"`
	Message       string `json:"message"`
}

type codeAnswer struct {
	Status     string `json:"status"`
	Code       string `json:"code"`
	WaitSecret string `json:"wait_secret"`
	TTLSeconds int    `json:"ttl_seconds"`
	Message    string `json:"message"`
}

// errUnauthorized — бэкенд не узнал сессию: из неё вышли на другом
// устройстве или перевыпустили номер. Войти придётся заново.
var errUnauthorized = errors.New("сессия закончилась, войдите заново")

func postJSON(path string, body any, out any) error {
	return postJSONWith(httpClient, path, body, out)
}

func postJSONWith(client *http.Client, path string, body any, out any) error {
	raw, err := json.Marshal(body)
	if err != nil {
		return err
	}
	req, err := http.NewRequest(http.MethodPost, brandBackend+path, bytes.NewReader(raw))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	if token := sessionToken(); token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}

	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusUnauthorized {
		dropSession()
		return errUnauthorized
	}

	data, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return err
	}
	if out == nil {
		return nil
	}
	if err := json.Unmarshal(data, out); err != nil {
		// Ответ не json — значит, отвечал не наш бэкенд, а страница
		// ошибки: nginx, портал гостиничного вайфая, что угодно.
		// Человеку нужен код ответа, а не разбор чужого html.
		logf("ответ на %s не разобрался (код %d): %.200s", path, resp.StatusCode, data)
		return fmt.Errorf("непонятный ответ сервера (код %d)", resp.StatusCode)
	}
	return nil
}

// Чем программа представляется бэкенду. Только для списка устройств
// аккаунта и для поддержки: ни одно поле её не опознаёт.
func deviceFields() map[string]any {
	return map[string]any{
		"platform":        runtime.GOOS,
		"device_name":     "Компьютер · " + platformName(),
		"app_version":     appVersion,
		"device_brand":    "PC",
		"device_model":    platformName(),
		"android_release": runtime.GOOS,
		"source":          "desktop",
	}
}

// sessionFrom — ответ на вход в пригодном виде. Отказ приходит обычным
// ответом с причиной, и показать её человеку должен тот, кто вызвал.
func sessionFrom(out *sessionAnswer) (*sessionAnswer, error) {
	if out.Token == "" {
		return nil, fmt.Errorf("%s", first(out.Message, out.Status, "сервер не впустил"))
	}
	return out, nil
}

// createAccount — новый аккаунт: номер и сессия. Подписки у него ещё нет.
func createAccount() (*sessionAnswer, error) {
	var out sessionAnswer
	if err := postJSON("/api/v1/account/create", deviceFields(), &out); err != nil {
		return nil, err
	}
	return sessionFrom(&out)
}

// loginByNumber — вход по номеру аккаунта.
func loginByNumber(number string) (*sessionAnswer, error) {
	body := deviceFields()
	body["number"] = number

	var out sessionAnswer
	if err := postJSON("/api/v1/account/login", body, &out); err != nil {
		return nil, err
	}
	return sessionFrom(&out)
}

// migrateSession — старая версия знала себя по DeviceID. Один раз меняем
// его на сессию в аккаунте той же подписки и больше не шлём.
func migrateSession(deviceID string) (*sessionAnswer, error) {
	body := deviceFields()
	body["android_id"] = deviceID

	var out sessionAnswer
	if err := postJSON("/api/v1/session/migrate", body, &out); err != nil {
		return nil, err
	}
	return sessionFrom(&out)
}

// requestJoinCode — просим код, который покажем QR-кодом. Аккаунт для
// этого не нужен; секрет ожидания знает только эта программа.
func requestJoinCode() (*codeAnswer, error) {
	var out codeAnswer
	if err := postJSON("/api/v1/code/request", map[string]any{}, &out); err != nil {
		return nil, err
	}
	if out.Status != "ok" || out.Code == "" {
		return nil, fmt.Errorf("код не выдан: %s %s", out.Status, out.Message)
	}
	return &out, nil
}

// joinStatus — впустили ли уже. Пока код не отсканировали, бэкенд
// отвечает «ждём», а потом — новой сессией.
func joinStatus(code, secret string) (*sessionAnswer, error) {
	body := deviceFields()
	body["code"] = code
	body["wait_secret"] = secret

	var out sessionAnswer
	if err := postJSON("/api/v1/code/status", body, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// refreshSubscription — что у аккаунта с подпиской.
func refreshSubscription() (*startAnswer, error) {
	var out startAnswer
	if err := postJSON("/api/v1/me", deviceFields(), &out); err != nil {
		return nil, err
	}
	return &out, nil
}

// claimTrial — пробный период аккаунту без подписки.
func claimTrial() (*startAnswer, error) {
	var out startAnswer
	if err := postJSON("/api/v1/account/trial", deviceFields(), &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func confirmNumber() error {
	return postJSON("/api/v1/account/confirm", map[string]any{}, nil)
}

// logoutSession — закрыть сессию на сервере. Ответ не важен: локально
// из аккаунта выходим в любом случае.
func logoutSession() {
	_ = postJSON("/api/v1/logout", map[string]any{}, nil)
}

// --- Продление ---------------------------------------------------------

// Тариф: сколько заплатить и что за это будет. Приходит с бэкенда, тот
// берёт его из apps/<приложение>.yml — там же, где тарифы бота.
type renewTier struct {
	Index  int    `json:"index"`
	Amount int    `json:"amount"`
	Days   int    `json:"days"`
	GB     int    `json:"gb"`
	Emoji  string `json:"emoji"`
}

// Подписка глазами панели. Срок и трафик держим раздельно: кончиться
// может и то, и другое, а причина человеку нужна точная.
type renewSub struct {
	Username         string `json:"username"`
	Status           string `json:"status"`
	ExpiresAt        string `json:"expires_at"`
	DaysLeft         *int   `json:"days_left"`
	DataLimit        int64  `json:"data_limit"`
	UsedTraffic      int64  `json:"used_traffic"`
	StartsOnFirstUse bool   `json:"starts_on_first_use"`
}

// Последняя заявка на продление и что с ней стало.
type renewRequest struct {
	ID        int    `json:"id"`
	Amount    *int   `json:"amount"`
	Days      *int   `json:"days"`
	GB        *int   `json:"gb"`
	Status    string `json:"status"`
	CreatedAt string `json:"created_at"`
	Delivered bool   `json:"delivered"`
}

type renewAnswer struct {
	Status             string        `json:"status"`
	RenewEnabled       bool          `json:"renew_enabled"`
	Tiers              []renewTier   `json:"tiers"`
	AllowCustom        bool          `json:"allow_custom"`
	PayURL             string        `json:"pay_url"`
	Requisites         string        `json:"requisites"`
	AutoApproveMinutes int           `json:"auto_approve_minutes"`
	Subscription       *renewSub     `json:"subscription"`
	Request            *renewRequest `json:"request"`
	Message            string        `json:"message"`
}

// fetchRenew — экран подписки одним запросом: срок, тарифы, куда платить
// и судьба последнего чека.
func fetchRenew() (*renewAnswer, error) {
	var out renewAnswer
	if err := postJSON("/api/v1/renew", deviceFields(), &out); err != nil {
		return nil, err
	}
	if out.Status != "ok" {
		return nil, fmt.Errorf("%s", first(out.Message, out.Status))
	}
	return &out, nil
}

// sendReceipt — чек об оплате. Файл идёт base64 в теле: ничего другого
// файлами программа не отправляет, и ради одного чека заводить
// multipart незачем. Тип любой — хоть снимок экрана, хоть pdf из банка.
//
// Отказ бэкенда — не ошибка связи: он приходит обычным ответом с
// причиной, и показать её человеку должен тот, кто вызвал.
func sendReceipt(tier *int, amount *int, image, name string) (*renewAnswer, error) {
	body := deviceFields()
	body["image"] = image
	body["name"] = name
	if tier != nil {
		body["tier"] = *tier
	}
	if amount != nil {
		body["amount"] = *amount
	}

	var out renewAnswer
	if err := postJSONWith(uploadClient, "/api/v1/renew/receipt", body, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

type routingAnswer struct {
	Version  int `json:"version"`
	Settings struct {
		DomainStrategy string   `json:"domain_strategy"`
		DirectDNS      []string `json:"direct_dns"`
	} `json:"settings"`
	RuDirect []json.RawMessage `json:"ru_direct"`
	Common   []json.RawMessage `json:"common"`
}

// fetchRouting — правила маршрутизации с бэкенда. Файл там один на все
// приложения, поэтому компьютер ходит мимо VPN ровно туда же, куда и
// телефон.
func fetchRouting() (*routingAnswer, error) {
	resp, err := httpClient.Get(brandBackend + "/api/v1/routing")
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	var out routingAnswer
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&out); err != nil {
		return nil, err
	}
	return &out, nil
}
