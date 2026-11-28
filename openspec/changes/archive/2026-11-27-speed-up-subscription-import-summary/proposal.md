# Proposal

> **Категория:** скорость · **Приоритет:** высокий · **Проверка:** воспроизведено на эмуляторе · **Код:** Crown · **Объём:** S

## Why

### Находка
После успешной загрузки подписки simple UI строит сводку («Подписка добавлена», с кнопкой «Подключить»), и пользователь ждёт её:

```kotlin
// app/src/simple/java/io/nekohasekai/sagernet/simple/add/AddViewModel.kt:294-295
val summary = withContext(Dispatchers.IO) { SimpleImport.summary(result.gid, enabled, updateHours) }
if (summary != null) show(AddDialog.Summary(summary))
```

`SimpleImport.summary` отфильтровывает управляемые автоселекторы, вызывая проверку на каждый сервер:

```kotlin
// app/src/simple/java/io/nekohasekai/sagernet/simple/add/SimpleImport.kt:425
val members = SagerDatabase.proxyDao.getByGroup(gid).filter { !TrafficModes.isManagedSelector(it.id) }
```

```kotlin
// app/src/simple/java/io/nekohasekai/sagernet/simple/modes/TrafficModes.kt:396-398
fun isManagedSelector(id: Long): Boolean = id > 0L && (id == store.getLong(KEY_MANAGED_AUTO) ||
    id == store.getLong(KEY_MANAGED_AUTO_WHITELIST) || id == store.getLong(KEY_MANAGED_COUNTRY))
```

`store` — `DataStore.configurationStore` без кэша (`database/preference/SettingsStore.kt`): каждый `getLong` — отдельный
`SELECT value FROM settings WHERE key = ?`. Для обычного сервера совпадения нет, поэтому выполняются все три запроса — **3 × N
запросов**, хотя нужны три числа. Во время импорта база занята и другими: UI 30–35 раз перечитывает список группы, а `:bg`
тестирует новые серверы, поэтому каждый мелкий запрос стоит 5–10 мс.

Сам фильтр для подписки почти всегда пуст: управляемые селекторы создаются в группе, которая не является подпиской
(`TrafficModes.selectorGroup`, `TrafficModes.kt:288-301`). Та же функция в цикле вызывается в `HomeViewModel.firstProfile`
(`simple/home/HomeViewModel.kt:689`).

### Влияние
Добавление подписки — первый шаг нового пользователя (`simple-ui-redesign/specs/simple-ui`, сценарий «Сводка импорта»). У
провайдера с сотнями серверов оно занимает десятки секунд и выглядит как зависание.

Замеры (эмулятор Android 17, release-сборка, локальный макет подписки, сеть ≈0 мс; от нажатия «Добавить» до появления листа
«Subscription added», погрешность ≈1 с):

| Подписка | Без трассировки | Под atrace |
|---|---|---|
| 500 серверов | 23,6 с; 22,8 с | — |
| 3000 серверов | 41,6 с (первое добавление на чистой БД ≈30 с) | 59 с |

atrace (`database`), UI-процесс, потоки `DefaultDispatcher`: для 500 серверов — 1640 запросов `SELECT value FROM settings WHERE key
= ?` подряд с 9-й по 25-ю секунду импорта (≈16 с из ≈22); для 3000 — ≈9100 запросов (15 с чистого времени SQL) с 9-й по 53-ю
секунду, лист появился на ≈55-й. Основное время ожидания — не сеть и не разбор подписки, а этот фильтр.

### Как воспроизвести
1. Release-сборка; макет подписки, отдающий N строк `ss://…#XX Server i` (N = 500 и 3000).
2. `adb shell "atrace --async_start -b 64000 -c am database"`.
3. Simple UI: «Серверы» → «+» → «Ввести ссылку» → адрес макета → «Добавить»; засечь время до листа «Подписка добавлена».
4. `adb shell "atrace --async_stop -b 64000 -c am database" > t.txt`.
5. Посчитать у pid UI-процесса строки `executeForCursorWindow SELECT \`value\` FROM \`settings\` WHERE \`key\` = ?` — ≈3 × N плотной
   полосой прямо перед появлением листа.

## What Changes
1. **(Рекомендуется)** В `TrafficModes` — функция, возвращающая множество id управляемых селекторов (три чтения один раз); в
   `SimpleImport.summary` и `HomeViewModel.firstProfile` фильтровать по этому множеству (`id !in managed`) вместо вызова
   `isManagedSelector` на каждый сервер. Поиск других вызовов в циклах — по `isManagedSelector`.
2. **Дешевле по памяти (дополнительно):** считать сводку без загрузки полных `ProxyEntity` (сейчас ради имени и страны грузятся все
   строки с `outbound_json`): число серверов — `COUNT(*)` по группе без типа автоселектора, страны — по выборке имён и
   `test_country`.
3. **Показывать лист сразу** после `SubscribeResult.Done` с уже известным (имя, число серверов из результата импорта), а число стран
   дописывать, когда посчитается.

Проверка: тот же сценарий — запросов к `settings` на импорт ≤ 10 вместо 3 × N; время до листа для 500/3000 серверов — единицы
секунд (ожидаемо в 3–5 раз меньше, остаток — загрузка и запись подписки).

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
(нет) — сценарий «Сводка импорта» в `simple-ui-redesign/specs/simple-ui` не меняется, меняется только скорость.

## Impact
- **Код:** `simple/modes/TrafficModes.kt`, `simple/add/SimpleImport.kt`, `simple/home/HomeViewModel.kt`, при п.3 —
  `simple/add/AddViewModel.kt`.
- **Upstream:** только код Crown.
- **Ядро throne, сборка:** не затрагиваются.
- **Риски:** id управляемых селекторов может измениться во время построения сводки (пересборка «Авто») — для сводки достаточно
  снимка, ошибка не больше одного сервера в счётчике. Кэш в `SettingsStore` как альтернатива шире и задевает межпроцессную
  согласованность (`settings-storage`, «Typed settings access») — не рекомендуется.
- **Проверка исправления:** замер выше; JVM-тест `summary` на группе с управляемым селектором и без.
- **Внешние источники:** не требуются.
