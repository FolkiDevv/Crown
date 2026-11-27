# Proposal

> **Категория:** надёжность · **Приоритет:** низкий · **Проверка:** подтверждено по коду (нет повтора); частота отказов
> GitHub API на общем IP — гипотеза · **Код:** upstream · **Объём:** S (повтор), M (запасной источник)

## Why

### Находка
Ежедневная проверка обновлений — периодическая работа WorkManager раз в сутки с единственным ограничением «есть сеть»
(`app/src/main/java/io/nekohasekai/sagernet/update/UpdateScheduler.kt:44-45`). Воркер смотрит только на успешный исход:

```kotlin
// UpdateScheduler.kt:72-83 (CheckTask)
override suspend fun doRemoteWork(): Result {
    if (!BuildConfig.IN_APP_UPDATER || !DataStore.updateCheckAuto) return Result.success()
    val result = withContext(Dispatchers.IO) { UpdateChecker.check(applicationContext) }
    if (result is UpdateChecker.Result.Available) { ... }
    return Result.success()
}
```

Любая ошибка — обрыв сети, DNS, таймаут, ответ 403/429/5xx — означает, что следующая попытка будет через сутки.
`Result.retry()` и backoff не используются, заголовки `Retry-After` и `X-RateLimit-Reset` не читаются.

Самая вероятная ошибка — лимит GitHub API. Запрос выпусков идёт без авторизации (`update/UpdateChecker.kt:187-191`), а для
таких запросов GitHub даёт 60 запросов в час на IP (проверено: ответ API без токена несёт `X-RateLimit-Limit: 60`). Кэш
по ETag (`If-None-Match`, `:190`) лимит не экономит: по документации GitHub ответ 304 не расходует лимит только у запроса
с заголовком `Authorization`. Клиент берётся через `newHttpClient(viaProxy = appRequestsViaProxy())` (`:76-77`); при
включённом VPN собственный трафик приложения идёт в туннель, и запрос выходит с IP сервера провайдера, который делят
все его клиенты и все их программы, проверяющие GitHub без токена. Ответ 403 превращается в
`Result.Failed("GitHub: HTTP 403 API rate limit exceeded for <IP>…")` (`:198-202`).

### Влияние
Пользователь узнаёт о новой версии на сутки позже после каждой неудачной проверки, а если его узел выхода постоянно
упирается в лимит — не узнаёт совсем. Встроенное обновление — основной канал доставки исправлений Crown. При ручной
проверке окно обновления показывает сырой текст GitHub с IP выхода, который пользователю ничего не говорит. Насколько
часто IP выхода реальных провайдеров исчерпывает лимит, не измерено.

### Как воспроизвести
Шаги выведены из кода, не прогонялись.
1. При включённом VPN выполнить запрос к `https://api.github.com/rate_limit` через тот же узел выхода и посмотреть
   `x-ratelimit-remaining`; повторить в разное время суток.
2. Модель отказа: на хосте прокси (например, mitmproxy), который на `api.github.com/repos/*/releases` отвечает `403` с
   `{"message":"API rate limit exceeded"}`. Направить через него трафик эмулятора и выполнить
   `adb shell cmd jobscheduler run -f dev.folki.crown.debug <id задания UpdateCheck>`.
3. Фактический результат: в logcat `Update check failed … HTTP 403`, следующая попытка — через сутки
   (`adb shell dumpsys jobscheduler`).

## What Changes
- Рекомендуется: в `CheckTask` при временной ошибке (сеть, таймаут, 403/429 с признаком лимита, 5xx) возвращать
  `Result.retry()`, задав запросу `setBackoffCriteria(EXPONENTIAL, …)` с началом не меньше 30–60 минут; при 403/429
  учитывать `Retry-After` и `X-RateLimit-Reset`. Потолок — 3–4 повтора в сутки.
- Запасной источник без API-лимита: при 403/429 читать Atom-ленту выпусков `https://github.com/<repo>/releases.atom`
  (теги, признак pre-release) и затем манифест `throne-update.json` по прямой ссылке
  `…/releases/download/<tag>/throne-update.json` — загрузки ассетов в лимит API не входят.
- В окне обновления вместо сырого текста показывать понятное «GitHub временно ограничил запросы, попробуйте позже»
  (без IP).
- Проверка: сценарий шага 2 — после 403 работа повторяется через десятки минут или часы, а не через сутки; с запасным
  источником предложение обновления появляется и при 403 от API.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
(нет) — отдельной спецификации встроенного обновления нет.

## Impact
- **Код:** `update/UpdateScheduler.kt`, `update/UpdateChecker.kt` (тип ошибки, запасной источник), строки окна обновления.
- **Upstream:** `UpdateScheduler.kt` вне списков — нужна новая точка правки или патч в upstream. `UpdateChecker.kt` —
  точка идентичности форка, но только ради имени репозитория; запасной источник — новое назначение, описание расширить.
- **Ядро throne, сборка, инструменты:** не затрагиваются.
- **Риски:** слишком частые повторы сами тратят общий лимит — нужен backoff и потолок. Atom-лента — HTML-домен
  `github.com`, её формат не является API с гарантиями; использовать только как запасной путь.
- **Проверка исправления:** шаги 2–3 до и после; юнит-тест классификации ответа (403 с лимитом, 429, 5xx, обрыв).
- **Внешние источники:**
  - GitHub Docs, Best practices for using the REST API (условные запросы): https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api
  - GitHub Docs, Rate limits for the REST API: https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api
  - WorkManager, повтор и backoff: https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work#retries_backoff
