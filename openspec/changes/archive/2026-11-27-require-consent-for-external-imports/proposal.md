# Proposal

> **Категория:** надёжность · **Приоритет:** высокий ·
> **Проверка:** воспроизведено на эмуляторе (extra `importTexts` в simple, SEND-текст в classic); файлы — подтверждено по коду ·
> **Код:** оба · **Объём:** S–M

## Why

### Находка

`MainActivity` экспортирована и принимает все внешние входы (`app/src/main/AndroidManifest.xml:77-201`): MAIN/LAUNCHER,
VIEW для `content`/`file`, SEND с `application/octet-stream`, VIEW ссылок профилей и `crown://`. Подтверждение
спрашивается не на всех путях, по которым содержимое приходит из чужого приложения.

**Simple UI** (по умолчанию). `MainActivity` перенаправляет intent в `SimpleActivity` и копирует все extras
(`app/src/simple/java/io/nekohasekai/sagernet/simple/SimpleUi.kt:151-162`, `source.extras?.let { intent.putExtras(it) }`).
`SimpleActivity.handleImportIntent` спрашивает подтверждение только у VIEW-ссылки и SEND-текста (`external = true`):

```kotlin
// simple/SimpleActivity.kt:446-451, 457-460, 466-469
intent.getStringArrayListExtra(MainActivity.EXTRA_IMPORT_TEXTS)?.let {
    add.importTexts(it)                      // без external → perform() сразу
    return
}
...
if (scheme == "content" || scheme == "file") openFile(uri)   // VIEW файла — без вопроса
...
if (stream != null) openFile(stream)                         // SEND файла — без вопроса
```
- `EXTRA_IMPORT_TEXTS` («importTexts») задуман для собственного QR-сканера, но читается из любого intent.
  `AddViewModel.importTexts` для одного элемента вызывает `importText(payload)` с `external = false`
  (`simple/add/AddViewModel.kt:143-145`), и подписка добавляется сразу.
- Файл из другого приложения: `AddViewModel.openFile` выполняет `perform(plan)` без вопроса (`AddViewModel.kt:154-180`). Файл с
  одной http(s)-строкой — это `Plan.Subscription`.

Тихая подписка в simple влечёт больше, чем новая группа: первая подписка включает «готовые настройки»
(`AddViewModel.kt:277-282`, `TrafficModeApplier.setUpAuto`); управляемый селектор «Авто» охватывает все группы
(`simple/modes/TrafficModes.kt:233-234`, `GroupRepo.ids()`), так что серверы чужой подписки попадают в пул автовыбора;
`SimpleDefaults.apply` включает «Send HWID» до первой загрузки (`AddViewModel.kt:268-269`).

**Classic.** `MainActivity.handleImportIntent` (`ui/MainActivity.kt:248-265`): VIEW ссылки профиля подтверждается
(`importProfile`, «Импортировать профиль?»), подписки и http(s) спрашиваются (`SubscribeFlows.addSubscription`,
`chooseUrlImport`). Но SEND-текст, extra `importTexts` и присланные файлы со ссылками профилей идут в
`SubscribeFlows.importInto` (`ui/SubscribeFlows.kt:56-65, 203-215`), который сразу вызывает
`SubscriptionClient.importText(content, gid)` — все профили попадают в текущую группу, пользователь видит только снэкбар.
Сам Crown уже защищал этот путь от VIEW со многими строками (`simple/add/CrownLinks.kt:36-40`: «a crafted intent with several
would reach the text import, which adds servers without the confirmation a link gets») — SEND и `importTexts` эта защита
не закрывает.

Classic доступен чужому приложению при любом выбранном интерфейсе: extras `io.nekohasekai.sagernet.simple.OPEN_CLASSIC`
и `EXTRA_WIFI_PERMISSION` входят в `CLASSIC_EXTRAS` (`SimpleUi.kt:45-48`) и отключают redirect, после чего
`MainActivity.onCreate` сам обрабатывает intent.

Явный intent `ACTION_MAIN` + `CATEGORY_LAUNCHER` совпадает с фильтром лаунчера, а SEND с `application/octet-stream` — с
фильтром SEND, поэтому ограничение Android 14+ на явные intent'ы, не совпадающие с фильтрами, здесь не помогает.

### Влияние

- Любое приложение на устройстве без разрешений одним intent'ом добавляет в Crown свою подписку (simple) или свои серверы в
  текущую группу (classic). Пользователь видит только сводку «Подписка добавлена» или снэкбар «Импортировано N».
- Серверы злоумышленника попадают в пул селектора «Авто» и могут стать текущим сервером, если отвечают быстрее, — трафик
  пользователя идёт через чужой сервер без его выбора. На первой подписке ещё и включаются «готовые настройки», а HWID
  уходит на чужой сервер подписки.
- Нарушает simple-ui «Подтверждение ссылки из другого приложения» (ссылка из другого приложения добавляет подписку или
  серверы только после подтверждения). Требование освобождает от подтверждения «файл», но имеет в виду файл, выбранный
  самим пользователем через «Открыть файл», а не присланный чужим приложением. `AGENTS.md`: intent'ы и файлы извне —
  недоверенный ввод.

