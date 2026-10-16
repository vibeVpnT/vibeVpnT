// Интерфейс обновляется по состоянию, которое отдаёт программа, но
// трогает в разметке только то, что действительно изменилось: раньше
// список серверов пересобирался целиком каждую секунду, и из-за этого
// слетало наведение, а клик попадал в уже заменённую строку.

let current = null;
let lastNotice = "";
let rows = new Map(); // tag -> элемент строки

const $ = (id) => document.getElementById(id);

async function call(path, body) {
  try {
    const response = body === undefined
      ? await fetch(path)
      : await fetch(path, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        });
    return await response.json();
  } catch (e) {
    return { ok: false, error: "программа не отвечает" };
  }
}

async function poll() {
  const state = await call("/api/state");
  if (state && state.version) render(state);
  setTimeout(poll, 1000);
}

function setText(element, text) {
  if (element.textContent !== text) element.textContent = text;
}

function render(s) {
  current = s;

  const needLogin = !s.authorized;
  $("login").classList.toggle("hidden", !needLogin);
  $("app").classList.toggle("hidden", needLogin);

  if (needLogin) {
    renderLogin(s.login);
    return;
  }

  $("power").classList.toggle("on", s.connected);
  setText($("timer"), clock(s.seconds));
  setText($("home-status"), s.busy || (s.connected ? "Подключено" : "Не подключено"));
  setText($("home-sub"), s.days_left === null || s.days_left === undefined
    ? ""
    : `Подписка: осталось ${s.days_left} ${plural(s.days_left, "день", "дня", "дней")}`);

  renderServers(s);

  $("ru-switch").classList.toggle("on", s.ru_direct);
  $("chip-ru").classList.toggle("on", s.ru_direct);
  // Режим показываем только там, где он работает: в Windows обещать
  // туннель нечем.
  $("vpn-card").classList.toggle("hidden", !s.vpn_supported);
  $("chip-vpn").classList.toggle("hidden", !s.vpn_supported);
  $("vpn-switch").classList.toggle("on", s.vpn_mode);
  $("chip-vpn").classList.toggle("on", s.vpn_mode);
  setText($("version"), s.version);
  setText($("brand"), s.brand || "");
  setText($("platform"), s.platform || "");
  setText($("ports"), `SOCKS ${s.proxy_ports[0]}, HTTP ${s.proxy_ports[1]}`);

  if (s.notice && s.notice !== lastNotice) showNotice(s.notice);
  lastNotice = s.notice;

  maybeWarn(s);
}

// --- Конец срока -----------------------------------------------------
//
// Предупреждаем за три дня и при каждом входе: программу открывают не
// каждый день, и единственное уведомление человек попросту пропустит.
// Внутри одного запуска показываем один раз — иначе окно всплывало бы
// каждую секунду, с каждым опросом состояния.

const WARN_DAYS = 3;
let warned = false;

function maybeWarn(s) {
  if (warned || s.days_left === null || s.days_left === undefined) return;
  if (s.days_left > WARN_DAYS) return;

  warned = true;
  const left = s.days_left;
  setText($("warn-title"), left > 0 ? "Подписка заканчивается" : "Подписка закончилась");
  setText($("warn-text"), left > 0
    ? `Осталось ${left} ${plural(left, "день", "дня", "дней")}. Продлите заранее — доступ не прервётся.`
    : "Доступ уже не работает. Продлите подписку, чтобы вернуть его.");
  $("warn").classList.remove("hidden");
}

function closeWarn() { $("warn").classList.add("hidden"); }

function warnToRenew() {
  closeWarn();
  go("renew");
}

function renderLogin(login) {
  const has = login && login.code;
  $("login-empty").classList.toggle("hidden", !!has);
  $("login-code").classList.toggle("hidden", !has);
  if (!has) return;

  if ($("qr").src !== login.qr) $("qr").src = login.qr;
  setText($("digits"), login.code.slice(0, 3) + " " + login.code.slice(3));
  const left = Math.max(0, login.seconds | 0);
  setText($("expires"), left > 0
    ? `Действует ещё ${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")}`
    : "Код истёк — получите новый");
}

function buildRow(server) {
  const row = document.createElement("div");
  row.className = "server";
  row.innerHTML =
    '<div><div class="name"></div><div class="type"></div></div><span class="ping hidden"></span>';
  row.onclick = () => select(server.tag);
  return row;
}

