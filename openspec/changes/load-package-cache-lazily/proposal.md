# Proposal

> **Категория:** скорость · **Приоритет:** средний · **Проверка:** воспроизведено на эмуляторе · **Код:** upstream · **Объём:** S

## Why

### Находка
`SagerNet.onCreate` в **обоих** процессах сразу строит полный список установленных пакетов:

```kotlin
// app/src/main/java/io/nekohasekai/sagernet/SagerNet.kt:59, 80-83
if (isMainProcess || isBgProcess) {
    …
    runOnDefaultDispatcher {
        PackageCache.register()
        cleanWebview()
    }
}
```

`PackageCache.register()` (`utils/PackageCache.kt:74-103`) вешает приёмник `PACKAGE_ADDED/REMOVED` и вызывает `reload()`
(`PackageCache.kt:107-133`) — два тяжёлых binder-вызова в system_server:

```kotlin
app.packageManager.getInstalledPackages(PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.GET_PERMISSIONS)
…
app.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
```

Ответы идут постранично (`ParceledListSlice`), снимок хранится в памяти процесса, а приёмник перестраивает его после каждой
установки или обновления любого приложения (с задержкой 1 с на пачку, `PackageCache.kt:83-91`).

Кто снимок читает:
- **UI-процесс** — только классические экраны: `RouteProfileActivity.kt:305`, `RouteRuleActivity.kt:148,349`, `AppManagerActivity.kt:61`.
  `AppListActivity.kt:118` и `AppManagerActivity.kt:123` при открытии всё равно делают свежий `reload()`. Simple UI (интерфейс по
  умолчанию) `PackageCache` не использует вовсе (в `app/src/simple` — 0 обращений).
- **`:bg`** — колбэки ядра о владельце соединения (`moe/matsuri/nb4a/NativeInterface.kt:65-94`: `findConnectionOwner` →
  `packageNamesOf(uid)` → `PackageCache.snapshot()`). Они нужны, пока работает VPN: `route.find_process` включается при
  включённой статистике трафика (`outbound/config/ConfigGenerator.kt:1403`, по умолчанию включена).
- `ApplicationInfo.metaData` не читается нигде (поиск `.metaData` по `app/src` — 0): флаг `GET_META_DATA` только увеличивает
  объём передачи и памяти.

Загрузка и так умеет быть ленивой: `PackageCache.snapshot()` при пустом кэше вызывает `awaitLoadSync()` → `register()`
(`PackageCache.kt:136-153`).

При этом `:bg` поднимается и без VPN — при каждом холодном старте UI ради `androidx.room.MultiInstanceInvalidationService`, для
плитки, виджетов и воркеров. Каждый такой подъём повторяет выгрузку.

### Влияние
Замер (эмулятор Android 17, release-сборка, atrace `binder_driver`, холодный старт из лаунчера, 269 пакетов):

| Процесс | Вызов | Время | Ответ |
|---|---|---|---|
| UI, `DefaultDispatcher` | `getInstalledPackages` (+страницы) | 241 + 29 + 63 + 64 мс | 65 + 212 + 109 + 29 КБ |
| UI, `DefaultDispatcher` | `getInstalledApplications` (+страница) | 84 + 33 мс | 65 + 65 КБ |
| `:bg`, `DefaultDispatcher` | то же | 123 + 14 + 35 + 20 мс | 65 + 212 + 109 + 65 КБ |

Итого ≈1 МБ парселей и ≈0,7 с работы system_server на каждый холодный старт UI. Это идёт одновременно со стартом activity: главный
поток в тот же момент ждёт system_server в своих binder-вызовах (`bindApplication`, `activityStart` — по 75–88 мс; сон в binder —
167 мс из 561 мс `bindApplication`). На телефоне с 300–500 приложениями объём растёт пропорционально. Плюс перестройка в обоих
процессах при массовом обновлении приложений из магазина.

### Как воспроизвести
1. Release-сборка, simple UI. `adb shell am force-stop dev.folki.crown`.
2. `adb shell "atrace --async_start -b 24000 -c am binder_driver sched"`.
3. `adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n dev.folki.crown/io.nekohasekai.sagernet.ui.MainActivity`.
4. Через 5 с `adb shell "atrace --async_stop -b 24000 -c am binder_driver sched" > trace.txt`.
5. В трассе — `binder_transaction` от потоков `DefaultDispatch-*` обоих процессов приложения к system_server и
   `binder_transaction_alloc_buf … data_size=` 65–217 КБ. Ожидаемо: по серии на процесс на каждый холодный старт.

## What Changes
1. **(Рекомендуется) Убрать `PackageCache.register()` из `SagerNet.onCreate`.** Первая загрузка случится при первом
   `snapshot()`: в UI — при открытии классического экрана, которому он нужен (они и сейчас его ждут), в simple UI — никогда.
2. **`:bg`: прогрев при старте VPN, а не процесса.** В `BaseService.startProxy` (в фоне, параллельно сборке конфига) вызвать
   загрузку, если конфигурация будет искать владельца соединений (`find_process`, правила по приложениям, per-app proxy). Иначе
   первый вызов `findConnectionOwner` из потока ядра будет ждать загрузку в `runBlocking` (≈0,2–0,5 с) на первом соединении.
3. **Убрать `GET_META_DATA`** из `getInstalledApplications` — метаданные не используются.
4. Дешёвая частичная мера без п.1: в UI-процессе не загружать, пока выбран simple UI.

Проверка: та же трасса — в UI-процессе при холодном старте simple UI нет `getInstalledPackages`; в `:bg` при выключенном VPN их
нет, при подключении — одна серия; медиана 10 холодных стартов до/после; первое соединение после подключения не ждёт загрузку
(время до первого удачного запроса через VPN до/после).

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
(нет) — поведение не меняется, меняется только момент загрузки списка пакетов.

## Impact
- **Код:** `SagerNet.kt` (убрать вызов), `utils/PackageCache.kt` (флаг), `bg/BaseService.kt` (прогрев при старте VPN).
- **Upstream:** `SagerNet.kt` — в списках только как точка идентичности форка (версия), строка `PackageCache.register()` к ней не
  относится — нужна новая точка правки; `PackageCache.kt` вне списков — новая точка; `BaseService.kt` — точка 6 с расширенным
  описанием. Лишняя выгрузка есть и в upstream — возможен патч туда.
- **Ядро throne, сборка:** не затрагиваются.
- **Риски:** приёмник `PACKAGE_ADDED/REMOVED` будет регистрироваться позже — до первой загрузки обновлять нечего; если в `:bg`
  найдётся ещё один потребитель снимка вне VPN — он просто подождёт первую загрузку, как сейчас при гонке старта.
- **Проверка исправления:** трасса выше; ручная проверка классических экранов выбора приложений и правил по приложениям;
  per-app proxy в VPN (allow/deny) работает с первого соединения.
- **Внешние источники:** не требуются.
