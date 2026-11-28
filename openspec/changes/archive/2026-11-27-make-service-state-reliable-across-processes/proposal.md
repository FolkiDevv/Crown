# Proposal

> **Категория:** надёжность · **Приоритет:** высокий · **Проверка:** воспроизведено на эмуляторе ·
> **Код:** оба (Crown + upstream `SagerConnection`) · **Объём:** S

## Why

### Находка
В UI-процессе состояние VPN-сервиса — обычное поле в памяти, общее только по названию:

```kotlin
// app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt:24-26
// share service state in main & bg process
@Volatile
var serviceState = BaseService.State.Idle
```

В `:bg` его пишет сам сервис (`bg/BaseService.kt:138-141`, `changeState`). В UI-процессе — только колбэк
**смены** состояния привязанного `SagerConnection`:

```kotlin
// app/src/main/java/io/nekohasekai/sagernet/bg/SagerConnection.kt:69-72
override fun stateChanged(state: Int, profileName: String?, msg: String?) {
    if (state < 0) return // skip private
    val s = BaseService.State.values()[state]
    DataStore.serviceState = s
```

При привязке к уже работающему сервису текущее состояние в поле не попадает: `registerCallback`
(`BaseService.kt:190-199`) только регистрирует колбэк, `onServiceConnected` (`SagerConnection.kt:123-139`) ничего не пишет,
`binderDied` (`:149-156`) поле не сбрасывает. Классический интерфейс это компенсирует сам —
`MainActivity.changeState` пишет `DataStore.serviceState = state` (`ui/MainActivity.kt:440`), в том числе из
`onServiceConnected` (`:547`). Простой интерфейс держит состояние в собственном поле `HomeViewModel.serviceState`
(`simple/home/HomeViewModel.kt:123`, `:410-422`, `:468`) и в `DataStore` его не переносит.

Отсюда три вида рассинхрона в UI-процессе:
1. **Новый UI-процесс при работающем VPN** (Android выгрузил UI, VPN живёт в `:bg`): поле = `Idle`, пока сервис не сменит
   состояние.
2. **UI-процесс без привязки вовсе** — его поднимает ресивер кнопок уведомления «Вы вне зоны белых списков»
   (`simple/regime/WhitelistZoneNotifications.kt:89-110`, ресивер без `android:process`, `AndroidManifest.xml:476-478`):
   поле = `Idle`.
3. **После гибели `:bg`**: поле остаётся `Connected` до следующего старта VPN.

Главный потребитель — `TrafficModeApplier`, который по этому полю решает, перезапускать ли работающее ядро после выбора
сервера или режима:

```kotlin
// app/src/simple/java/io/nekohasekai/sagernet/simple/modes/TrafficModeApplier.kt:264-275
val state = DataStore.serviceState
if (!isRunning(state)) return                 // isRunning(Idle) = true по simple_connected_since
val quiet = state == BaseService.State.Connected && ...
if (quiet) reloadNow(message) else { Logs.i("$message — once the service is connected"); ... }
settle(DataStore.selectedProxy)
```

То же в `switchTo` (`:198-219`). При `Idle` перезагрузка откладывается до «Connected», а цикл `settle` (`:296-336`) раз в
секунду читает то же поле, видит не-`Connected` (`:312-315`) и через 30 с (`SETTLE_MS`, `:338`) молча заканчивается.
Перезагрузка так и не отправляется. Наблюдатель сети в `:bg` это не исправит: `applyRegime` сравнивает с уже записанными
UI-процессом значениями и получает `ServiceChange.NONE` (`simple/regime/NetworkRegimeWatcher.kt:425-426`).

Другие читатели поля в UI-процессе получают то же неверное значение: `ktx/Nets.kt:66` и `:149` (запросы приложения через
локальный прокси — после гибели `:bg` идут на закрытый порт вместо сообщения «профиль не запущен»), `ExitIp.kt:120`,
`RepairReportExport.kt:142` и `LogExport.kt:108` (состояние в отчётах).

### Влияние
- Пользователь меняет сервер («Сервер не отвечает» → выбрать другой) или режим после того, как Android выгрузил UI-процесс.
  Экран и главная показывают новый выбор («Защищено, Сервер: <новый>»), а ядро работает на старом сервере и старых правилах
  до ручного переподключения. Выбор живого сервера вместо упавшего не восстанавливает связь.
- Кнопки «Включить «Умный»» / «Включить «Авто»» в уведомлении «Вы вне зоны белых списков» убирают уведомление и меняют
  режим в настройках, но работающее ядро остаётся на маршрутизации белых списков — нарушение traffic-modes «Действия
  подсказки вне зоны белых списков». Уведомление приходит, когда пользователь не в приложении, то есть UI-процесс к нажатию
  обычно уже выгружен — это основной случай, а не редкий.
- После гибели `:bg` (паника ядра, нехватка памяти) с включённым «Запросы приложения через прокси» проверка обновлений и
  загрузка конфигурации из UI падают с «connection refused»; отчёты «Починки» и журнала пишут `service: connected`.
  По умолчанию эта опция выключена, поэтому эта часть — низкой серьёзности.

### Как воспроизвести
Эмулятор Android 17 (x86_64), debug-сборка коммита `0a0fcc4e`, простой интерфейс, подписка из двух локальных серверов,
уровень журнала `info` (`files/core.log`).

