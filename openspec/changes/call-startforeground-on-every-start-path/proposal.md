# Proposal

> **Категория:** стабильность, надёжность · **Приоритет:** средний · **Проверка:** воспроизведено на эмуляторе (нет
> согласия на VPN); старт во время остановки — подтверждено по коду · **Код:** upstream · **Объём:** S–M

## Why

### Находка
Все входы запускают сервис подключения через `startForegroundService` (`SagerNet.startService()` →
`ContextCompat.startForegroundService`). Android требует, чтобы такой сервис вызвал `startForeground()` до
остановки, иначе процесс `:bg` падает. Код это знает и в одной ветке защищается уведомлением-заглушкой:

```kotlin
// app/src/main/java/io/nekohasekai/sagernet/bg/BaseService.kt:616-620
if (profile == null) { // gracefully shutdown: https://stackoverflow.com/q/47337857/2245107
    if (data.notification == null) data.notification = createNotification("")
    onNoProfile()
    stopRunner(false, getString(R.string.profile_empty))
```

Две ветки `onStartCommand` этого не делают.

**A. Нет согласия на VPN.**
```kotlin
// app/src/main/java/io/nekohasekai/sagernet/bg/VpnService.kt:100-110
if (DataStore.serviceMode == Key.MODE_VPN) {
    if (prepare(this) != null) {
        startActivity(Intent(this, VpnRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } else return super<BaseService.Interface>.onStartCommand(intent, flags, startId)
}
stopRunner()
return Service.START_NOT_STICKY
```
Сервис останавливается, так и не став foreground. Интерфейсы classic и simple сюда не попадают: они сначала
запрашивают согласие через `VpnRequestActivity.StartService`. Попадают внешние входы, которые вызывают
`SagerNet.startService()` без `VpnService.prepare()`: ярлык «Включить» (`ui/QuickEnableShortcut.kt:44-50`), ярлык и
виджет «Переключить» (`QuickToggleShortcut.kt`), плитка (`bg/TileService.kt:67-75`), автозапуск (`BootReceiver.kt`).
Согласия нет после свежей установки (пользователь сначала добавил виджет, плитку или ярлык) или после «Забыть VPN»
в системных настройках.

**B. Старт во время остановки.**
```kotlin
// app/src/main/java/io/nekohasekai/sagernet/bg/BaseService.kt:602-608
fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    DataStore.baseService = this
    if (data.state != State.Stopped) return Service.START_NOT_STICKY
    startProxy()
```
При окончательной остановке уведомление снимается в самом начале (`BaseService.kt:453-458`), затем идёт долгое
закрытие ядра. Новый `startForegroundService` в этом окне возвращается без `startForeground`, а в конце остановки
`stopSelf()` без `startId` (`BaseService.kt:524`) снимает и этот запрос: команда пользователя теряется, и `:bg`
падает по тому же механизму. Стартовать в Stopping могут выбор профиля на ТВ (`ConfigurationFragment.kt:669`: у
Stopping `started == false`), `VpnRequestActivity` после ответа на диалог, совпавшие по времени `BootReceiver` и
`PackageReplacedReceiver`. Главные кнопки обоих интерфейсов и плитка в Stopping заблокированы.

### Влияние
- A: пользователь без согласия на VPN нажимает ярлык, виджет или плитку — процесс `:bg` падает через ~4 секунды,
  VPN не включается. Поверх системного диалога согласия открывается экран отчёта о сбое с окном «Поделиться»
  журналом, так что пользователь видит падение вместо запроса разрешения. При автозапуске после перезагрузки
  `startActivity` из фонового сервиса запрещён: окна согласия нет, падение остаётся.
- B: редкая гонка, но с потерянной командой «подключить» и падением.
- Нарушаются обещания `proxy-connection` «Quick settings tile toggle» (плитка запускает выбранный профиль) и
  `app-widgets` «Cold-start toggle» (соединение всё равно запускается).

### Как воспроизвести
Debug-сборка `dev.folki.crown.debug`, эмулятор Android 17 (API 37).

1. `adb shell pm clear dev.folki.crown.debug`, пройти первый запуск, добавить сервер, не подключаться.
2. Убедиться, что согласия нет: `adb shell appops set dev.folki.crown.debug ACTIVATE_VPN default`
   (`pm clear` согласие не сбрасывает).
