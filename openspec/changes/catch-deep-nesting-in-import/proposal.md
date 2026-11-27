# Proposal

> **Категория:** стабильность · **Приоритет:** высокий ·
> **Проверка:** воспроизведено на эмуляторе ·
> **Код:** оба · **Объём:** S

## Why

### Находка

Разбор импортируемого текста рекурсивен по глубине вложенности и ничем её не ограничивает. А вызывающий код ловит только
`Exception`, тогда как `StackOverflowError` — это `Error`.

1. **JSON.** Текст, который начинается с `{` или `[` и закрывается в последнем символе, отдаётся в org.json:

   ```kotlin
   // app/src/main/java/io/nekohasekai/sagernet/outbound/import/ProfileImport.kt:427-429
   if (text[0] == '{' || text[0] == '[') {
       if (Scan.matchingClose(text, 0) == text.length - 1) {
           val value = JsonInput.parseValue(text)
   // app/src/main/java/io/nekohasekai/sagernet/outbound/json/JsonInput.kt:26-30
   fun parseValue(text: String): Any? = try {
       fromOrgJsonValue(JSONTokener(text).nextValue())
   } catch (e: Exception) { null }
   ```
   `JSONTokener` из платформы рекурсивен (`nextValue → readArray → nextValue`) и предела глубины не имеет. Рекурсивны и
   `fromOrgJson`/`fromOrgJsonValue` (`JsonInput.kt:34-57`). Собственный парсер JSON-редактора предел имеет
   (`ui/json/engine/JsonTree.kt:44,153`, `MAX_PARSE_DEPTH = 96`), но на пути импорта он не используется.
2. **YAML.** Текст, где встречается `proxies:`, уходит в `ClashYaml.document` (`ProfileImport.kt:449-452`). И потоковое
   чтение (`ClashYaml.kt:209-245`, `compose → composeNode → compose`), и `loadWhole` (`ClashYaml.kt:65-66`, `Composer`
   snakeyaml) рекурсивны. В snakeyaml 1.30 (`app/build.gradle.kts:86`) предела вложенности нет: его добавили в 1.31
   (CVE-2022-25857). `ProfileImport.clash` и `guarded` ловят только `Exception` (`ProfileImport.kt:398-403, 708-725`).
3. **Вызывающие в процессе UI тоже ловят только `Exception`.** Самый опасный путь — автоматическая проверка буфера обмена в
   simple UI: `onStart` взводит `clipboardCheck` (`app/src/simple/java/io/nekohasekai/sagernet/simple/SimpleActivity.kt:390`),
   `onWindowFocusChanged` (`:434-438`) вызывает `AddViewModel.checkClipboard`. Тот разбирает текст в `Dispatchers.IO`
   внутри `catch (e: Exception)` (`simple/add/AddViewModel.kt:188-202`), `SimpleImport.plan` — тоже
   (`simple/add/SimpleImport.kt:81-86, 98-103`). Ошибка уходит из корутины `viewModelScope` в обработчик падений, который
   перезапускает процесс с экраном отчёта (`BlankActivity`). Метка «этот клип уже смотрели» (`lastClipStamp`,
   `SimpleActivity.kt:724`) живёт только в памяти, поэтому после перезапуска тот же клип разбирается снова.
   Те же незащищённые пути: вставка, «Ввести ссылку», файл и «Поделиться» в simple (`AddViewModel.kt:122,149,179`);
   в classic — `MainActivity.importProfile` (`ui/MainActivity.kt:310-317`), импорт из файла и буфера
   (`ui/profiles/ProfileImports.kt`), ссылки профилей маршрутизации (`RouteShare.fromShareInput` → `JsonInput.parseValue`).
4. В `:bg` обновление подписки защищено: `SubscriptionQueue.drain` ловит `Throwable`
   (`group/SubscriptionQueue.kt:344-349`). Там глубокий документ даёт только непонятное сообщение «task failed: stack size …».

### Влияние

- Текст с вложенностью от нескольких тысяч уровней в буфере обмена роняет процесс UI при каждом возвращении в приложение.
  Пользователь ничего не нажимает: открыл Crown — экран отчёта о падении. Пока буфер не изменится, приложение «не
  открывается», связать это с буфером пользователь не может, подключиться из UI нельзя.
- Порог очень низкий. Release-сборка: 3000 уровней не падает, 8000 падает — это 16 КБ текста. Debug-сборка падает уже на
  1000 уровнях (2 КБ). Поток `DefaultDispatcher` получает стек 1036 КБ.
- Источник — недоверенный ввод: кнопка «скопировать» на чужой странице, присланный текст, файл, share из другого
  приложения. Спецификация simple-ui («Предложение импорта из буфера обмена») обещает только предложить распознанную
  ссылку; падение на нераспознанном тексте ей противоречит, как и сценарий «Нераспознанное содержимое» требования
  «Универсальное добавление серверов» («показывается понятное сообщение … и ничего не добавляется»).

### Как воспроизвести

Проверено на эмуляторе Android 17 (API 37, x86_64), release- и debug-сборки коммита `0a0fcc4e`.

1. Открыть в Chrome на устройстве страницу с кнопкой, которая копирует в буфер строку из N открывающих и N закрывающих
   квадратных скобок (через `document.execCommand('copy')`).
2. Нажать кнопку, затем открыть Crown (simple UI). Если открыт диалог «Не отключать VPN в фоне», закрыть его: проверка
   буфера идёт, когда окно activity получает фокус.
