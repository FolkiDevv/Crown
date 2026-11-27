# Proposal

> **Категория:** надёжность, скорость · **Приоритет:** средний · **Проверка:** воспроизведено на эмуляторе (debug-сборка,
> нагруженный эмулятор) · **Код:** upstream · **Объём:** M

## Why

### Находка
Автозапуск после перезагрузки («Подключаться при включении телефона») держится на одной попытке: `BootReceiver` получает
`BOOT_COMPLETED` и вызывает `SagerNet.startService()` (`BootReceiver.kt:23-35`). Повтора и уведомления о неудаче нет.

Ресивер объявлен в процессе `:bg` (`AndroidManifest.xml:451-455`, `android:process=":bg"`). Прежде чем доставить ему broadcast,
система создаёт процесс и выполняет `Application.onCreate`, где для `:bg` в главном потоке идёт тяжёлая инициализация:

```kotlin
// app/src/main/java/io/nekohasekai/sagernet/SagerNet.kt:59-68
if (isMainProcess || isBgProcess) {
    SettingsMapper.installOutboundHooks()
    if (isBgProcess) {
        updateNotificationChannels()
        Widgets.push(this)
        CoreRuntime.setup(this)          // запуск рантайма Go, загрузка libthrone.so, открытие БД
        AutoSelectorRuntime.install()
```

`CoreRuntime.setup` (`bg/CoreRuntime.kt:53-66`): `Seq.setContext(app)` (инициализация рантайма Go), `CoreLog.newSession()` →
`DataStore.<clinit>` (открытие Room-БД), `Mobile.setup(...)`. Сразу после загрузки CPU занят десятками получателей
`BOOT_COMPLETED`, и `bindApplication` процесса `:bg` может не уложиться в системный срок старта процесса: система фиксирует ANR
«failed to complete startup» и убивает процесс. Первым `:bg` после загрузки часто поднимает не ресивер, а привязка плитки
быстрых настроек (System UI) или Room (`MultiInstanceInvalidationService`) — они попадают в тот же тяжёлый `onCreate`.

### Влияние
Пользователь включил автоподключение и рассчитывает на VPN после перезагрузки, а VPN не поднимается, и об этом ничего не
сообщается. Наблюдалось на эмуляторе в 1 из 2 перезагрузок (наблюдение 4+ мин); во второй VPN поднялся через ≈71 с после
`sys.boot_completed`. Тот же тяжёлый `onCreate` удлиняет любое пробуждение `:bg` (плитка, виджет, WorkManager) —
см. `move-startup-work-off-main-thread`.

Ограничения данных: debug-сборка (в журнале — проверка байткода в рантайме по 100–850 мс на метод, release этого не делает),
эмулятор делил хост с другими эмуляторами (load average 15–30). Release-сборка на перезагрузку не проверялась. ANR того же вида
есть и в более ранних прогонах (в т.ч. у исходного upstream-приложения), то есть это не единичный случай, но частоту на реальных
телефонах нужно измерить.

Журнал (debug, Android 17, первая перезагрузка):
```
00:52:34.440 uc_send_user_broadcast: [0,android.intent.action.BOOT_COMPLETED]
00:53:49.723 am_proc_start: [0,3099,…,dev.folki.crown.debug:bg,bound-service,{…/TileService}]
00:54:20.031 am_anr: [0,3099,dev.folki.crown.debug:bg,…,Process … failed to complete startup]
00:54:22.677 am_proc_died: [0,3099,dev.folki.crown.debug:bg,…]
00:55:11.581 am_proc_start: [0,3998,…,dev.folki.crown.debug:bg,bound-service,{…/MultiInstanceInvalidationService}]
00:55:35.870 am_anr: [0,3998,dev.folki.crown.debug:bg,…,Process … failed to complete startup]
до 00:57+ — VPN нет
```
Стеки главного потока в ANR: `libthrone.so (Java_io_throneproj_mobile_Mobile__1init)` и
`DataStore.<clinit>` ← `CoreLog.refreshSettings` ← `CoreLog.newSession` ← `CoreRuntime.setup(CoreRuntime.kt:55)` ←
`SagerNet.onCreate(SagerNet.kt:67)` ← `ActivityThread.handleBindApplication`.

