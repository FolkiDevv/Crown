# Proposal

> **Категория:** надёжность · **Приоритет:** средний · **Проверка:** воспроизведено на эмуляторе ·
> **Код:** Crown · **Объём:** S–M

## Why

### Находка
Любая ошибка старта сервиса доходит до интерфейса одинаково. `BaseService` ловит исключение генератора
конфигурации или ядра и вызывает `failRunner(текст, dnsSettings=…, geoSettings=…, ruleSets=…)`
(`bg/BaseService.kt:688-722`). Тот сохраняет `DataStore.serviceError` и три флага вида ошибки: DNS, geo-файлы
Xray, наборы правил (rule-set).

**Classic** использует флаги и предлагает действие (`ui/MainActivity.kt:462-502`):

```kotlin
if (DataStore.serviceErrorDns) { bar.setAction(R.string.settings_dns) { … } }
else if (DataStore.serviceErrorGeo) { bar.setAction(R.string.xray_geo_assets_action) { … } }
else if (DataStore.serviceErrorRuleSets) { bar.setAction(R.string.rule_set_deferred_action) { confirmStartWithoutRuleSets() } }
```

`confirmStartWithoutRuleSets` ставит `DataStore.startWithoutRuleSets`, и следующий старт идёт без наборов, которые
не удалось скачать: они догружаются после подключения.

**Simple** берёт только текст и считает любую ошибку «потерей соединения»:

```kotlin
// app/src/simple/java/io/nekohasekai/sagernet/simple/home/HomeViewModel.kt:458-460
BaseService.State.Stopped -> {
    if (HomeStatus.isLoss(msg, userStopping)) lost = HomeProblem.ConnectionLost(msg)
}
// app/src/simple/java/io/nekohasekai/sagernet/simple/home/HomeStatus.kt:130
fun isLoss(msg: String?, userStopping: Boolean): Boolean = !userStopping && !msg.isNullOrBlank()
```

Для любого `ConnectionLost` баннер один (`home/HomeScreen.kt:601`, `strings_simple.xml:49-50`): «Соединение
потеряно» / «VPN неожиданно отключился. „Починка“ поможет найти причину». Флаги вида ошибки simple только
сбрасывает (`HomeViewModel.kt:753-755`), `startWithoutRuleSets` в `app/src/simple` не встречается. Сам текст ошибки
виден только в «Починке».

### Влияние
- VPN ни разу не поднялся, а пользователь читает «неожиданно отключился». Ошибку настройки (неизвестный набор
  правил, удалённый профиль в правиле маршрута, неверный DNS, занятый порт) не отличить от обрыва связи.
- Наборы правил без кэша скачиваются во время старта ядра. На свежей установке в режиме «Авто/Умный» старт падает,
  если сеть или зеркало в этот момент недоступны. Повторное «Подключить» падает так же. В classic то же состояние
  выводится одной кнопкой «Всё равно подключиться», в simple выхода нет — только сменить режим или уйти в classic.
  Это расходится с правилом `AGENTS.md`: когда simple добавляет путь, который есть в classic, нужно сравнить, что
  classic проверяет или спрашивает.
- Спецификация `simple-ui-redesign` «Баннер проблемы на главной» требует «краткое объяснение на понятном языке»;
  сейчас объяснение неверное.

### Как воспроизвести
Debug-сборка `dev.folki.crown.debug`, эмулятор Android 17 (API 37).

1. `pm clear`, первый запуск, добавить сервер, оставить режим «Auto · smart». Наборы правил ещё не скачаны.
2. Сделать сеть недоступной (режим полёта) и нажать «Подключить». В `files/core.log`:
   ```
   [BoxInstance] box start failed for profile 1: start service: start router: (initialize rule-set[3]:
     initial rule-set: geosite-ru-available-only-inside: Get "…": dial tcp …: connect: network is unreachable | …
   ```
   На главной: «Connection lost» / «The VPN turned off unexpectedly. Repair can help find out why.» / «Off».
3. Нажать «Подключить» ещё раз — та же ошибка через ~1 с, тот же баннер, другого действия нет.
4. Тот же баннер при другой причине: занять порт 2080 (`adb shell "nohup toybox nc -L -s 127.0.0.1 -p 2080 cat &"`),
   нажать «Подключить» — в журнале `listen tcp 127.0.0.1:2080: bind: address already in use`, на экране
   «Connection lost».
5. Для сравнения — classic (по коду, `MainActivity.kt:474-475`): при ошибке наборов правил снекбар с действием
   «Всё равно подключиться».

## What Changes
1. **(Рекомендуется.)** Различать в simple «не удалось подключиться» и «соединение потеряно». Если `Stopped` с
   сообщением пришёл сразу после `Connecting` и VPN в этой попытке не был `Connected`, показывать баннер «Не удалось
   подключиться» с кратким текстом причины (через существующий `ServiceMessages.localize`).
2. Читать флаги вида ошибки и давать действие в баннере, как в classic:
   - наборы правил — «Подключиться без наборов правил» с тем же подтверждением (`rule_set_deferred_title/message`),
     которое ставит `DataStore.startWithoutRuleSets` и запускает подключение;
   - DNS и geo-файлы — переход в «Починку» с этим шагом или в соответствующий раздел расширенного режима.
3. «Соединение потеряно» оставить для настоящего обрыва (сервис был `Connected`).
4. Проверка «до/после»: шаги 2–3 показывают «Не удалось подключиться: не скачались наборы правил» и кнопку; после
   подтверждения VPN поднимается без сети до сервера наборов и догружает их позже. Шаг 4 — понятный текст про
   занятый порт.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `simple-ui` (в `openspec/changes/simple-ui-redesign/specs/simple-ui`): в «Баннер проблемы на главной» добавить
  сценарии «Подключение не удалось» (отдельно от потери соединения) и «Не скачались наборы правил» с действием
  «Подключиться без наборов правил».
- `routing-rules`: в «Rule set references» — ошибка загрузки набора в simple показывается как ошибка набора правил,
  с тем же выходом, что в classic.

## Impact
- **Код:** `app/src/simple/java/io/nekohasekai/sagernet/simple/home/HomeViewModel.kt`, `HomeStatus.kt`,
  `HomeScreen.kt`, `HomeProblem`, `strings_simple.xml` (все переводы).
- **Upstream:** только код Crown. `BaseService.failRunner` и флаги уже есть; правка upstream не нужна.
- **Ядро throne, сборка, инструменты:** не затрагиваются.
- **Риски:** старт без наборов правил первые секунды не маршрутизирует по ним (правила ничего не совпадают, пока
  наборы не скачаются), поэтому нужно явное согласие, как в classic. Признак «VPN в этой попытке был подключён» должен
  переживать пересоздание activity и смерть процесса UI (хранить рядом с `serviceError`, а не только в памяти
  ViewModel).
- **Проверка исправления:** unit-тесты `HomeStatus` на переходы `Connecting → Stopped(msg)` и
  `Connected → Stopped(msg)`; ручной сценарий выше.