function renderServers(s) {
  const box = $("servers");
  const servers = s.servers || [];

  if (!servers.length) {
    if (!box.firstElementChild || !box.firstElementChild.classList.contains("empty")) {
      box.innerHTML = '<div class="muted empty">Серверов пока нет</div>';
      rows = new Map();
    }
  } else {
    if (box.firstElementChild && box.firstElementChild.classList.contains("empty")) {
      box.innerHTML = "";
      rows = new Map();
    }

    const seen = new Set();
    servers.forEach((server, index) => {
      seen.add(server.tag);
      let row = rows.get(server.tag);
      if (!row) {
        row = buildRow(server);
        rows.set(server.tag, row);
      }
      // Порядок мог поменяться после сортировки.
      if (box.children[index] !== row) box.insertBefore(row, box.children[index] || null);

      row.classList.toggle("selected", server.tag === s.selected);
      setText(row.querySelector(".name"), server.remark || server.address);
      setText(row.querySelector(".type"),
        `${server.protocol} / ${server.network}${server.security === "tls" ? " / tls" : ""}`);

      // Рамку рисуем только после замера: пустая обводка выглядела бы
      // поломкой.
      const badge = row.querySelector(".ping");
      badge.classList.toggle("hidden", !server.ping);
      badge.classList.toggle("bad", server.ping < 0);
      if (server.ping) {
        setText(badge, server.ping < 0 ? "нет ответа" : `${server.ping} мс`);
      }
    });

    for (const [tag, row] of rows) {
      if (!seen.has(tag)) {
        row.remove();
        rows.delete(tag);
      }
    }
  }

  setText($("panel-title"), s.busy || (s.connected ? "Подключено" : "Не подключено"));
  setText($("panel-sub"), s.connected && !s.busy
    ? `Весь трафик идёт через выбранный сервер · ${clock(s.seconds)}`
    : "");

  const button = $("panel-btn");
  setText(button, s.connected ? "Отключить" : "Подключить");
  button.disabled = !!s.busy;
  $("power").disabled = !!s.busy;
}

function clock(seconds) {
  const s = Math.max(0, seconds | 0);
  const pad = (n) => String(n).padStart(2, "0");
  return `${pad(Math.floor(s / 3600))}:${pad(Math.floor(s / 60) % 60)}:${pad(s % 60)}`;
}

function plural(n, one, few, many) {
  const mod10 = n % 10, mod100 = n % 100;
  if (mod10 === 1 && mod100 !== 11) return one;
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) return few;
  return many;
}

let noticeTimer = null;

function showNotice(text) {
  const box = $("notice");
  box.textContent = text;
  box.classList.add("show");
  clearTimeout(noticeTimer);
  // Длинным сообщениям — больше времени: в них теперь настоящая причина.
  noticeTimer = setTimeout(() => box.classList.remove("show"),
    Math.min(9000, 2600 + text.length * 40));
}

// --- Действия --------------------------------------------------------

function go(tab) {
  for (const section of document.querySelectorAll(".tab")) section.classList.add("hidden");
  $("tab-" + tab).classList.remove("hidden");
  for (const button of document.querySelectorAll(".nav")) {
    button.classList.toggle("active", button.dataset.tab === tab);
  }
  if (tab === "settings") loadLog();

  // Подписку спрашиваем у сервера, а не берём из состояния: она
  // меняется не здесь, а в панели, и пока экран открыт, человек должен
  // видеть, что с его чеком. В остальное время дёргать сервер незачем.
  clearInterval(renewTimer);
  renewTimer = null;
  if (tab === "renew") {
    loadRenew();
    renewTimer = setInterval(loadRenew, 10000);
  }
}

async function toggle() {
  if (!current || current.busy) return;
  const answer = await call(current.connected ? "/api/disconnect" : "/api/connect");
  if (answer && answer.error) showNotice(answer.error);
}

async function select(tag) { await call("/api/select?tag=" + encodeURIComponent(tag)); }
async function sortBy(by) { await call("/api/sort?by=" + by); }
async function pingAll() { await call("/api/ping"); }
async function refresh() { await call("/api/refresh"); }

async function showCode() {
  const answer = await call("/api/login");
  if (answer && answer.error) showNotice(answer.error);
}

async function toggleRu() {
  if (!current) return;
  await busyWhile("chip-ru", () =>
    call("/api/rudirect?on=" + (current.ru_direct ? "0" : "1")));
}

// Ссылку открывает сама программа: окну известна одна-единственная
// ручка, и открыть через неё что-то другое нельзя.
async function openSource() {
  await call("/api/source");
}

async function toggleVPN() {
  if (!current) return;
  const answer = await busyWhile("chip-vpn", () =>
    call("/api/vpnmode?on=" + (current.vpn_mode ? "0" : "1")));
  if (answer && answer.error) showNotice(answer.error);
}

