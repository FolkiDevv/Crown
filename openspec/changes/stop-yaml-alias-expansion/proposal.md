# Proposal

> **Категория:** стабильность, скорость · **Приоритет:** высокий ·
> **Проверка:** воспроизведено на эмуляторе ·
> **Код:** upstream · **Объём:** S

## Why

### Находка

Любой импортируемый текст, где есть подстрока `proxies:`, разбирается как Clash YAML
(`app/src/main/java/io/nekohasekai/sagernet/outbound/import/ProfileImport.kt:449-452`). Документ с якорем (`&name`)
потоковое чтение не берёт (`ClashYaml.kt:212`, `throw Unsupported()`), и он собирается целиком через `Composer` snakeyaml с
`LoaderOptions()` по умолчанию (`ClashYaml.kt:65-70`).

Snakeyaml сам от экспоненты защищён: число алиасов на коллекции ограничено (`maxAliasesForCollections`, 50 по умолчанию), а
`SafeConstructor` для узла по алиасу отдаёт уже построенный объект, поэтому значение `proxies` — это граф с общими
поддеревьями. Раскрывает его код приложения: рекурсия `convert` копирует каждое общее поддерево заново и не помнит уже
виденные объекты.

```kotlin
// app/src/main/java/io/nekohasekai/sagernet/outbound/import/ClashYaml.kt:89-97
private fun toEntry(value: Any?): JsonObject = (convert(value) as? JsonObject) ?: JsonObject()

private fun convert(value: Any?): Any = when (value) {
    null -> JsonNull
    is Map<*, *> -> JsonObject().also { obj -> for ((k, v) in value) obj[k.toString()] = convert(v) }
    is List<*> -> JsonArray().also { arr -> for (v in value) arr.add(convert(v)) }
    ...
```

Документ из 18 строк (≈430 байт): базовый список из 10 чисел и 16 уровней «список из трёх алиасов предыдущего уровня» —
всего 48 алиасов, в пределах лимита snakeyaml. `convert` пытается построить 3^16 ≈ 43 млн `JsonArray` и ≈430 млн
значений. `toEntry` приводит результат к `JsonObject` уже после полного `convert` (`ClashYaml.kt:89`), так что раскрытие
идёт, даже если запись не словарь.

Где это исполняется:
- **процесс `:bg`, обновление подписки** (`group/SubscriptionRefresh.kt` → `ProfileImport.parse`). `SubscriptionQueue.drain`
  ловит `Throwable` (`group/SubscriptionQueue.kt:344-349`), поэтому сам OOM процесс не роняет, но до него процесс VPN
  десятки секунд занимает всю кучу (`android:largeHeap="true"`, `AndroidManifest.xml:68`) и крутит GC, блокирующий
  выделения памяти во всех потоках ART;
- **процесс UI**: автоматическая проверка буфера обмена, вставка, файл, share (`simple/add/AddViewModel.kt:192-202` ловит
  только `Exception`) — падение с экраном отчёта.

### Влияние

- Сервер подписки — недоверенный источник: сломанная или скомпрометированная панель, подставной ответ, MITM на
  `http://`-подписке. Ответ меньше килобайта заставляет `:bg` при каждом обновлении (ручном и автоматическом) около 40 с
  держать ~750 МБ RSS и упираться в предел кучи 576 МБ (на эмуляторе). На телефонах с меньшим `dalvik.vm.heapsize` OOM
  наступает раньше, но процесс с сотнями мегабайт в фоне — первый кандидат для low memory killer, а его смерть обрывает VPN.
- В проверке на эмуляторе туннель не прервался: задержка запроса через VPN выросла с ~20 мс до 50–250 мс на ~40 с.
  Binder-вызовы к сервису в это время занимали до 2,6 с (`ISagerNetService … took 2611ms`).
- Пользователь получает непонятную ошибку «Subscription task failed: Failed to allocate a 16 byte allocation…» вместо
  сообщения о плохом документе. Спецификация `profile-management` (Subscription update, «Subscription fetch failure»)
  обещает ошибку и неизменные профили — это выполняется, но ценой минуты GC-шторма в процессе VPN.
- Тот же текст в буфере обмена роняет процесс UI при открытии simple UI (OOM в `ClashYaml.convert`), как глубокая
  вложенность в соседнем изменении `catch-deep-nesting-in-import`.

### Как воспроизвести