3. Фактический результат (N = 20 000, release):
   ```
   E Thread[main,5,main]: java.lang.StackOverflowError: stack size 1036KB
       at org.json.JSONTokener.readArray(JSONTokener.java:425)
       at org.json.JSONTokener.nextValue(JSONTokener.java:107)   (×тысячи)
       ...
       at ...outbound.import.ProfileImport$Parser.document(ProfileImport.kt:430)
       at ...simple.add.SimpleImport.plan(SimpleImport.kt:82)
       at ...simple.add.AddViewModel$checkClipboard$1$offer$1.invokeSuspend(AddViewModel.kt:194)
   I Process : Sending signal. PID: … SIG: 9
   ```
   Наверху задачи — `io.nekohasekai.sagernet.ui.BlankActivity` (отчёт о падении). Повторное открытие приложения падает так же.
4. Порог (одна попытка на значение): release — 1000 и 3000 уровней без падения, 8000 и 20 000 — падение; debug — 500 без
   падения, 1000/2000/5000/20 000 — падение.
5. Вариант YAML: `proxies: ` + 20 000 `[` + 20 000 `]` (40 КБ). Падение с `StackOverflowError` в
   `org.yaml.snakeyaml.scanner.ScannerImpl.fetchFlowSequenceStart` / `ParserImpl$ParseFlowSequenceEntry.produce`.
6. Тест на JVM: `ProfileImport.parse("[".repeat(50_000) + "]".repeat(50_000))` и вариант с `proxies:` должны вернуть
   результат с отчётом об ошибке, а не бросить `StackOverflowError`.

## What Changes

1. (Рекомендуется.) Ограничить вложенность до передачи текста в рекурсивные парсеры:
   - JSON: `Scan.matchingClose` (`ProfileImport.kt:1232-1255`) уже проходит текст итеративно и считает `depth`. Добавить
     предел (например, 96, как `MAX_PARSE_DEPTH` редактора) и при превышении сообщать «документ вложен слишком глубоко»,
     не вызывая org.json. Ту же проверку — в `JsonInput.parseValue`/`parseObjectOrNull`, чтобы её получили и другие
     вызывающие (`RouteShare`).
   - YAML: в `ClashYaml.StreamingRead` вести счётчик глубины в `composeNode` и бросать понятную ошибку; для `loadWhole` —
     либо предварительный итеративный проход по событиям парсера со счётчиком глубины, либо обновление snakeyaml до версии,
     где есть `LoaderOptions.setNestingDepthLimit` (1.31+). Счётчик дешевле: обновление меняет и другие умолчания
     (см. «Риски»). Ошибка сканера тоже рекурсивна (стек упал в `ScannerImpl`), поэтому проверять глубину надо до
     snakeyaml: например, итеративно посчитать максимальную вложенность `[`/`{` вне строк в тексте с `proxies:`.
2. Страховка в точках входа UI: `SimpleImport.plan`, `AddViewModel.checkClipboard` и пути вставки/файла/share,
   `MainActivity.importProfile`, `ProfileImports` ловят `StackOverflowError` и показывают «не распознано», как при `Exception`.
3. Для автоматической проверки буфера хранить отпечаток отвергнутого клипа на диске (в настройках simple UI уже есть
   `KEY_DECLINED` для отклонённых предложений), чтобы плохой клип не разбирался после каждого перезапуска.

Как проверить: тест из шага 6; сценарий эмулятора — приложение открывается без падения, предложения импорта нет, при ручной
вставке — сообщение «не похоже на ссылку».

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `profile-management`: в «Import and share» добавить сценарий «текст или файл с чрезмерной вложенностью отклоняется с
  сообщением об ошибке, приложение не падает».
- `simple-ui` (`openspec/changes/simple-ui-redesign/specs/simple-ui`): в «Предложение импорта из буфера обмена» —
  нераспознаваемый или испорченный текст в буфере не мешает открыть приложение.

## Impact

- **Код:** `outbound/import/ProfileImport.kt` (`Scan.matchingClose`, `document`), `outbound/json/JsonInput.kt`,
  `outbound/import/ClashYaml.kt`; `simple/add/AddViewModel.kt`, `simple/add/SimpleImport.kt`; `ui/MainActivity.kt`,
  `ui/profiles/ProfileImports.kt`.
- **Upstream:** пункт 1 — файлы upstream вне `TOUCH_POINTS`/`FORK_IDENTITY_POINTS`: нужна новая точка правки
  (`ProfileImport.kt`, `JsonInput.kt`, `ClashYaml.kt`) или патч в upstream. Пункты 2–3 для simple — код Crown;
  `MainActivity.kt` — существующая точка правки 5, но правка `importProfile` выходит за её описание (redirect и
  `crown://`), описание точки придётся расширить.
- **Ядро throne, сборка, инструменты:** ядро не затрагивается. Сборка — только при варианте с обновлением snakeyaml.
- **Риски:** слишком низкий предел отвергнет законные конфигурации; в реальных sing-box/Xray/Clash документах вложенность
  меньше 20, предел 64–128 безопасен. Обновление snakeyaml до 1.32+ вводит предел размера документа (`codePointLimit`,
  3 МБ по умолчанию) и может отвергнуть большие Clash-подписки — его пришлось бы поднять явно.
- **Проверка исправления:** JVM-тесты на глубокий JSON и YAML (ожидается отчёт об ошибке за миллисекунды) и на обычные
  документы (результат не меняется); сценарий эмулятора из «Как воспроизвести».
- **Внешние источники:** snakeyaml CVE-2022-25857 (предел вложенности добавлен в 1.31):
  https://nvd.nist.gov/vuln/detail/CVE-2022-25857
