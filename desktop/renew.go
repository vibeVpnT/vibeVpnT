package main

import (
	"fmt"
	"time"
)

// Что стало с чеком.
//
// Решение принимает человек на той стороне, и ждать его можно минуты, а
// можно и дольше. Сообщить нам об этом бэкенд не умеет — спрашиваем
// сами, пока ответа нет. Сам чек при этом уже отправлен: программу
// можно закрыть, ответ не потеряется.

// Статусы, при которых заявка ещё жива. Остальные — окончательные.
func requestOpen(status string) bool {
	switch status {
	case "pending", "processing", "manual", "failed":
		return true
	}
	return false
}

// Как часто спрашиваем. Редко: решение принимает человек, и минута
// туда-сюда ничего не меняет, а лишний запрос в минуту — это запрос в
// минуту от каждой запущенной программы.
const renewPollInterval = time.Minute

// runRenewWatch — сторож продления.
//
// Первый круг делает сразу: решение могли принять, пока программа была
// закрыта, и тогда человек должен узнать о нём при запуске, а не через
// минуту после.
func runRenewWatch() {
	for {
		stateMu.Lock()
		id, status := st.RenewRequestID, st.RenewRequestStatus
		stateMu.Unlock()

		if id != 0 && requestOpen(status) {
			if answer, err := fetchRenew(); err == nil {
				noteRenewRequest(answer.Request)
			}
		}

		time.Sleep(renewPollInterval)
	}
}

// noteRenewRequest — запомнить судьбу заявки и, если она решилась,
// сказать об этом человеку.
//
// Зовут отовсюду, где с бэкенда приходит свежий ответ: и сторож, и сам
// экран подписки. Поэтому повторные вызовы с тем же статусом должны
// молчать — иначе уведомление приходило бы каждые десять секунд, пока
// открыт экран.
func noteRenewRequest(request *renewRequest) {
	stateMu.Lock()
	wasID, wasStatus := st.RenewRequestID, st.RenewRequestStatus

	if request == nil {
		st.RenewRequestID, st.RenewRequestStatus = 0, ""
		changed := wasID != 0
		stateMu.Unlock()
		if changed {
			save()
		}
		return
	}

	st.RenewRequestID, st.RenewRequestStatus = request.ID, request.Status
	stateMu.Unlock()
	save()

	// Другая заявка: её судьбу мы и не отслеживали — просто запомнили.
	if request.ID != wasID {
		return
	}
	if request.Status == wasStatus {
		return
	}

	announceRenew(request)
}

func announceRenew(request *renewRequest) {
	switch request.Status {
	case "approved", "auto_approved":
		// Сначала перечитываем подписку: иначе уведомление скажет
		// «продлено», а на главной останется прежний срок.
		if answer, err := refreshSubscription(); err == nil && answer.SubscriptionURL != "" {
			applySubscription(answer)
		}

		text := "Оплата принята, подписка продлена" + renewGained(request) + "."
		logf("продление зачислено: заявка %d%s", request.ID, renewGained(request))
		setNotice(text)
		notify(brandTitle+" — подписка продлена", text)

	case "rejected":
		text := "Чек не приняли. Если это ошибка, напишите в поддержку."
		logf("заявка %d отклонена", request.ID)
		setNotice(text)
		notify(brandTitle+" — чек отклонён", text)

	case "rolled_back":
		text := "Продление откатили. Если это ошибка, напишите в поддержку."
		logf("заявка %d откачена", request.ID)
		setNotice(text)
		notify(brandTitle+" — продление откатили", text)
	}
}

// renewGained — что именно прибавилось. У заявки со своей суммой срок
// назначает человек, и до его решения тут пусто.
func renewGained(request *renewRequest) string {
	if request.Days == nil || *request.Days == 0 {
		return ""
	}

	out := fmt.Sprintf(": +%d %s", *request.Days, plural(*request.Days, "день", "дня", "дней"))
	if request.GB != nil && *request.GB > 0 {
		out += fmt.Sprintf(" и %d ГБ", *request.GB)
	}
	return out
}

// plural — «1 день», «2 дня», «5 дней». Тот же разбор, что в окне: текст
// уведомления человек читает теми же глазами.
func plural(n int, one, few, many string) string {
	mod10, mod100 := n%10, n%100
	switch {
	case mod10 == 1 && mod100 != 11:
		return one
	case mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20):
		return few
	}
	return many
}
