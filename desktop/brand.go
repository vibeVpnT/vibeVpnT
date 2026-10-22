package main

// Бренд приложения. Значения те же, что в brands/proxyvv.properties у
// мобильной версии: одно и то же приложение, разные платформы.
const (
	// brandName — имя для системы: каталоги (/opt/vv, ~/.config/vv),
	// пакет, исполняемый файл и команда `vv --quit`. Менять нельзя:
	// сломаются пути уже установленных копий и ссылки на файлы.
	brandName = "vv"
	// brandTitle — имя для людей: заголовки окон, уведомления, «О
	// приложении». Его и видно.
	brandTitle    = "VibeVPN"
	brandBackend  = "https://m.t-forge.online/proxyvv"
	brandScheme   = "proxyvv"
	brandSubAgent = "xray-client/1.0"
	brandSubName  = "ProxyVV"
	// brandSource — исходный код. Открывается из «О приложении»;
	// отдельной ручкой, а не ссылкой на странице, чтобы окно умело
	// открывать только её, а не что угодно.
	brandSource = "https://github.com/vibeVpnT/vibeVpnT"
	appVersion  = "2.6.2"
)