### Как воспроизвести
1. Debug- или release-сборка, «Подключаться при включении телефона» включено, VPN подключён (чтобы `BootReceiver` был включён).
2. `adb reboot`; дождаться `sys.boot_completed=1`.
3. Каждые 5 с `adb shell dumpsys connectivity | grep "VPN CONNECTED"`; параллельно `adb logcat -b events | grep -E "am_anr|am_proc_start|am_proc_died"`.
4. Наблюдать: `am_anr … :bg … failed to complete startup`, VPN не появляется. Повторить 5 раз; на слабом устройстве или под
   нагрузкой — чаще. Для release-сборки снять ту же серию: это главный недостающий замер.

## What Changes
1. **(Рекомендуется) Повтор и уведомление при неудаче автозапуска.** `BootReceiver` записывает в БД «автозапуск запрошен в
   момент T»; сервис снимает отметку при Connected. Отметку и повтор должен ставить код, который не зависит от старта
   `:bg`: в описанном ANR `:bg` умирает в `Application.onCreate` ещё до вызова ресивера. Поэтому перенос
   `BootReceiver` в основной процесс (п. 3) — обязательная часть этого пункта, а не отдельный вариант. Если через N секунд после загрузки отметка жива (процесс `:bg` убит, старт не
   удался), повторить запуск (отложенная задача WorkManager или `AlarmManager`) не больше 2–3 раз, затем показать уведомление
   «VPN не включился после перезагрузки — включить». Запуск VPN-сервиса из такой задачи на Android 12+ опирается на исключения
   из ограничений старта foreground-сервиса из фона (исключение из оптимизации батареи — его simple UI просит при первом
   запуске; в AOSP также исключение для приложения с выданным appop `ACTIVATE_VPN`) — нужна проверка на Android 14–17;
   при `ForegroundServiceStartNotAllowedException` — только уведомление.
2. **Облегчить старт `:bg`** (общая часть с `move-startup-work-off-main-thread`): вынести `CoreRuntime.setup` из
   `Application.onCreate` в фоновый поток с единым ожиданием готовности перед первым использованием ядра (старт сервиса, тест,
   `TileService` ядро не нужно); `CoreLog.newSession` не должен открывать БД в главном потоке.
3. **Перенос ресивера (нужен для п. 1; сам по себе — частичная мера)**: перенести `BootReceiver` в основной процесс — он только вызывает `startForegroundService`, а
   VPN-сервис в `:bg` стартует уже как foreground-сервис. Не помогает, если тяжёлым остаётся и `onCreate` основного процесса, и не
   спасает `:bg` от того же ANR при старте сервиса; поэтому — только вместе с п.1.
4. В «Починке» и описании автозапуска упомянуть системный «Постоянный VPN»: система сама поднимает его после загрузки.

Проверка: время `bindApplication` процесса `:bg` после загрузки (Perfetto, трек `bindApplication`) до/после; серия из 5
перезагрузок на release-сборке и на debug под нагрузкой — VPN поднимается каждый раз или пользователь получает уведомление.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `proxy-connection`: точки входа системы (boot receiver) — автозапуск после перезагрузки повторяется при неудаче ограниченное
  число раз и сообщает пользователю, если VPN так и не поднялся.

## Impact
- **Код:** `BootReceiver.kt`, `SagerNet.kt` (блок `isBgProcess`), `bg/CoreRuntime.kt`, `moe/matsuri/nb4a/utils/CoreLog.kt`,
  новая отложенная задача повтора; манифест — при переносе ресивера.
- **Upstream:** `BootReceiver.kt`, `CoreRuntime.kt`, `CoreLog.kt` вне списков — новые точки правки (или патч в upstream).
  `SagerNet.kt` — точка идентичности форка (версия), блок инициализации к ней не относится — тоже новая точка; манифест —
  точка 3 с расширенным описанием.
- **Ядро throne, сборка:** вызовы `Seq.setContext`/`Mobile.setup` переносятся во времени, ядро не меняется.
- **Риски:** ленивая инициализация ядра — гонки, если какой-то путь `:bg` вызовет ядро до готовности (нужен один барьер в
  `CoreRuntime`); повтор не должен поднимать VPN, если пользователь за это время выключил автоподключение или сам нажал «Отключить».
- **Проверка исправления:** серия перезагрузок выше; JVM-тест логики отметки и лимита повторов.
- **Внешние источники:**
  - Исключения из ограничений старта FGS из фона, в т.ч. `ACTION_BOOT_COMPLETED` и отключённая оптимизация батареи:
    https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
  - Android 15: ресиверам `BOOT_COMPLETED` запрещено запускать FGS типов dataSync, camera, mediaPlayback, phoneCall,
    mediaProjection, microphone (тип VPN-сервиса `systemExempted` в списке нет):
    https://developer.android.com/about/versions/15/behavior-changes-15