Проверено на эмуляторе Android 17 (API 37, x86_64), debug-сборка коммита `0a0fcc4e`; `dalvik.vm.heapsize = 576m`.

1. Локальный HTTP-сервер отдаёт документ: `a0: &a0 [0,…,9]`, затем `a1: &a1 [*a0,*a0,*a0]` … до `a16`, затем `proxies: [*a16]`.
2. Подключить VPN (любой рабочий сервер). Открыть `crown://addsub?url=<адрес документа>` и нажать «Добавить».
3. Наблюдать RSS процессов (`ps -A -o PID,RSS,NAME`) и logcat:
   ```
   t=5s  :bg 457 948 KB   t=20s :bg 852 488 KB   t=35s :bg 886 356 KB
   W .crown.debug:bg: WaitForGcToComplete blocked Alloc on Alloc for 3.359s
   W libbinder.Binder: Binder transaction to …ISagerNetService … took 2611ms
   W .crown.debug:bg: Throwing OutOfMemoryError "Failed to allocate a 16 byte allocation … growth limit 603979776 …"
   ```
   Через ~45 с — диалог «The subscription did not load … Subscription task failed: Failed to allocate…». `:bg` жив, VPN
   включён, Java heap после GC возвращается к ~12 МБ.
4. Вариант UI: скопировать тот же текст в буфер (например, кнопкой на веб-странице) и открыть Crown:
   `java.lang.OutOfMemoryError … at …outbound.import.ClashYaml.convert(ClashYaml.kt:94)`, процесс UI перезапущен с отчётом
   о падении.
5. Тест на JVM: `ClashYaml.document(<документ из шага 1>)` должен за миллисекунды бросить понятную ошибку; Clash-подписки с
   обычными якорями (`<<: *base`, общие `ws-opts`) должны импортироваться как раньше.

## What Changes

1. (Рекомендуется.) Ограничить раскрытие в `ClashYaml`: считать значения, которые создаёт `convert`, и при превышении предела
   бросать понятную ошибку («документ раскрывается слишком сильно — алиасы»). Предел стоит считать от размера исходного
   текста (например, не больше 100 значений на каждый символ документа, но не меньше 1–2 млн) — тогда честные большие
   подписки с общими опциями не упрутся в него, а 430-байтовая бомба остановится сразу.
2. Альтернатива — запоминать уже сконвертированные общие объекты (`IdentityHashMap`) и переиспользовать результат. Минус:
   `JsonObject`/`JsonArray` изменяемы, а последующие шаги (`ClashProxy`) могут менять запись, так что для общих узлов всё
   равно нужна копия. Счётчик проще и надёжнее.
3. Дополнительно можно уменьшить `maxAliasesForCollections` для подписок: алиасы в Clash-подписках редки и немногочисленны.
   Это не заменяет п. 1 — даже 18 алиасов дают 3^6 копий.
4. Ошибку показывать как отчёт об импорте («документ подписки не удалось разобрать»), а не как текст OOM.

Как проверить: шаги 1–4 — отказ за миллисекунды, RSS `:bg` не растёт, UI не падает.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `profile-management`: в «Subscription update» уточнить сценарий отказа — документ, который раскрывается в чрезмерный объём
  (алиасы YAML), отклоняется с сообщением об ошибке без заметного роста памяти процесса VPN; профили не меняются.

## Impact

- **Код:** `app/src/main/java/io/nekohasekai/sagernet/outbound/import/ClashYaml.kt` (`convert`, `convertEntries`, `loadWhole`).
- **Upstream:** файл upstream вне `TOUCH_POINTS` — нужна новая точка правки (`ClashYaml.kt`) или патч в upstream.
- **Ядро throne, сборка, инструменты:** не затрагиваются. Обновление snakeyaml проблему само не решает: раскрытие происходит
  в коде приложения.
- **Риски:** слишком низкий предел отвергнет большие честные подписки с общими опциями через `<<: *opts`; предел
  от размера текста этого избегает. Счётчик добавляет одну проверку на каждое значение — незаметно.
- **Проверка исправления:** JVM-тест на бомбу (отказ, время < 100 мс) и на подписку с 3000 прокси и общими якорями
  (результат как до изменения); сценарий эмулятора из шагов 1–4.
- **Внешние источники:** SnakeYAML, `LoaderOptions.setMaxAliasesForCollections` (защита от «billion laughs» на уровне
  графа узлов, CVE-2017-18640): https://nvd.nist.gov/vuln/detail/CVE-2017-18640
