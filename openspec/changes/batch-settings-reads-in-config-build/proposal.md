# Proposal

> **Категория:** скорость · **Приоритет:** средний · **Проверка:** воспроизведено на эмуляторе · **Код:** upstream · **Объём:** S–M

## Why

### Находка
Путь «Подключить» → `BaseService.startProxy` (`bg/BaseService.kt:612`) → `ProxyInstance.init` → `BoxInstance.buildConfig`
(`bg/proto/BoxInstance.kt:50-64`) → `CoreConfigs.buildMain` (`bg/proto/CoreConfigs.kt:59-72`) строит конфигурацию в потоке
`CoreLifecycle` процесса `:bg`. Два источника данных читаются по одной строке:

1. **Профили.** Провайдер профилей генератора читает каждый id отдельным запросом:
   ```kotlin
   // app/src/main/java/io/nekohasekai/sagernet/bg/proto/CoreConfigs.kt:35
   private fun row(id: Long): ProxyEntity? = rows.getOrPut(id) { SagerDatabase.proxyDao.getById(id) }
   ```
   Для автовыбора в конфигурацию входят до `AutoSelector.buildLimit = 300` участников, и каждый — это
   `SELECT * FROM profiles WHERE id = ?` с разбором `outbound_json`. Пакетный запрос уже есть —
   `ProxyDao.getEntities(ids)` (`database/ProxyEntity.kt:368-369`), но здесь не используется.
2. **Настройки.** `SettingsStore` намеренно без кэша (`database/preference/SettingsStore.kt:10-13`: «read without a cache»), и
   каждый геттер `DataStore.*` — отдельный `SELECT value FROM settings WHERE key = ?`. `SettingsMapper.generatorSettings` и
   `buildContext` читают десятки ключей, часть — повторно: `generatorSettings` вызывается и в `buildMain`, и по умолчанию в
   `generator()` (`CoreConfigs.kt:46-51`).

### Влияние
Замеры (эмулятор Android 17, release-сборка, локальный shadowsocks-сервер — сеть исключена). Время от нажатия «Подключить» до
`Vpn: Established … on tun0`:

| Сценарий | n | Медиана | min–max |
|---|---|---|---|
| Один локальный сервер | 8 | 3,1 с | 1,6–4,9 с |
| «Авто», подписка 3000 серверов | 5 | 6,0 с | 5,3–9,9 с |

atrace (`database`) одного подключения «Авто»/3000, процесс `:bg`: **304** запроса `profiles` по id (суммарно 459 мс, непрерывной
полосой ≈6,5 с вперемешку с остальной сборкой) и **136** запросов `settings` по ключу (510 мс суммарно: под конкуренцией с
другими потоками отдельный запрос доходит до сотни миллисекунд; часть этих чтений идёт уже после подключения). Параллельно
работает сборщик мусора — на каждый профиль создаётся `ProxyEntity` с разобранным outbound. Итого порядка 0,5–1 с из ≈3 с,
которые «Авто» по большой подписке добавляет к подключению (остальное — сборка и запуск 300 outbound в ядре). На медленном
телефоне — пропорционально больше.

### Как воспроизвести
1. Release-сборка, подписка из 3000 серверов на локальный тестовый сервер, режим «Авто», VPN выключен.
2. `adb shell "atrace --async_start -b 32000 -c am dalvik sched database"`.
3. Нажать «Подключить»; дождаться `Established by dev.folki.crown` в logcat.
4. `adb shell "atrace --async_stop -b 32000 -c am dalvik sched database" > t.txt`.
5. Посчитать у pid `:bg` пары `START-/END-executeForCursorWindow SELECT * FROM \`profiles\` WHERE \`id\` = ?` и
   `… SELECT \`value\` FROM \`settings\` WHERE \`key\` = ?` (поток `CoreLifecycle`): ожидаемо ≈300 и ≈140.
6. Для сравнения — один выбранный сервер: профилей по id единицы, настроек — порядка сотни.

## What Changes
1. **(Рекомендуется) Пакетная загрузка профилей.** Перед генерацией собрать id, которые точно понадобятся (участники
   автовыбора известны из `AutoSelectorPlan`, звенья цепочек — из профиля), и прочитать их `getEntities(ids)` кусками (≤ 900 id на
   запрос из-за лимита переменных SQLite на старых Android), складывая в тот же `rows`. Остальные id по-прежнему читаются по одному.
2. **Снимок настроек на одну генерацию.** Прочитать таблицу `settings` одним `SELECT key, value` (порядка сотни строк) в начале
   `buildMain` и отдать `SettingsMapper` как источник на время сборки (или режим «снимок на блок» в `SettingsStore`). Межпроцессная
   согласованность (`settings-storage`, «Typed settings access») не страдает: генерация и сейчас — чтение в один момент, а изменение
   настройки во время неё и так ведёт к перезапуску. Ключи, которые сборка сама пишет (`inboundSocksPort`, `coreBoxApiPort` в
   `BoxInstance.buildConfig`), нужно читать после записи.
3. Дешёвая частичная мера: не вызывать `generatorSettings` дважды и не читать один ключ повторно внутри `SettingsMapper`.

Проверка: тот же atrace — число запросов на подключение падает до десятков; медиана «нажатие → Established» для «Авто»/3000 по 10
повторам до/после (ожидаемо −0,5…1 с на эмуляторе); тесты генератора конфигурации дают идентичный JSON до/после.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
(нет) — поведение `proxy-connection` и `auto-selector` не меняется, только скорость подключения.

## Impact
- **Код:** `bg/proto/CoreConfigs.kt` (провайдер профилей, `buildMain`), `database/SettingsMapper.kt`, при п.2 —
  `database/preference/SettingsStore.kt`.
- **Upstream:** файлы вне списков точек правки — патч в upstream (проблема общая) или новая точка правки.
- **Ядро throne, сборка:** не затрагиваются.
- **Риски:** снимок настроек, не учитывающий записи самой сборки, даст неверные порты — обязательна проверка «JSON до/после»;
  пакет из 300 профилей в памяти — так и сейчас (`rows` кэширует всё на генерацию).
- **Проверка исправления:** замер выше; JVM-тесты генератора (сравнение конфигураций) на выборке профилей и настроек.
- **Внешние источники:** лимит переменных SQLite (`SQLITE_MAX_VARIABLE_NUMBER`, 999 до SQLite 3.32): https://www.sqlite.org/limits.html
