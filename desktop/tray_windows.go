//go:build windows

package main

import (
	"os"
	"runtime"
	"sync"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

// Значок в трее.
//
// Без него программа работает невидимо: окно — это чужой браузер, и
// закрыв его, человек остаётся с запущенным ядром и включённым системным
// прокси, не имея ни малейшего способа это заметить или выключить.
//
// Написан голыми вызовами Windows, без сторонних библиотек: все они
// тянут C-зависимость, а с ней пропадает кросс-сборка — собирать стало
// бы негде.

var (
	kernel32 = windows.NewLazySystemDLL("kernel32.dll")
	shell32t = windows.NewLazySystemDLL("shell32.dll")

	getModuleHandle  = kernel32.NewProc("GetModuleHandleW")
	registerClassEx  = user32.NewProc("RegisterClassExW")
	createWindowEx   = user32.NewProc("CreateWindowExW")
	defWindowProc    = user32.NewProc("DefWindowProcW")
	destroyWindow    = user32.NewProc("DestroyWindow")
	getMessage       = user32.NewProc("GetMessageW")
	translateMessage = user32.NewProc("TranslateMessage")
	dispatchMessage  = user32.NewProc("DispatchMessageW")
	postQuitMessage  = user32.NewProc("PostQuitMessage")
	postMessage      = user32.NewProc("PostMessageW")
	createPopupMenu  = user32.NewProc("CreatePopupMenu")
	appendMenu       = user32.NewProc("AppendMenuW")
	destroyMenu      = user32.NewProc("DestroyMenu")
	trackPopupMenu   = user32.NewProc("TrackPopupMenu")
	setForegroundWnd = user32.NewProc("SetForegroundWindow")
	getCursorPos     = user32.NewProc("GetCursorPos")
	setTimer         = user32.NewProc("SetTimer")
	loadCursor       = user32.NewProc("LoadCursorW")
	shellNotifyIcon  = shell32t.NewProc("Shell_NotifyIconW")
	extractIcon      = shell32t.NewProc("ExtractIconW")
)

const (
	wmDestroy     = 0x0002
	wmClose       = 0x0010
	wmCommand     = 0x0111
	wmTimer       = 0x0113
	wmRButtonUp   = 0x0205
	wmLButtonUp   = 0x0202
	wmLDblClick   = 0x0203
	wmTrayMessage = 0x0400 + 1 // WM_USER + 1

	nimAdd    = 0x0
	nimModify = 0x1
	nimDelete = 0x2

	nifMessage = 0x1
	nifIcon    = 0x2
	nifTip     = 0x4
	nifInfo    = 0x10

	niifInfo = 0x1

	menuOpen = 1001
	menuQuit = 1002

	tpmLeftAlign   = 0x0000
	tpmRightButton = 0x0002
)

type wndClassEx struct {
	Size       uint32
	Style      uint32
	WndProc    uintptr
	ClsExtra   int32
	WndExtra   int32
	Instance   syscall.Handle
	Icon       syscall.Handle
	Cursor     syscall.Handle
	Background syscall.Handle
	MenuName   *uint16
	ClassName  *uint16
	IconSm     syscall.Handle
}

type notifyIconData struct {
	Size            uint32
	Wnd             syscall.Handle
	ID              uint32
	Flags           uint32
	CallbackMessage uint32
	_               uint32
	Icon            syscall.Handle
	Tip             [128]uint16
	State           uint32
	StateMask       uint32
	Info            [256]uint16
	TimeoutVersion  uint32
	InfoTitle       [64]uint16
	InfoFlags       uint32
	GuidItem        [16]byte
	BalloonIcon     syscall.Handle
}

type point struct{ X, Y int32 }

type msg struct {
	Wnd     syscall.Handle
	Message uint32
	WParam  uintptr
	LParam  uintptr
	Time    uint32
	Pt      point
	Private uint32
}

var (
	trayWnd  syscall.Handle
	trayIcon syscall.Handle
	trayOn   bool
	trayTip  string
)

// startTray — заводит значок. false — не вышло, и программа переходит
// к запасному способу закрываться.
func startTray() bool {
	ready := make(chan bool, 1)

	go func() {
		// Окно и его очередь сообщений обязаны жить на одном потоке.
		runtime.LockOSThread()
		defer runtime.UnlockOSThread()

		defer func() {
			if problem := recover(); problem != nil {
				logf("значок в трее сорвался: %v", problem)
				select {
				case ready <- false:
				default:
				}
			}
		}()

		if !createTrayWindow() {
			ready <- false
			return
		}
		ready <- true
		runMessageLoop()
	}()

	select {
	case ok := <-ready:
		trayOn = ok
		return ok
	case <-time.After(3 * time.Second):
		return false
	}
}

func createTrayWindow() bool {
	instance, _, _ := getModuleHandle.Call(0)
	className, _ := syscall.UTF16PtrFromString("vvTrayWindow")

	const idcArrow = 32512
	cursor, _, _ := loadCursor.Call(0, uintptr(idcArrow))

	class := wndClassEx{
		WndProc:   syscall.NewCallback(trayWndProc),
		Instance:  syscall.Handle(instance),
		Cursor:    syscall.Handle(cursor),
		ClassName: className,
	}
	class.Size = uint32(unsafe.Sizeof(class))

	if atom, _, _ := registerClassEx.Call(uintptr(unsafe.Pointer(&class))); atom == 0 {
		logf("класс окна для значка не зарегистрировался")
		return false
	}

	title, _ := syscall.UTF16PtrFromString(brandTitle)
	wnd, _, _ := createWindowEx.Call(
		0,
		uintptr(unsafe.Pointer(className)),
		uintptr(unsafe.Pointer(title)),
		0, 0, 0, 0, 0,
		0, 0, instance, 0,
	)
	if wnd == 0 {
		logf("окно для значка не создалось")
		return false
	}
	trayWnd = syscall.Handle(wnd)

	// Значок берём из самой программы: отдельный файл с иконкой возить
	// незачем.
	if exe, err := os.Executable(); err == nil {
		path, _ := syscall.UTF16PtrFromString(exe)
		icon, _, _ := extractIcon.Call(instance, uintptr(unsafe.Pointer(path)), 0)
		if icon > 1 {
			trayIcon = syscall.Handle(icon)
		}
	}

	data := newIconData()
	data.Flags = nifMessage | nifTip
	if trayIcon != 0 {
		data.Flags |= nifIcon
	}
	if ok, _, _ := shellNotifyIcon.Call(nimAdd, uintptr(unsafe.Pointer(&data))); ok == 0 {
		logf("значок не добавился в трей")
		return false
	}

	// Подпись значка обновляем по таймеру: лезть в Windows из чужого
	// потока нельзя, а состояние меняется само по себе.
	setTimer.Call(wnd, 1, 2000, 0)
	return true
}

func newIconData() notifyIconData {
	data := notifyIconData{
		Wnd:             trayWnd,
		ID:              1,
		CallbackMessage: wmTrayMessage,
		Icon:            trayIcon,
	}
	data.Size = uint32(unsafe.Sizeof(data))
	copyTip(&data, trayTitle())
	return data
}

func trayTitle() string {
	if coreRunning() {
		return brandTitle + " — подключено"
	}
	return brandTitle + " — не подключено"
}

// Уведомление ждёт своей очереди: показать его может только поток
// окна, а приходит новость из фоновой горутины. Забирает его тот же
// таймер, что обновляет подпись значка, — раз в две секунды.
var pendingNote struct {
	sync.Mutex
	title, text string
}

// notify — всплывающее уведомление у значка. Без значка показать его
// нечем: окно у программы чужое, а своего она не заводит.
func notify(title, text string) {
	logf("%s: %s", title, text)
	if !trayOn {
		return
	}

	pendingNote.Lock()
	pendingNote.title, pendingNote.text = title, text
	pendingNote.Unlock()
}

// showPendingNote — вызывается из потока окна.
func showPendingNote() {
	pendingNote.Lock()
	title, text := pendingNote.title, pendingNote.text
	pendingNote.title, pendingNote.text = "", ""
	pendingNote.Unlock()

	if text == "" {
		return
	}

	data := newIconData()
	data.Flags = nifInfo
	data.InfoFlags = niifInfo
	copyUTF16(data.InfoTitle[:], title)
	copyUTF16(data.Info[:], text)
	shellNotifyIcon.Call(nimModify, uintptr(unsafe.Pointer(&data)))
}

func copyTip(data *notifyIconData, text string) {
	copyUTF16(data.Tip[:], text)
}

// copyUTF16 — строка в поле фиксированной длины, с обязательным нулём
// на конце: Windows читает такие поля до него.
func copyUTF16(field []uint16, text string) {
	encoded, err := syscall.UTF16FromString(text)
	if err != nil {
		return
	}
	if len(encoded) > len(field) {
		encoded = encoded[:len(field)-1]
		encoded = append(encoded, 0)
	}
	copy(field, encoded)
}

func trayWndProc(wnd syscall.Handle, message uint32, wparam, lparam uintptr) uintptr {
	switch message {
	case wmTrayMessage:
		switch uint32(lparam) {
		case wmLButtonUp, wmLDblClick:
			go openWindow()
		case wmRButtonUp:
			showTrayMenu(wnd)
		}
		return 0

	case wmTimer:
		if title := trayTitle(); title != trayTip {
			trayTip = title
			data := newIconData()
			data.Flags = nifTip
			shellNotifyIcon.Call(nimModify, uintptr(unsafe.Pointer(&data)))
		}
		showPendingNote()
		return 0

	case wmCommand:
		switch wparam & 0xffff {
		case menuOpen:
			go openWindow()
		case menuQuit:
			go shutdown()
		}
		return 0

	case wmClose:
		destroyWindow.Call(uintptr(wnd))
		return 0

	case wmDestroy:
		postQuitMessage.Call(0)
		return 0
	}

	result, _, _ := defWindowProc.Call(uintptr(wnd), uintptr(message), wparam, lparam)
	return result
}

func showTrayMenu(wnd syscall.Handle) {
	menu, _, _ := createPopupMenu.Call()
	if menu == 0 {
		return
	}
	defer destroyMenu.Call(menu)

	const mfString = 0x0
	open, _ := syscall.UTF16PtrFromString("Открыть окно")
	quit, _ := syscall.UTF16PtrFromString("Выйти")
	appendMenu.Call(menu, mfString, menuOpen, uintptr(unsafe.Pointer(open)))
	appendMenu.Call(menu, mfString, menuQuit, uintptr(unsafe.Pointer(quit)))

	var cursor point
	getCursorPos.Call(uintptr(unsafe.Pointer(&cursor)))

	// Без этих двух строк меню не закрывается по щелчку мимо него —
	// так устроен TrackPopupMenu.
	setForegroundWnd.Call(uintptr(wnd))
	trackPopupMenu.Call(menu, tpmLeftAlign|tpmRightButton,
		uintptr(cursor.X), uintptr(cursor.Y), 0, uintptr(wnd), 0)
	postMessage.Call(uintptr(wnd), 0, 0, 0)
}

func runMessageLoop() {
	var message msg
	for {
		result, _, _ := getMessage.Call(uintptr(unsafe.Pointer(&message)), 0, 0, 0)
		if int32(result) <= 0 {
			return
		}
		translateMessage.Call(uintptr(unsafe.Pointer(&message)))
		dispatchMessage.Call(uintptr(unsafe.Pointer(&message)))
	}
}

func stopTray() {
	if !trayOn || trayWnd == 0 {
		return
	}
	data := newIconData()
	shellNotifyIcon.Call(nimDelete, uintptr(unsafe.Pointer(&data)))
	postMessage.Call(uintptr(trayWnd), wmClose, 0, 0)
}