// Пока запрос идёт, плашка гаснет и не нажимается: смена режима
// перезапускает ядро и спрашивает пароль, и второе нажатие посреди
// этого ничем хорошим не кончится.
async function busyWhile(id, action) {
  const chip = $(id);
  if (chip) chip.classList.add("busy");
  try {
    return await action();
  } finally {
    if (chip) chip.classList.remove("busy");
  }
}

// Журнал показываем прямо в настройках: «что-то не работает» должно
// превращаться в текст, который можно прочитать и переслать.
async function loadLog() {
  const answer = await call("/api/log");
  if (!answer || !answer.lines) return;
  setText($("log-path"), answer.path);
  const box = $("log");
  box.textContent = answer.lines.slice(-12).join("\n") || "пока пусто";
}

// --- Подписка --------------------------------------------------------
//
// Продление устроено как в боте: тариф, оплата, чек. Начисляет его
// человек, который чек смотрит, — или само, если он промолчит. Поэтому
// экран умеет две вещи: показать срок и показать, что с последним чеком.

let renew = null;      // последний ответ бэкенда
let renewTimer = null; // опрос, пока экран открыт
let chosen = null;     // выбранный тариф или своя сумма

// Статусы, при которых заявка ещё жива и просить второй раз незачем.
const OPEN = ["pending", "processing", "manual", "failed"];

async function loadRenew() {
  const answer = await call("/api/renew");
  if (!answer || !answer.ok) {
    renewFailed(answer && answer.error ? answer.error : "нет связи");
    return;
  }

  const before = renew && renew.request ? renew.request.status : "";
  renew = answer.renew;
  renderRenew();

  // Заявку применили, пока экран открыт: перечитываем подписку, иначе
  // «осталось дней» на главной останется прежним до перезапуска.
  const now = renew.request ? renew.request.status : "";
  if (before && before !== now && (now === "approved" || now === "auto_approved")) {
    await call("/api/refresh");
  }
}

// Сервер не ответил — показываем причину и убираем всё остальное:
// тарифы из прошлого ответа и кнопка оплаты здесь только соврут.
function renewFailed(reason) {
  setText($("sub-left"), "Не удалось спросить подписку: " + reason);
  setText($("sub-traffic"), "Попробуйте позже. Подключение к VPN для этого не нужно.");
  setText($("sub-key"), "");
  for (const id of ["renew-pick", "renew-pay", "renew-state", "renew-off"]) {
    $(id).classList.add("hidden");
  }
}

function renderRenew() {
  const sub = renew.subscription;
  if (!sub) {
    setText($("sub-left"), "Ключа нет");
    setText($("sub-traffic"), "");
    setText($("sub-key"), "");
  } else {
    setText($("sub-left"), leftText(sub));
    setText($("sub-traffic"), trafficText(sub));
    setText($("sub-key"), "Ключ: " + sub.username);
  }

  const request = renew.request;
  const waiting = request && OPEN.includes(request.status);

  $("renew-off").classList.toggle("hidden", renew.renew_enabled);
  $("renew-pick").classList.toggle("hidden", !renew.renew_enabled || waiting);
  $("renew-state").classList.toggle("hidden", !request);
  if (request) renderRequest(request);

  // Пока заявка жива, выбирать новый тариф не из чего.
  if (waiting) $("renew-pay").classList.add("hidden");

  renderTiers();
}

function leftText(sub) {
  if (sub.starts_on_first_use) return "Срок начнётся с первого подключения";
  if (sub.days_left === null || sub.days_left === undefined) return "Срок не ограничен";
  if (sub.days_left <= 0) return "Срок вышел";
  return `Осталось ${sub.days_left} ${plural(sub.days_left, "день", "дня", "дней")}`;
}

function trafficText(sub) {
  if (!sub.data_limit) return `Потрачено ${gb(sub.used_traffic)} ГБ, лимита нет`;
  const left = Math.max(0, sub.data_limit - sub.used_traffic);
  return `Трафика осталось ${gb(left)} ГБ из ${gb(sub.data_limit)} ГБ`;
}

function gb(bytes) {
  const value = (bytes || 0) / (1024 * 1024 * 1024);
  return value >= 10 ? Math.round(value) : Math.round(value * 10) / 10;
}

