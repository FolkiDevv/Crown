# Proposal

> **Категория:** скорость · **Приоритет:** средний · **Проверка:** воспроизведено на эмуляторе (замеры release-сборки; доля ядра
> в старте `:bg` — по коду) · **Код:** upstream (+ Crown в simple UI) · **Объём:** M

## Why

### Находка
`Application.onCreate` обоих процессов выполняет на главном потоке работу, которая не нужна для первого кадра или для первого
колбэка компонента.

**Основной процесс** (`SagerNet.kt:86-89`): `Theme.apply(this)`, `Theme.applyNightTheme()`, `AppLocale.apply()` читают
`DataStore.appTheme`, `amoledTheme`, `nightTheme`, `appLanguage` (`utils/Theme.kt:41-60,156-180`). Настройки хранятся в таблице
`settings` Room без кэша (`database/SettingsStore.kt:13-32`: каждое чтение — запрос), поэтому первое чтение **открывает БД** на
главном потоке (`SagerDatabase.kt:217-232`: создание `RoomDatabase`, `writableDatabase`, WAL, проверка схемы и миграции,
привязка к `MultiInstanceInvalidationService`); сборка это явно разрешает (`SagerDatabase.kt:209` `.allowMainThreadQueries()`).
Дальше по пути старта — ещё чтения из главного потока: `ThemedActivity.onCreate` (`ui/ThemedActivity.kt:40-45`), `SimpleTheme`
(`simple/SimpleTheme.kt:94-96`), `HomeViewModel.<init>` (`simple/home/HomeViewModel.kt:227-228`), `SimpleActivity.onResume`
(`simple/SimpleActivity.kt:430`).

**Процесс `:bg`** (`SagerNet.kt:59-68`): `updateNotificationChannels()`, `Widgets.push`, `CoreRuntime.setup(this)`
(`bg/CoreRuntime.kt:53-66`): `Seq.setContext` (загрузка `libthrone.so` — 91 МБ для x86_64 — и старт рантайма Go),
`CoreLog.newSession()` (открытие той же БД и **копирование** `core.log` в `core.log.prev` под файловой блокировкой,
`moe/matsuri/nb4a/utils/CoreLog.kt:148-162`), `Mobile.setup(...)`, три вызова в Go ради строки версии. Пока это не закончится,
система не вызывает в `:bg` ни `TileService`, ни ресиверы виджетов, ни `onStartCommand`.

`:bg` при этом поднимается гораздо чаще, чем нужен VPN: в нём живут плитка, ярлыки, виджеты, `BootReceiver`,
`RemoteWorkerService` WorkManager и `androidx.room.MultiInstanceInvalidationService` (`AndroidManifest.xml:418-519`). Последний
означает, что **каждый холодный старт UI поднимает и `:bg`** (`Start proc …:bg … for bound-service
androidx.room.MultiInstanceInvalidationService`) — с загрузкой ядра, хотя VPN выключен. Ядру из этих компонентов оно нужно
только сервису подключения, тестам и части воркеров.

### Влияние
Замеры (эмулятор Android 17, release-сборка `dev.folki.crown`, atrace холодного старта с пустой БД):
- главный поток UI: `bindApplication` 561 мс (из них ожидание CPU 255 мс, сон в binder-вызовах 167 мс); на главном потоке —
  открытие БД (`PRAGMA journal_mode` 50 мс, всё открытие ≈75 мс) и 26 чтений `settings` (13 мс);
- параллельно `:bg`: `bindApplication` 502 мс, из них ≈360 мс — после `makeApplication`, то есть в `SagerNet.onCreate` (разбивку
  внутри по трассе не видно: в коде нет своих секций; основная часть по коду — загрузка ядра); PSS `:bg` при выключенном VPN
  ≈68 МБ (код ≈50 МБ). Эти 0,5 с ЦП конкурируют со стартом UI;
- медиана холодного старта до первого кадра — 1,9–2,8 с (0–3000 серверов).
- debug+StrictMode, полный обход UI и подключения: 84 нарушения диска на главных потоках обоих процессов, сетевых — 0.
- Под нагрузкой одно чтение настройки доходит до 30–650 мс; сразу после загрузки системы тяжёлый `onCreate` `:bg` приводит к ANR
  «failed to complete startup» (см. `make-boot-autostart-reliable`).
- Плитка и виджет при выгруженном `:bg` ждут загрузки ядра перед первым откликом (величина не измерена отдельно).

### Как воспроизвести
1. Release-сборка; `adb shell am force-stop dev.folki.crown`.
2. Записать трассу (`atrace` или Perfetto: `sched freq am wm view dalvik binder_driver database`), запустить приложение с лаунчера.
3. В трассе: главный поток `dev.folki.crown` — `bindApplication` и SQLite-срезы внутри; процесс `dev.folki.crown:bg` —
   `bindApplication` и `Start proc … for bound-service … MultiInstanceInvalidationService` в logcat.
4. Для плитки: VPN выключен, `:bg` убит, открыть шторку с плиткой — время от `Start proc …:bg` до `TileService.onStartListening`.
5. StrictMode: debug-сборка с `ThreadPolicy` (`detectDiskReads/Writes/Network`, `penaltyLog`) в обоих процессах; пройти
   старт, подключение, экраны — сгруппировать нарушения по первому кадру приложения.