3. `adb shell am start -n dev.folki.crown.debug/io.nekohasekai.sagernet.ui.QuickEnableShortcut`.
4. Журнал:
   ```
   ActivityManager: Background started FGS: Allowed ... cmp=dev.folki.crown.debug/io.nekohasekai.sagernet.bg.VpnService
   ActivityTaskManager: START u0 {... cmp=.../VpnRequestActivity} ...
   ActivityManager: Bringing down service while still waiting for start foreground: ServiceRecord{... VpnService}
   AndroidRuntime: FATAL EXCEPTION: main
   AndroidRuntime: Process: dev.folki.crown.debug:bg, PID: 13395
   AndroidRuntime: android.app.RemoteServiceException$ForegroundServiceDidNotStartInTimeException:
       Context.startForegroundService() did not then call Service.startForeground()
   am_proc_died: [0,13395,dev.folki.crown.debug:bg,...]
   ```
   Между стартом сервиса и падением ~4 с. На экране — окно «Поделиться» с файлом журнала сбоя, под ним системный
   диалог «Connection request».

Путь B на эмуляторе без root не воспроизводится (сервис не экспортирован); подтверждается по коду. Для теста:
инструментальный тест, который вызывает `startForegroundService` сразу после `stopService` при подключённом профиле
и проверяет, что VPN поднялся и в `logcat -b crash` пусто.

## What Changes
1. **Каждая ветка `onStartCommand` сначала делает сервис foreground** (уведомление-заглушка, как в ветке
   `profile == null`), а уже потом решает, что делать. Заглушка сейчас показывается с типом `systemExempted`
   (`ServiceNotification.kt:279-284`), а он на API 34+ без согласия на VPN не разрешён — то есть как раз в ветке «нет
   согласия» `startForeground` может быть отклонён. Для этой ветки нужен тип, не требующий согласия (`shortService`
   на API 34+ или `specialUse`, см. `fix-proxy-mode-foreground-service-type`). Основная защита — п. 3: внешние
   входы проверяют согласие до `startService()`, и сервис без согласия не запускается вовсе.
2. Ветка «нет согласия» в `VpnService.onStartCommand`: вместо `startActivity` из сервиса показать уведомление
   «Нужно разрешение на VPN» с `PendingIntent` на `VpnRequestActivity`, затем `stopRunner`. Из фона прямой запуск
   activity не работает.
3. Внешние входы повторяют проверку, которая есть в интерфейсах: `QuickEnableShortcut` и `QuickToggleShortcut` —
   `VpnService.prepare()` перед `startService()` и при не-null запуск `VpnRequestActivity` (ярлык в этот момент на
   переднем плане); плитка — `startActivityAndCollapse(PendingIntent)` на `VpnRequestActivity` (API 34+).
4. Старт во время Stopping: вызвать `startForeground` с уведомлением «Переключение…» и поставить флаг «старт после
   остановки»; в конце `stopRunner` вместо `stopSelf()` запускать `startProxy()` (симметрично существующему
   `stopRequested`). Вызывать `stopSelf(startId)` последнего обработанного запроса, а не `stopSelf()`.

Проверка «до/после»: шаги 1–4 не дают записей в `logcat -b crash`; пользователь видит запрос согласия; при старте во
время остановки VPN поднимается.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `proxy-connection`: в «Connection state machine» и «External control actions» — запуск из любого входа без
  согласия на VPN не роняет фоновый процесс, а приводит к запросу согласия; запрос старта во время остановки
  выполняется после неё.
- `app-widgets`: в «Cold-start toggle» — то же для виджета без согласия на VPN.

## Impact
- **Код:** `bg/VpnService.kt`, `bg/BaseService.kt` (`onStartCommand`, конец `stopRunner`), `bg/TileService.kt`,
  `QuickToggleShortcut.kt`, `ui/QuickEnableShortcut.kt`.
- **Upstream:** все файлы — upstream. `BaseService.kt` уже содержит точку правки форка (вызов
  `SimpleHooks.onServiceStopped`), но эта правка в неё не ложится: нужна новая точка правки (или патч в upstream).
  Пересечения: экспортированные ярлыки — `restrict-exported-vpn-controls`; автозапуск — `make-boot-autostart-reliable`;
  экран отчёта поверх диалога после падения `:bg` — `recover-from-background-process-crash`.
- **Ядро throne, сборка, инструменты:** не затрагиваются.
- **Риски:** уведомление-заглушка на долю секунды появляется в шторке. Флаг «старт после остановки» должен
  сбрасываться, если пользователь сразу нажал «стоп», иначе VPN включится против воли.
- **Проверка исправления:** сценарий выше; инструментальный тест «старт во время остановки».
- **Внешние источники:** Android, «Troubleshoot foreground services»
  (https://developer.android.com/develop/background-work/services/fgs/troubleshooting), «Restrictions on starting
  activities from the background» (https://developer.android.com/guide/components/activities/background-starts).