function renderRequest(request) {
  const sum = request.amount ? `на ${request.amount} ₽` : "";
  const minutes = renew.auto_approve_minutes;

  let title = "Чек отправлен";
  let text = "";

  switch (request.status) {
    case "pending":
    case "processing":
      text = request.delivered
        ? `Чек ${sum} у нас, ждём подтверждения.` +
          (minutes > 0 && request.days ? ` Если никто не ответит, продление применится само через ${minutes} мин.` : "")
        : `Чек ${sum} отправляем…`;
      break;
    case "manual":
      title = "Сумму проверяют";
      text = "Срок по своей сумме назначают вручную — это не мгновенно.";
      break;
    case "failed":
      title = "Продлить не получилось";
      text = "Чек у нас, но начисление не прошло. С этим уже разбираются.";
      break;
    case "approved":
    case "auto_approved":
      title = "Продлено";
      text = request.days
        ? `Добавили ${request.days} ${plural(request.days, "день", "дня", "дней")}` +
          (request.gb ? ` и ${request.gb} ГБ.` : ".")
        : "Подписка продлена.";
      break;
    case "rejected":
      title = "Чек отклонён";
      text = "Если это ошибка, напишите в поддержку.";
      break;
    case "rolled_back":
      title = "Продление откатили";
      text = "Если это ошибка, напишите в поддержку.";
      break;
    default:
      text = "";
  }

  setText($("renew-state-title"), title);
  setText($("renew-state-text"), text);
}

function renderTiers() {
  const box = $("tiers");
  box.innerHTML = "";

  for (const tier of renew.tiers || []) {
    const card = document.createElement("div");
    card.className = "card tier";
    card.onclick = () => pickTier(tier);
    card.innerHTML =
      `<div>${tier.emoji || ""} ${tier.days} ${plural(tier.days, "день", "дня", "дней")}` +
      `${tier.gb ? ` + ${tier.gb} ГБ` : ""}</div>` +
      `<div class="amount">${tier.amount} ₽</div>`;
    box.appendChild(card);
  }
}

function pickTier(tier) {
  chosen = { tier: tier.index, amount: tier.amount };
  showPay(`Оплата ${tier.amount} ₽ — ${tier.days} ${plural(tier.days, "день", "дня", "дней")}` +
    (tier.gb ? ` и ${tier.gb} ГБ` : ""));
}

function showPay(title) {
  setText($("pay-title"), title);
  setText($("pay-how"), renew.pay_url
    ? "Оплатите по ссылке, затем приложите чек — по нему продление и начислят. Подойдёт любой файл: снимок экрана, фотография, pdf из банка."
    : "Оплатите по реквизитам ниже, затем приложите чек: снимок экрана, фотографию или pdf из банка.");

  const requisites = $("pay-requisites");
  requisites.classList.toggle("hidden", !renew.requisites);
  if (renew.requisites) requisites.textContent = renew.requisites;

  $("pay-open").classList.toggle("hidden", !renew.pay_url);
  $("renew-pick").classList.add("hidden");
  $("renew-pay").classList.remove("hidden");
}

function backToTiers() {
  chosen = null;
  $("renew-pay").classList.add("hidden");
  $("renew-pick").classList.remove("hidden");
}

async function openPay() {
  const answer = await call("/api/renew/pay");
  if (answer && answer.error) showNotice(answer.error);
}

function pickReceipt() { $("receipt").click(); }

// Чек читаем прямо здесь и отправляем base64: окно — это браузер, и
// путь к файлу ему виден не всегда, а содержимое — всегда. Файл любой:
// у кого-то снимок экрана, у кого-то pdf из банка.
async function uploadReceipt(input) {
  const file = input.files && input.files[0];
  input.value = "";
  if (!file || !chosen) return;

  // Полсотни мегабайт — потолок Telegram, которым чек уходит админам.
  if (file.size > 50 * 1024 * 1024) {
    showNotice("Файл больше 50 МБ — пришлите чек поменьше");
    return;
  }

  const image = await new Promise((resolve) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result).split(",")[1] || "");
    reader.onerror = () => resolve("");
    reader.readAsDataURL(file);
  });

  if (!image) {
    showNotice("Не удалось прочитать файл");
    return;
  }

  const answer = await call("/api/renew/receipt", { ...chosen, image, name: file.name });
  if (!answer || !answer.ok) {
    showNotice(answer && answer.error ? answer.error : "Чек не отправился");
    return;
  }

  // В ответ на чек бэкенд присылает только судьбу заявки — тарифы и
  // подписку он для этого не перечитывает. Берём из ответа заявку, а
  // остальное обновляем обычным запросом.
  chosen = null;
  if (answer.renew && answer.renew.request) renew.request = answer.renew.request;
  $("renew-pay").classList.add("hidden");
  renderRenew();
  loadRenew();
}

async function quit() {
  await call("/api/quit");
  document.body.innerHTML =
    '<div class="screen"><div class="muted">Программа закрыта. Окно можно закрыть.</div></div>';
}

poll();