## What Changes
1. **(Рекомендуется) Ленивая загрузка ядра в `:bg`.** `CoreRuntime.setup` — однократная потокобезопасная инициализация
   (`ensureSetup()`), вызываемая на `coreDispatcher` перед первым реальным обращением к ядру: `ProxyInstance.init` в
   `connectingJob`, `CoreService` (тесты, подписки, диагностика), воркеры, которым нужно ядро. Плитка, виджеты, ярлыки, ресиверы и
   `MultiInstanceInvalidationService` поднимают `:bg` без ядра. В коде 26 файлов обращаются к `io.throneproj.mobile`/`libcore` —
   каждый путь нужно провести через барьер (риск ниже).
2. **`CoreLog.newSession` — в фоне, но при старте процесса** (журнал прошлого сеанса должен сохраниться и после падения):
   переименование файла вместо копирования там, где основной процесс не держит дескриптор; иначе — ротация под блокировкой вне
   главного потока.
3. **Основной процесс: оформление без Room.** Значения темы, ночного режима и языка зеркалировать в `SharedPreferences` при
   записи (их пишет только UI) и читать в `Theme.apply`/`AppLocale.apply`/`ThemedActivity`/`SimpleTheme` оттуда; на API 33+ язык
   уже хранит AppCompat.
4. **Прогрев БД в фоне** в начале `SagerNet.onCreate` (`runOnDefaultDispatcher { SagerDatabase.instance… }`), чтобы первое
   чтение на главном потоке не платило за открытие; `HomeViewModel.<init>` читает `serviceError`/`serviceMode` в
   `viewModelScope` на IO.
5. **`:bg` после подключения**: записи `currentProfile`, `runningProfiles`, `runningRouteId`, `SimpleHooks.onServiceStarted`
   (`BaseService.kt:676-685`) — через `withContext(Dispatchers.IO)`, сохраняя порядок «записать до `changeState(Connected)`».
6. **Мелкие вызовы на старте simple UI**: каналы уведомлений подписок создавать один раз на процесс, а не при каждом
   создании activity (`SubscriptionAlerts.ensureChannels`, `SimpleActivity.kt:223` — два binder-вызова
   `createNotificationChannel`); `SettingsViewModel` в конструкторе на главном потоке вызывает `SimpleUi.isUpgrade`
   (`SettingsViewModel.kt:94`), а тот — `packageManager.getPackageInfo` (`SimpleUi.kt:140-148`): вынести в фон или читать
   уже сохранённый признак `KEY_INSTALL_KIND`.

Проверка «до/после»: `bindApplication` обоих процессов (цель — `:bg` при выключенном VPN < 150 мс, UI без SQLite на главном
потоке), PSS `:bg` при выключенном VPN, медиана холодного старта (10 прогонов), время отклика плитки при выгруженном `:bg`,
StrictMode-обход без нарушений на путях старта.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- (нет) — поведение не меняется. Ограничение из `settings-storage`, «Typed settings access» («all reads SHALL reflect the latest
  persisted value across UI and service processes»), соблюдается: зеркало в `SharedPreferences` — только для ключей
  оформления, которые пишет один UI-процесс.

## Impact
- **Код:** `SagerNet.kt`, `bg/CoreRuntime.kt`, `moe/matsuri/nb4a/utils/CoreLog.kt`, `utils/Theme.kt`, `utils/AppLocale.kt`,
  `ui/ThemedActivity.kt`, `bg/BaseService.kt`, все точки входа в ядро; simple UI — `SimpleTheme.kt`, `HomeViewModel.kt`,
  `SimpleHooks.kt`.
- **Upstream:** почти всё — файлы upstream вне списков (`CoreRuntime.kt`, `CoreLog.kt`, `Theme.kt`, `ThemedActivity.kt`) — новые
  точки правки или патч в upstream; `SagerNet.kt` — точка идентичности форка (версия), блок инициализации к ней не относится;
  `BaseService.kt` — точка 6 с расширенным описанием. Simple UI — код Crown.
- **Ядро throne, сборка:** ядро не меняется, меняется момент вызова `Seq.setContext`/`Mobile.setup`.
- **Риски:** обращение к классам ядра до `ensureSetup()` упадёт или будет работать без контекста — нужен поиск всех точек входа
  и тест «каждый компонент `:bg` на холодном процессе»; первый кадр с неверной темой, если зеркало не заполнено (миграция:
  при отсутствии зеркала — одно чтение из Room, как сейчас); `CoreLog.newSession` нельзя откладывать до первого старта
  сервиса, иначе журнал упавшего сеанса перезапишется.
- **Проверка исправления:** замеры выше; инструментальный тест холодных стартов плитки, виджета, `BootReceiver`, воркера.
- **Внешние источники:**
  - App startup time (фазы `bindApplication`, рекомендации не выполнять тяжёлую работу в `Application.onCreate`):
    https://developer.android.com/topic/performance/vitals/launch-time
