# Proposal

> **Категория:** надёжность · **Приоритет:** средний ·
> **Проверка:** воспроизведено на эмуляторе ·
> **Код:** Crown · **Объём:** S

## Why

### Находка

Диалог согласия на ссылку из другого приложения (simple-ui «Подтверждение ссылки из другого приложения») показывает
только имя из ссылки и хост подписки. Хост вычисляется самодельным разбором строки:

```kotlin
// app/src/simple/java/io/nekohasekai/sagernet/simple/add/AddViewModel.kt:341-342
private fun hostOf(url: String): String =
    url.substringAfter("://").substringBefore('/').substringBefore('?').substringAfterLast('@').substringBefore(':')
```

Разбор не останавливается на `#` и `\`. Загрузка подписки идёт через OkHttp (`HttpUrl`), который заканчивает authority на
первом из `/ \ ? #`. Отсюда расхождение:

| URL в ссылке | `hostOf` — показывается пользователю | хост загрузки (OkHttp) |
|---|---|---|
| `https://evil.example#@provider.example/sub` | `provider.example` | `evil.example` |
| `https://evil.example\@provider.example/sub` | `provider.example` | `evil.example` |

Обе формы проходят `ImportText.isHttpUrl` (`simple/add/ImportText.kt:69-78`): там хост обрезается по `#`, но проверяется
лишь то, что он не пуст. В диалог они попадают через `crown://addsub?url=<закодированный URL>&name=…` и через
`clash://install-config?url=…`. Имя группы (`name=`) показывается крупно над текстом (`simple/add/AddScreens.kt:409-418`) и
тоже задаётся ссылкой. Тот же `hostOf` подписывает предложение из буфера обмена (`AddViewModel.kt:207`).

Classic в своём диалоге (`ui/SubscribeFlows.kt`, `addSubscription`) показывает ссылку целиком, там подделать адрес труднее.

### Влияние

Страница или приложение показывает «подписка с сайта provider.example» (настоящий провайдер пользователя), а подписка
скачивается с чужого сервера. Последствия те же, что у тихого импорта: чужие серверы попадают в пул селектора «Авто» и могут
стать текущим сервером, на первой подписке включаются «готовые настройки», HWID уходит на чужой сервер подписки. Защита,
которую обещает требование спецификации («Добавить подписку?» с адресом сайта подписки), обходится одной строкой ссылки.

### Как воспроизвести

Проверено на эмуляторе Android 17 (API 37), debug-сборка коммита `0a0fcc4e`; «чужой» сервер — локальный HTTP-сервер на хосте
(`10.0.2.2:21400`).

1. `adb shell am start -a android.intent.action.VIEW -d 'crown://addsub?url=http%3A%2F%2F10.0.2.2%3A21400%23%40provider.example%2Fsub&name=Provider' dev.folki.crown.debug`
2. Диалог: «Add this subscription? · Provider · A link from another app asks to add a subscription from **provider.example**.»
3. Нажать «Add». Журнал локального сервера: `GET host=10.0.2.2:21400 / ua=Crown/Android/2.0.7` — запрос ушёл на хост из
   начала URL, а не на `provider.example`. (Сервер в тесте отдал пустой ответ, поэтому подписка не добавилась; настоящий
   злоумышленник отдаст рабочий список.)
4. JVM-тест: `hostOf(url)` против `url.toHttpUrl().host` на строках таблицы выше.

## What Changes

1. (Рекомендуется.) Брать хост для диалога и для предложения из буфера тем же разборщиком, что и для загрузки:
   `url.toHttpUrlOrNull()?.host`. Если разбор не удался — ссылку не принимать (`ImportText.isHttpUrl` тоже перевести на
   `toHttpUrlOrNull`, чтобы правило было одно).
2. Показывать хост в ASCII (punycode), если в нём не-ASCII символы, — против омоглифов (`provіder.example` с кириллической `і`).
3. Дополнительно отклонять подписочные URL, где после `#` есть `@` или в authority есть `\`: у честной ссылки подписки
   такого не бывает.

Как проверить: JVM-тест на таблицу; сценарий эмулятора показывает в диалоге `10.0.2.2`.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `simple-ui` (`openspec/changes/simple-ui-redesign/specs/simple-ui`): в «Подтверждение ссылки из другого приложения» добавить,
  что показывается хост, с которого будет скачана подписка (тем же разбором, что при загрузке), в ASCII-виде; ссылка, хост
  которой нельзя однозначно определить, не принимается.

## Impact

- **Код:** `simple/add/AddViewModel.kt` (`hostOf`), `simple/add/ImportText.kt` (`isHttpUrl`), тесты
  `app/src/test/...ImportTextTest` (новые случаи).
- **Upstream:** код Crown, точки правки не нужны.
- **Ядро throne, сборка, инструменты:** не затрагиваются.
- **Риски:** `HttpUrl` нормализует хост (нижний регистр, punycode), и в диалоге он может выглядеть иначе, чем в ссылке, —
  это и нужно. Ссылки с `\` или `#@`, если они где-то используются честно, перестанут приниматься.
- **Проверка исправления:** JVM-тесты на `#@`, `\@`, юникодный хост, обычный `https://sub.example.com/abc`; сценарий эмулятора.
- **Внешние источники:** WHATWG URL Standard — `\` как разделитель пути для special-схем, `#` завершает authority:
  https://url.spec.whatwg.org/#authority-state ; OkHttp `HttpUrl`: https://square.github.io/okhttp/5.x/okhttp/okhttp3/-http-url/
