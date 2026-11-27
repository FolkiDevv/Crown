# Proposal

> **Категория:** надёжность · **Приоритет:** средний · **Проверка:** подтверждено по коду · **Код:** Crown (вызов из
> существующей точки правки) · **Объём:** S–M

## Why

### Находка
Единственный механизм учёта сбоев — Java-обработчик `CrashHandler`, и он сам отмечает, что нативные сбои ему не видны:
```kotlin
// app/src/main/java/io/nekohasekai/sagernet/utils/CrashHandler.kt:20
// note: libc / go panic is in android log
```
Ядро (Go, `libthrone.so`) загружается в процесс `:bg` (`bg/CoreRuntime.kt:53-66`). Необработанный panic в горутине, SIGSEGV
в нативном коде, ANR сервиса, убийство при нехватке памяти — всё это завершает `:bg` без вызова Java-обработчика. Следы
остаются только в logcat: экспорт журнала берёт последние строки logcat приложения, но кольцевой буфер вытесняется за
минуты или часы, а экспортировать журнал пользователь должен догадаться сам. Журнал ядра при новом старте `:bg` копируется
в `core.log.prev` с перезаписью (`moe/matsuri/nb4a/utils/CoreLog.kt:147-162`), так что после второго перезапуска след
первого сбоя теряется.

Android 11+ хранит причину завершения каждого процесса приложения: `ActivityManager.getHistoricalProcessExitReasons`
возвращает `ApplicationExitInfo` (`REASON_CRASH_NATIVE`, `REASON_ANR`, `REASON_LOW_MEMORY`, `REASON_SIGNALED`,
`REASON_EXCESSIVE_RESOURCE_USAGE` и др.), а для ANR и нативных сбоев — `getTraceInputStream()` с трассой. В коде этот API не
используется (поиск `getHistoricalProcessExitReasons|ApplicationExitInfo` по `app/src` — 0 совпадений).

### Влияние
- Пользователь видит, что VPN «сам выключился», и не получает ни объяснения, ни предложения отправить журнал.
- В Android 17 появились ограничения памяти на приложение (сначала на Pixel, затем у других производителей): процесс,
  превысивший предел, замедляется и может быть завершён, и отличить это можно только по `ApplicationExitInfo`
  (`REASON_OTHER`, в описании `MemoryLimiter:AnonSwap`). Для `:bg` с кучей Go и пиками памяти при разборе больших подписок это
  новый, иначе невидимый класс отказов.
- Владелец узнаёт о нативных падениях ядра и ANR, только если пользователь сам снимет logcat через adb.
- `diagnostics-logging`, требование «Crash and error reporting path»: неожиданные ошибки сервиса должны попадать в журнал
  со стеком. Для нативных сбоев и убийств системой это не выполняется. Запись причин остаётся на устройстве, поэтому
  запрету на автоматическую отправку решение не противоречит.

На эмуляторе гибель `:bg` при тестах на устойчивость видна только в системном журнале
(`ActivityManager: Process dev.folki.crown.debug:bg (pid …) has died`, `am_proc_died`); в журнале приложения причины нет.

### Как воспроизвести
Шаги выведены из кода; системная часть (смерть `:bg` по `kill`) на эмуляторе наблюдалась, проверка журнала приложения не
прогонялась.
1. Debug-сборка, подключить VPN.
2. Смоделировать нативную смерть: `adb shell run-as dev.folki.crown.debug kill -SEGV <pid :bg>` (или `kill -ABRT`).
3. Открыть приложение, экспортировать журнал: записи о причине завершения `:bg` нет.
4. Убедиться, что система причину знает: `adb shell dumpsys activity exit-info dev.folki.crown.debug` — запись процесса `:bg`
   с причиной `CRASH_NATIVE`/`SIGNALED`.
5. Для ограничителя памяти Android 17: `adb shell am memory-limiter status`, при поддержке — ручной режим с низким пределом
   и загрузка большой подписки.

## What Changes
- Рекомендуется: при старте процесса `:bg` (из кода Crown, например `SimpleHooks.onServiceStarted`, который вызывается из
  `BaseService` независимо от интерфейса) на Android 11+ читать `getHistoricalProcessExitReasons(packageName, 0, N)`, брать
  записи новее сохранённой отметки времени, и для каждой писать в журнал строку «предыдущий процесс X завершён: причина,
  статус, важность, время, описание».
- Для `REASON_ANR` и `REASON_CRASH_NATIVE` — добавлять первые несколько КБ `getTraceInputStream()`, пропуская через то же
  скрытие чувствительных данных, что и остальной журнал при экспорте.
- Если `:bg` завершился аварийно, когда VPN был включён пользователем, — показать в simple ненавязчивое сообщение
  «Подключение прервано из-за сбоя — отправить журнал?» с существующим экспортом журнала.
- Дешёвая частичная мера: не перезаписывать `core.log.prev`, если предыдущая сессия не завершилась штатно (сохранять как
  отдельный файл сбоя).
- Проверка: шаги 2–3 — после изменения журнал содержит причину и трассу.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `diagnostics-logging`: в «Crash and error reporting path» добавляется — причины нештатного завершения процессов
  приложения, о которых сообщает система (нативный сбой, ANR, убийство системой, ограничитель памяти), записываются в журнал
  при следующем запуске.

## Impact
- **Код:** новый модуль в `app/src/simple/...` (чтение `ApplicationExitInfo`, отметка времени в настройках), вызов из
  `SimpleHooks`; сообщение в simple UI; при частичной мере — `CoreLog.kt`.
- **Upstream:** основной объём — код Crown; вызов идёт через существующую точку правки 6 (`BaseService.kt`, SimpleHooks).
  Частичная мера в `CoreLog.kt` требует новой точки правки.
- **Ядро throne, сборка, инструменты:** не затрагиваются.
- **Риски:** трассы ANR и tombstone большие — ограничивать размер; содержат адреса памяти, но не данные подписок; всё равно
  пропускать через скрытие. Записи могут повторяться — нужна отметка последней обработанной.
- **Проверка исправления:** шаги «Как воспроизвести»; юнит-тест на фильтр новых записей по отметке времени.
- **Внешние источники:**
  - `ActivityManager.getHistoricalProcessExitReasons`: https://developer.android.com/reference/android/app/ActivityManager#getHistoricalProcessExitReasons(java.lang.String,%20int,%20int)
  - `ApplicationExitInfo`: https://developer.android.com/reference/android/app/ApplicationExitInfo
  - Android 17, «Behavior changes: all apps» (ограничения памяти): https://developer.android.com/about/versions/17/behavior-changes-all
  - Android Developers Blog, «App broader memory limits» (2026-08): https://android-developers.googleblog.com/2026/08/app-broader-memory-limits.html
