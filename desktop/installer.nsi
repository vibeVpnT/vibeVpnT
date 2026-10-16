; Установщик для Windows.
;
; Лежит внутри того же архива, что и сама программа: кто хочет ярлыки и
; запись в «Установку и удаление программ» — запускает setup.exe, кому
; это не нужно — просто vv.exe из распакованной папки.
;
; Ставим в Program Files и на всю машину, как это делают обычные
; установщики: ярлыки появляются у всех пользователей, а запись об
; удалении — в общем списке программ. За это Windows спросит права
; администратора при запуске setup.exe.
;
; Файлы установщик не носит внутри себя, а берёт из своей же папки: они
; лежат в том же архиве, рядом. Иначе архив распух бы вдвое — одни и те
; же шестьдесят мегабайт в нём лежали бы дважды.
;
; Собирается makensis'ом из pack.sh, значения приходят ключами -D.

Unicode true
RequestExecutionLevel admin
SetCompressor /SOLID lzma

!include "MUI2.nsh"

Name "${NAME}"
BrandingText "${NAME} ${VERSION}"
OutFile "${OUTFILE}"
InstallDir "$PROGRAMFILES64\${NAME}"
InstallDirRegKey HKLM "Software\${SLUG}" "InstallDir"

!define MUI_ICON "${ICON}"
!define MUI_UNICON "${ICON}"
!define MUI_FINISHPAGE_RUN "$INSTDIR\${SLUG}.exe"
!define MUI_FINISHPAGE_RUN_TEXT "Запустить ${NAME}"

!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "Russian"

!define UNINST_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\${SLUG}"

; Ярлык, который сразу просит права администратора.
;
; В Windows это не свойство программы, а флажок в самом ярлыке — бит
; 0x20 в байте 21 файла .lnk, тот самый «Запуск от имени администратора»
; из свойств ярлыка. Ставим его руками: плагина для этого в стандартной
; поставке NSIS нет, а байт есть байт.
!macro RunAsAdmin path
  ; Переходы относительные, без меток: макрос вставляется дважды, а
  ; одинаковые метки в одном скрипте NSIS не принимает.
  ClearErrors
  FileOpen $0 "${path}" a
  IfErrors +7
    FileSeek $0 21
    FileReadByte $0 $1
    IntOp $1 $1 | 0x20
    FileSeek $0 21
    FileWriteByte $0 $1
    FileClose $0
!macroend

Function .onInit
  ; Ярлыки и запись об удалении — общие для всех, кто сидит за машиной.
  SetShellVarContext all
  ; Сам установщик 32-разрядный, а программа и папка — 64. Без этого
  ; запись об удалении уехала бы в Wow6432Node, то есть в список
  ; 32-разрядных программ, где ей не место.
  SetRegView 64

  ; Установщик ставит то, что лежит рядом с ним. Запущенный в одиночку —
  ; из папки «Загрузки», без распаковки — он ставить нечего, и сказать
  ; об этом надо сразу, а не создать пустую папку с ярлыками.
  IfFileExists "$EXEDIR\${SLUG}.exe" ok 0
    MessageBox MB_ICONSTOP "Рядом с установщиком нет файлов программы.$\r$\n$\r$\nРаспакуйте архив целиком и запустите setup.exe из распакованной папки."
    Abort
  ok:
FunctionEnd

Section "Программа" SecMain
  SectionIn RO

  ; Работающую копию просим закрыться по-хорошему: иначе файл занят, а
  ; системный прокси останется висеть на порт, где никого не будет.
  nsExec::ExecToLog '"$INSTDIR\${SLUG}.exe" --quit'
  Sleep 800

  SetOutPath "$INSTDIR"
  CopyFiles /SILENT "$EXEDIR\${SLUG}.exe"     "$INSTDIR"
  CopyFiles /SILENT "$EXEDIR\xray.exe"        "$INSTDIR"
  CopyFiles /SILENT "$EXEDIR\tun2socks.exe"   "$INSTDIR"
  CopyFiles /SILENT "$EXEDIR\wintun.dll"      "$INSTDIR"
  CopyFiles /SILENT "$EXEDIR\geosite.dat"     "$INSTDIR"
  CopyFiles /SILENT "$EXEDIR\geoip.dat"       "$INSTDIR"

  CreateShortCut "$DESKTOP\${NAME}.lnk" "$INSTDIR\${SLUG}.exe"
  CreateDirectory "$SMPROGRAMS\${NAME}"
  CreateShortCut "$SMPROGRAMS\${NAME}\${NAME}.lnk" "$INSTDIR\${SLUG}.exe"
  CreateShortCut "$SMPROGRAMS\${NAME}\Удалить ${NAME}.lnk" "$INSTDIR\uninstall.exe"

  ; Оба ярлыка запускают программу сразу с правами: тогда режим VPN
  ; включается без второго окна UAC посреди работы.
  !insertmacro RunAsAdmin "$DESKTOP\${NAME}.lnk"
  !insertmacro RunAsAdmin "$SMPROGRAMS\${NAME}\${NAME}.lnk"

  WriteUninstaller "$INSTDIR\uninstall.exe"

  WriteRegStr HKLM "Software\${SLUG}" "InstallDir" "$INSTDIR"
  WriteRegStr HKLM "${UNINST_KEY}" "DisplayName" "${NAME}"
  WriteRegStr HKLM "${UNINST_KEY}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKLM "${UNINST_KEY}" "DisplayIcon" "$INSTDIR\${SLUG}.exe"
  WriteRegStr HKLM "${UNINST_KEY}" "Publisher" "${NAME}"
  WriteRegStr HKLM "${UNINST_KEY}" "UninstallString" '"$INSTDIR\uninstall.exe"'
  WriteRegStr HKLM "${UNINST_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegDWORD HKLM "${UNINST_KEY}" "NoModify" 1
  WriteRegDWORD HKLM "${UNINST_KEY}" "NoRepair" 1
SectionEnd

Function un.onInit
  SetShellVarContext all
  SetRegView 64
FunctionEnd

Section "Uninstall"
  nsExec::ExecToLog '"$INSTDIR\${SLUG}.exe" --quit'
  Sleep 800

  Delete "$INSTDIR\${SLUG}.exe"
  Delete "$INSTDIR\xray.exe"
  Delete "$INSTDIR\tun2socks.exe"
  Delete "$INSTDIR\wintun.dll"
  Delete "$INSTDIR\geosite.dat"
  Delete "$INSTDIR\geoip.dat"
  Delete "$INSTDIR\uninstall.exe"
  ; Остальное в папке — чужое, его не трогаем: RMDir без /r уберёт
  ; каталог, только если он пуст.
  RMDir "$INSTDIR"

  Delete "$DESKTOP\${NAME}.lnk"
  Delete "$SMPROGRAMS\${NAME}\${NAME}.lnk"
  Delete "$SMPROGRAMS\${NAME}\Удалить ${NAME}.lnk"
  RMDir "$SMPROGRAMS\${NAME}"

  DeleteRegKey HKLM "${UNINST_KEY}"
  DeleteRegKey HKLM "Software\${SLUG}"
SectionEnd
