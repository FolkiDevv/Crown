# Proposal

> **Категория:** стабильность · **Приоритет:** низкий · **Проверка:** подтверждено по коду (нет предела); нехватка памяти
> в `:bg` — гипотеза, не измерено · **Код:** upstream · **Объём:** S

## Why

### Находка
**1. `fetchText` читает ответ целиком без предела.**

```kotlin
// app/src/main/java/io/nekohasekai/sagernet/ktx/Nets.kt:90-102
fun fetchText(url: String, ..., viaProxy: Boolean = appRequestsViaProxy(), ...): HttpFetchResult {
    val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
    newHttpClient(viaProxy, allowInsecure, restrictTls13).newCall(request).execute().use { response ->
        if (!response.isSuccessful) error("HTTP ${response.code}")
        return HttpFetchResult(response.body?.string().orEmpty(), response.headers)
    }
}
```

Через `fetchText` идут удалённые маршрутные профили (`group/RemoteRouteUpdater.kt:54-55`; URL вводит пользователь или
он приходит ссылкой), снимок routeprofiles и конфигурация simple (`simple/config/SimpleConfigRepo.kt:58`).
`response.body?.string()` читает тело в память без ограничения. У подписок предел есть (`readCapped`, `Nets.kt:294-303`),
здесь нет. Клиент `newHttpClient` (`Nets.kt:56-88`) к тому же следует перенаправлениям OkHttp по умолчанию, в том числе
с https на http; у подписок такой переход запрещён (`Nets.kt:216-219`). Маршрутные профили и `config.json` весят
единицы килобайт, поэтому предел в несколько мегабайт ничего законного не отсекает.

**2. Предел тела подписки (64 МиБ) не согласован с памятью разбора.** `SubscriptionFetch.MAX_BYTES = 64L * 1024 * 1024`
(`group/SubscriptionFetch.kt:18`). Тело копится в `Buffer` и затем копируется в массив (`readCapped`), потом превращается
в строку (`SubscriptionFetch.kt:86`), а разбор base64-подписки делает ещё несколько копий (обрезка пробелов, массив
байтов, буфер декодирования и его копия, итоговая строка с кириллицей в UTF-16). По коду пик на разборе — порядка
4–5 размеров ответа одновременно, то есть до ~300 МБ Java-кучи процесса `:bg` при ответе на 64 МиБ. Число оценено по
коду и не измерено. `largeHeap="true"` (`AndroidManifest.xml:68`) даёт предел, зависящий от устройства.

Сам поток загрузки нехватку памяти переживёт: `SubscriptionQueue.drain` ловит любые `Throwable`
(`SubscriptionQueue.kt:346`), воркер WorkManager — тоже. Но при почти исчерпанной куче `OutOfMemoryError` может
получить любой другой поток `:bg` — сервис, binder, обратные вызовы, — а там перехвата нет. `:bg` — это процесс VPN.

### Влияние
Редкий сценарий: нужен сломанный или враждебный источник — удалённый маршрут с неверным URL на большой файл, сервер,
отдающий бесконечное тело, панель, которая вместо списка серверов отдаёт огромный журнал. Последствие — возможное
падение процесса `:bg` и обрыв VPN; при включённом автообновлении маршрутов — повторно при каждом запуске работы.
Реальные подписки весят единицы мегабайт.

### Как воспроизвести
Шаги выведены из кода, не прогонялись.
1. На хосте поднять HTTP-сервер, отдающий бесконечное тело без `Content-Length` (например, поток из `yes`), и пробросить
   порт: `adb reverse tcp:8099 tcp:8099`.
2. Классический интерфейс → Маршруты → добавить удалённый профиль `http://127.0.0.1:8099/big` → «Обновить»; для `:bg`
   включить автообновление маршрутов и выполнить `adb shell cmd jobscheduler run -f dev.folki.crown.debug <id>`.
3. Наблюдать `adb shell dumpsys meminfo dev.folki.crown.debug:bg` и logcat: рост кучи до предела и
   `java.lang.OutOfMemoryError`; при включённом VPN — разрыв ли соединения.
4. Для подписки: сервер отдаёт base64 от 40–60 МБ ссылок с кириллическими именами; при подключённом VPN обновить
   подписку и снять пик Java-кучи `:bg` тем же `dumpsys meminfo`.

## What Changes
- Рекомендуется: в `fetchText` читать тело тем же `readCapped` с пределом (например, 8 МиБ) и понятной ошибкой «ответ
  слишком большой»; запретить переход с https на http (`followSslRedirects(false)` или та же политика, что в `httpGet`).
- Для подписок: после замера (шаг 4) выбрать предел, при котором пик разбора с запасом помещается в кучу (например,
  16–32 МиБ вместо 64), и сократить копии при разборе: обрезка пробелов по индексам, декодирование base64 сразу в строку
  без промежуточной копии массива, освобождение исходного тела до разбора, если оно больше не нужно.
- Проверка: шаги 2–3 дают ошибку «ответ слишком большой» без роста кучи; пик Java-кучи в шаге 4 — не больше ~2 размеров
  ответа.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `routing-rules`: требование «Routing profiles» (удалённые профили) дополняется — загрузка удалённого профиля
  ограничена по размеру и не понижает https до http.
- `profile-management`: требование «Subscription update» — предел размера ответа подписки сообщается понятной ошибкой
  и не превышает объёма, который приложение может разобрать.

## Impact
- **Код:** `ktx/Nets.kt` (`fetchText`, `newHttpClient`); `group/SubscriptionFetch.kt` (предел), разбор в
  `outbound/import/ProfileImport.kt` и `Base64Strict.kt` (копии).
- **Upstream:** `Nets.kt` — существующая точка идентичности форка (User-Agent), новое назначение — описание расширить;
  `SubscriptionFetch.kt` — существующая точка 9, но только для заголовков лимита устройств: предел — новое назначение.
  Разбор (`ProfileImport.kt`) — вне списков: новая точка правки или патч в upstream (у upstream тот же предел 64 МиБ,
  он перенесён из desktop).
- **Ядро throne, сборка, инструменты:** не затрагиваются.
- **Риски:** слишком низкий предел отвергнет законный большой Clash-конфиг с правилами на мегабайты; предел выбирать
  по замеру. Ошибка должна объяснять причину, чтобы пользователь не искал её в сети.
- **Проверка исправления:** шаги 1–4; юнит-тест `fetchText` с MockWebServer на тело больше предела и на перенаправление
  с https на http.
- **Внешние источники:**
  - OkHttp 5.x, `ResponseBody.string()` (читает всё тело в память): https://square.github.io/okhttp/5.x/okhttp/okhttp3/-response-body/string.html
  - OkHttp 5.x, `OkHttpClient.Builder.followSslRedirects`: https://square.github.io/okhttp/5.x/okhttp/okhttp3/-ok-http-client/-builder/follow-ssl-redirects.html
  - Android, обзор управления памятью (лимит кучи, `largeHeap`): https://developer.android.com/topic/performance/memory-overview