Сценарий А — кнопка уведомления:
1. «Настройки» → режим «Белые списки», подключиться. В журнале: `check #1 … -> normal`, затем
   `simple wl zone: configuration for whitelists (mode) in network … without whitelists`; приходит уведомление
   «You are outside the whitelist zone» с кнопками «Switch to Auto» / «Switch to Smart».
2. Свернуть приложение, `adb shell am kill dev.folki.crown.debug` (убивает только UI-процесс; `:bg` с foreground-сервисом жив).
3. В шторке нажать «Switch to Smart» (ресивер поднимает новый UI-процесс).
4. Фактически: в журнале
   `simple modes: «smart» applied, reload of the running service — once the service is connected` и
   `simple wl zone: switched to «smart» (configuration for whitelists left: true)`; уведомление исчезло. Через 60 с ни одной
   новой строки `Start:` / `ServiceStopTrace` — ядро продолжает работать с конфигурацией, запущенной до нажатия
   (единственный `Start:` — момент подключения), уведомление VPN по-прежнему «Auto · whitelists».

Сценарий Б — выбор сервера в новом UI-процессе (повторён четыре раза подряд):
1. Подключиться в «Авто», трафик идёт через сервер B.
2. Убить UI-процесс (`run-as … kill -9 <pid UI>`), открыть приложение, в «Серверах» выбрать сервер A.
3. Фактически: журнал `simple servers: server 8 chosen, applied once the service is connected` и больше ничего; по журналам
   серверов через 10/20/30/40/50 с: A = 0 запросов, B = 10/10/9/11/10. Главная: «Protected, Server: Local A».

Ожидаемо в обоих сценариях: перезапуск ядра на месте (`tun-close`, `Start:`) и трафик по новому выбору в течение нескольких секунд.

## What Changes
1. (рекомендуется) Сделать состояние сервиса в UI-процессе достоверным в самом `SagerConnection`: в `onServiceConnected`
   записывать `DataStore.serviceState` из `service.state` (AIDL `getState`), в `binderDied`/`onServiceDisconnected` —
   сбрасывать в `Idle`. Это то, что classic неявно делает через `MainActivity.changeState`; так поле станет верным для обоих
   интерфейсов, плитки и ярлыков. Чтобы не перетереть более свежее значение из колбэка, записывать прочитанное состояние
   только если колбэк `stateChanged` после регистрации ещё не приходил.
2. В `TrafficModeApplier` не опираться на поле, когда в процессе нет живой привязки (ресивер уведомления): перед решением о
   перезагрузке коротко привязаться к сервису и спросить `service.state` (как делает `QuickToggleShortcut`) — или отдать
   обработку кнопок уведомления в `:bg`, где состояние точное (тогда согласовать с блокировкой `TrafficModeApplier`, которая
   действует только внутри процесса).
3. Страховка в `:bg`: после любого `applyRegime` и по запросу проверки из UI сравнивать `runningRouteId`/`currentProfile`
   работающего ядра с сохранёнными `currentRouteId`/`selectedProxy` (как уже делает `pendingReload`,
   `NetworkRegimeWatcher.kt:442`) и перезагружать при расхождении — это ловит любой пропущенный UI-процессом reload.
4. `settle` по истечении срока при работающем сервисе не должен сдаваться молча: журналировать расхождение и, если сервис
   на деле `Connected`, отправить перезагрузку.

Проверка: сценарии А и Б — после нажатия/выбора в журнале `reload of the running service` (без «once the service is
connected»), `tun-close`/`Start:`, трафик через новый сервер ≤ 10 с, уведомление VPN показывает новый режим. JVM-тест на
`SagerConnection`: после `onServiceConnected` с сервисом в `Connected` поле равно `Connected`; после `binderDied` — `Idle`.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `proxy-connection`: «UI process binding» — уточнить, что UI-процесс после привязки к работающему сервису знает его
  текущее состояние до первого изменения, а после гибели сервиса показывает `Idle`; решения о перезагрузке не принимаются
  по устаревшему состоянию.
- `traffic-modes` (simple-ui-redesign): «Действия подсказки вне зоны белых списков» — сценарий «нажатие кнопки уведомления,
  когда приложение выгружено» перезапускает работающий VPN с выбранным режимом.

## Impact
- **Код:** `bg/SagerConnection.kt` (upstream), `simple/modes/TrafficModeApplier.kt`, `simple/regime/WhitelistZoneNotifications.kt`,
  `simple/regime/NetworkRegimeWatcher.kt`, `simple/home/HomeViewModel.kt`.
- **Upstream:** вариант 1 требует **новой точки правки** `SagerConnection.kt` (его нет в `TOUCH_POINTS`). Без неё —
  те же записи в коде Crown: `HomeViewModel.onServiceConnected`/`onBinderDied` и короткая привязка в `TrafficModeApplier`
  (пункты 2–4 — только код Crown).
- **Ядро throne, сборка, инструменты:** не затрагиваются.
- **Риски:** перезагрузка, ушедшая в старт, который ещё подключается, останавливает сервис (комментарий
  `TrafficModeApplier.kt:204-206`) — поэтому без привязки перезагрузку слать только при подтверждённом `Connected`.
  Каждый лишний перезапуск — окно, описанное в `keep-tunnel-during-in-place-restart`.
- **Проверка исправления:** сценарии А и Б выше; сценарий «убить `:bg`» — после него в UI-процессе `Idle`, запросы с
  «через прокси» дают «профиль не запущен».
