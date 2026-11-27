# Proposal

> **Категория:** надёжность · **Приоритет:** средний · **Проверка:** воспроизведено на эмуляторе · **Код:** Crown ·
> **Объём:** S

## Why

### Находка
Шаг «Доступность серверов» «Починки» тестирует серверы так:

```kotlin
// app/src/simple/java/io/nekohasekai/sagernet/simple/repair/DeviceRepairChecks.kt:437-446
private suspend fun urlTest(ids: List<Long>): Map<Long, Int?>? {
    val idle = withTimeoutOrNull(BUSY_WAIT_MS) { TestSessionClient.state.first { !it.running } } != null
    if (!idle || !TestSessionClient.startProfiles(TestSpec.KIND_URL, ids)) return null   // null -> TESTS_BUSY
    try {
        val finished = TestSessionClient.state.first { !it.running }
        return ids.associateWith { id ->
            val row = finished.rows[id]
            if (row?.ok == true) row.latency else null
```

Ожидание «движок свободен» смотрит только на `TestSessionClient` — сессии, начатые из UI-процесса. Сессии, которые `:bg`
запускает сам через `TestEngine.queueUrlTests` (`bg/test/TestEngine.kt:105-119`), ему не видны: ранжирование «Авто» перед
стартом и при «все серверы не отвечают» (`bg/autoselector/AutoSelectorProfiles.kt:170`), URL-тест после обновления подписки
(`group/SubscriptionQueue.kt:302-307`).

Если такая сессия идёт, `startProfiles` возвращает `true`, а `:bg` отказывает (`TestEngine.kt:33`:
`if (!session.tryLock()) return -1`), и клиент завершает сессию с `TestFailure.BUSY` без единой строки результата
(`ui/test/TestSessionClient.kt:278`). `urlTest` поле `failure` не смотрит и возвращает для каждого сервера `null`, а шаг
делает вывод:

```kotlin
// DeviceRepairChecks.kt:408-409
val (status, findings) = when {
    answering.isEmpty() -> StepStatus.FAIL to setOf(Finding.NO_SERVER_ANSWERS)
```

Предусмотренный пропуск `SkipReason.TESTS_BUSY` (`:385`) срабатывает только для UI-сессий. Тот же ложный «ни один сервер не
отвечает» получается при `TestFailure.UNREACHABLE` и `CORE_DIED` — сбой самого тестирования выдаётся за отказ серверов. Способ
спросить `:bg` о его сессии уже есть, но из UI не используется: `ICoreService.runningTestSession()`
(`bg/CoreService.kt:24`).

### Влияние
«Починку» запускают, когда «не работает», — как раз когда `:bg` с наибольшей вероятностью тестирует сам: «Авто» подключается и
ранжирует большую подписку, пересобирается после «все не отвечают», подписка после обновления проверяет свои серверы.
Пользователь получает «Ни один из N серверов не отвечает» и итог «Все серверы недоступны», хотя серверы работают; проверки через
VPN пропускаются («нет рабочего сервера»), а «Починка» по ложному диагнозу применяет исправление — обновляет все подписки.
Отчёт, отправленный в поддержку, содержит неверный диагноз.

### Как воспроизвести
Эмулятор Android 17, debug-сборка `0a0fcc4e`, простой интерфейс, «Авто», журнал уровня info.
1. Подписки на 504 сервера (2 рабочих локальных + 502, из них 500 на адресах TEST-NET, которые не отвечают), режим «Авто».
2. Нажать «Connect»: `:bg` ранжирует участников (уведомление «Auto: measuring 472 profiles…», тестовая сессия
   «URL test · … 2 OK · N failed» — рабочие серверы найдены).
3. Во время ранжирования открыть «Настройки» → «Something not working?» (Починка запускается).
4. Фактически, через 1 с после начала шага:
   ```
   simple repair: servers -> fail no_server_answers servers 0/504, whitelist 0/0, current answers false
   simple repair: fix subscription_updated: 2 subscription(s)
   simple repair: results … servers fail, dns_vpn skipped(no_working_server), …
   simple repair: finished, verdict cause all_servers_down
   ```
   На экране — «Server availability: None of the 504 servers answers». Параллельная сессия `:bg` в те же секунды показывала
   «2 OK».
   Ожидаемо: «не проверено: идёт другая проверка» (или ожидание фоновой сессии и реальные результаты).
5. Для сравнения: если тест запущен из UI-процесса (например, раздел «Серверы»), «Починка» корректно ждёт до 60 с и пишет
   «не проверено: идёт другой тест» — пропуск работает только для таких сессий.

## What Changes
1. (рекомендуется) В `urlTest` проверять `finished.failure`: `BUSY` → `null` (→ `TESTS_BUSY`, «не проверено: идёт другая
   проверка»); `UNREACHABLE`/`CORE_DIED` → отдельный пропуск «проверка не удалась», а не пустые результаты. Закрывает и сбои
   самого тестирования.
2. Перед стартом спрашивать у `:bg` занятость движка (`ICoreService.runningTestSession()`) и ждать её освобождения тем же
   `BUSY_WAIT_MS`, как сейчас для UI-сессий.
3. По желанию: для фонового ранжирования «Авто» не ждать, а взять результаты, которые оно пишет в БД.
Исправление по найденной причине (обновление подписок) не применять, если шаг не дал достоверного результата.

Проверка: сценарий выше — шаг «Доступность серверов» «пропущен: идёт другая проверка» или после ожидания показывает «2 из 504
отвечают», вердикт не `all_servers_down`, подписки не обновляются.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `connection-repair` (simple-ui-redesign): «Набор и порядок проверок» / «Итоговый вердикт» — сбой или занятость тестового
  движка (в том числе фоновая сессия сервиса) дают «не проверено» с причиной, а не «ни один сервер не отвечает».

## Impact
- **Код:** `simple/repair/DeviceRepairChecks.kt` (`urlTest`, `servers`); для п. 2 — привязка к `ICoreService` из UI-процесса
  (интерфейс уже есть).
- **Upstream:** код Crown; upstream не меняется (`TestSessionClient`, `CoreService` используются как есть).
- **Ядро throne, сборка, инструменты:** не затрагиваются.
- **Риски:** ожидание фоновой сессии делает «Починку» дольше (до `BUSY_WAIT_MS`, 60 с) — поэтому «пропущено с причиной»
  предпочтительнее долгого ожидания. Связанная проблема длительности шага — `bound-repair-server-check-duration`.
- **Проверка исправления:** сценарий выше; JVM-тест `servers()` с `finished.failure = BUSY` → `TESTS_BUSY`, `UNREACHABLE` →
  пропуск с причиной.