### Как воспроизвести

Проверено на эмуляторе Android 17 (API 37), debug-сборка коммита `0a0fcc4e`; команды выполняются от shell, как их отправило
бы любое приложение.

1. Simple UI (по умолчанию), в приложении одна подписка.
   `adb shell am start -n dev.folki.crown.debug/io.nekohasekai.sagernet.ui.MainActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER --esal importTexts "http://<тестовый сервер>/sub"`
   Фактически: диалога «Добавить подписку?» нет; сервер подписок получил запрос `GET /sub` с User-Agent `Crown/Android/…`;
   на экране сразу «Subscription added · 1 server · Daily subscription update». Для сравнения
   `am start -a android.intent.action.VIEW -d "crown://addsub?url=…"` показывает «Добавить подписку?» с кнопками «Отмена/Добавить».
2. Classic через extra:
   `adb shell am start -n dev.folki.crown.debug/io.nekohasekai.sagernet.ui.MainActivity -a android.intent.action.SEND -t application/octet-stream --es android.intent.extra.TEXT 'ss://<base64>@<адрес>:<порт>#Injected' --ez io.nekohasekai.sagernet.simple.OPEN_CLASSIC true`
   Фактически: открывается классический экран, в группе «Default» появляется профиль «Injected» (Shadowsocks), вопроса нет.
3. Файл (по коду, на эмуляторе не проверялся: нужен `content://` от FileProvider чужого приложения): SEND с `EXTRA_STREAM`
   на текстовый файл с одной строкой `https://…` — в simple подписка добавляется без вопроса (`AddViewModel.openFile` →
   `perform`).

## What Changes

1. (Рекомендуется.) Перестать принимать `EXTRA_IMPORT_TEXTS` из входящего intent'а. Сканер возвращает коды результатом
   (`registerForActivityResult`/`setResult`) тому экрану, который его открыл, — и в simple, и в classic. Проверка
   `getCallingPackage()`/`referrer` хуже: `referrer` подделывается, `callingPackage` есть только у `startActivityForResult`.
2. Всё, что пришло intent'ом извне, считать внешним:
   - simple: SEND/VIEW-файл, который даёт подписку или новые серверы, показывает тот же `LinkConfirm`, что VIEW-ссылка;
     «Открыть файл» внутри приложения остаётся без подтверждения;
   - classic: SEND-текст, `importTexts` и присланные файлы со ссылками профилей показывают то же подтверждение, что VIEW
     ссылки профиля (имя первого профиля и их число); вставка из буфера и «Импорт из файла» в меню остаются без вопроса.
3. `CLASSIC_EXTRAS` не должны выключать redirect у intent'а с внешними данными (есть `data`, `EXTRA_TEXT`, `EXTRA_STREAM`
   или `importTexts`): такие intent'ы всегда идут в выбранный пользователем интерфейс.
4. Частичная мера, если п. 2 откладывается: для подписок без подтверждения не применять `setUpAuto` и не включать HWID.

Как проверить: команды из «Как воспроизвести» показывают подтверждение; собственный QR-сканер, вставка из буфера и
«Открыть файл» работают без него.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `simple-ui` (`openspec/changes/simple-ui-redesign/specs/simple-ui`): в «Подтверждение ссылки из другого приложения» уточнить,
  что «файл» без подтверждения — это файл, выбранный в приложении; файл и тексты, присланные другим приложением, требуют
  подтверждения; добавить сценарий для extra сканера.
- `profile-management`: в «Import and share» — импорт из share/intent другого приложения требует подтверждения пользователя.

## Impact

- **Код:** `simple/SimpleActivity.kt` (`handleImportIntent`), `simple/add/AddViewModel.kt` (`importTexts`, `openFile`),
  `simple/SimpleUi.kt` (`simpleIntent`, `shouldRedirect`); `ui/MainActivity.kt` (`handleImportIntent`, запуск сканера),
  `ui/SubscribeFlows.kt` (`importText`/`importTexts`), `ui/ScannerActivity.kt` (возврат результата).
- **Upstream:** simple — код Crown. `MainActivity.kt` — существующая точка правки 5, `SubscribeFlows.kt` — точка идентичности
  форка (`crown://`), но подтверждение SEND-текста выходит за их описания: описания точек нужно расширить.
  `ScannerActivity.kt` вне списка — новая точка правки, если сканер переводится на результат.
- **Ядро throne, сборка, инструменты:** не затрагиваются.
- **Риски:** «Поделиться» в Crown станет на одно нажатие длиннее (как уже у VIEW). Сканер должен вернуть результат в оба
  интерфейса; если его открывают из виджета или плитки, нужен отдельный путь.
- **Проверка исправления:** инструментальный тест или сценарий эмулятора: явный intent с `importTexts`, SEND-текст с
  `OPEN_CLASSIC`, SEND-файл — каждый показывает подтверждение и после «Отмена» ничего не добавляет и не скачивает.
- **Внешние источники:** Android 14 behavior changes — сопоставление явных intent'ов с фильтрами:
  https://developer.android.com/about/versions/14/behavior-changes-14#safer-intents
