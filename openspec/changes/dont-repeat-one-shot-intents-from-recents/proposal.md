# Proposal

> **Категория:** надёжность · **Приоритет:** средний ·
> **Проверка:** воспроизведено на эмуляторе (ссылка `crown://addsub`); страница провайдера и объявление — подтверждено по коду ·
> **Код:** оба · **Объём:** S

## Why

### Находка

`SimpleActivity.onCreate` выполняет одноразовые действия из intent, если `savedInstanceState == null`, и больше ничего не
проверяет:

```kotlin
// app/src/simple/java/io/nekohasekai/sagernet/simple/SimpleActivity.kt:224-228, 298-301
if (savedInstanceState == null) requestedSection.value = sectionOf(intent)
if (savedInstanceState == null) {
    openAnnouncement(intent)      // лист объявления провайдера
    openProvider(intent)          // страница провайдера во внешнем браузере
}
...
if (savedInstanceState == null) {
    if (!askNotificationsOnce() && sectionOf(intent) == null) askBattery()
    handleImportIntent(intent)    // импорт ссылки, файла, «Поделиться»
}
```

`openProvider` и `openAnnouncement` снимают свои extras через `intent?.removeExtra(...)` (`:349`, `:361-362`), но это меняет
копию intent в памяти процесса, а не базовый intent задачи, который хранит система.

Когда задачу, созданную внешним intent'ом (ссылка или файл из другого приложения, кнопка уведомления), открывают из
«Недавних» после того, как activity закрыта («Назад») или процесс выгружен, система запускает её заново **базовым intent'ом**
с флагом `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` и `savedInstanceState == null`. Флаг нигде не проверяется
(`grep LAUNCHED_FROM_HISTORY app/src` — пусто). Более того, внешние intent'ы приходят в `MainActivity`, а та перенаправляет
их в `SimpleActivity` (`ui/MainActivity.kt:88` → `simple/SimpleUi.kt:112-124`), и `simpleIntent` переносит из флагов только
права на URI (`SimpleUi.kt:158`, `source.flags and URI_GRANTS`). Поэтому `SimpleActivity` этот флаг даже не видит, и проверка
только в `SimpleActivity` не поможет.

Какие действия повторяются:
- ссылка `crown://addsub`, `clash://`, ссылка сервера из браузера или мессенджера — снова «Добавить подписку?/серверы?»
  (если уже добавлено — сообщение «уже есть»);
- присланный файл (`content://`) — повторное чтение, права на который уже отозваны, → «не удалось прочитать файл»;
- кнопка «Продлить»/«Личный кабинет» уведомления подписки — intent прямо в `SimpleActivity` с `FLAG_ACTIVITY_NEW_TASK`
  (`simple/subs/SubscriptionAlerts.kt:126-131`) — снова открывается браузер на странице оплаты и взводится повторная
  проверка подписки (`notices.leavingFor`);
- «Подробнее» уведомления объявления (`SubscriptionAlerts.kt:101`, `EXTRA_ANNOUNCE`) — снова лист объявления.

Classic (`MainActivity`) повторяет обработку реже: она корень задачи, «Назад» делает `moveTaskToBack`, поэтому повтор
случается после перезагрузки или выгрузки задачи; тогда VIEW-ссылка спросит ещё раз, а SEND-текст импортируется повторно
(дубликаты серверов).

### Влияние

- Пользователь, вернувшийся к Crown через «Недавние», получает уже отвеченный вопрос о подписке, ошибку чтения файла или
  внезапно открытую страницу оплаты в браузере. Спецификация simple-ui описывает эти действия как ответ на нажатие
  пользователя («Подтверждение ссылки из другого приложения», «Проверка после страницы провайдера», «Объявление целиком») —
  повтор без нажатия ей противоречит.
- Частота: каждое открытие из «Недавних» задачи, созданной ссылкой, файлом или уведомлением, пока её не смахнули.

### Как воспроизвести

Проверено на эмуляторе Android 17 (API 37), debug-сборка коммита `0a0fcc4e`, simple UI.

1. Убрать задачи Crown из «Недавних».
2. `adb shell am start -a android.intent.action.VIEW -d 'crown://addsub?url=<адрес подписки>&name=Once' dev.folki.crown.debug`
   → «Add this subscription?» → «Cancel».
3. Нажать «Назад» (SimpleActivity закрывается, задача остаётся в «Недавних»).
4. Открыть Crown из «Недавних». Журнал:
   ```
   ActivityTaskManager: START u0 {act=android.intent.action.VIEW dat=crown://addsub/... flg=0x10100000 … cmp=…/ui.MainActivity}
   ActivityTaskManager: START u0 {act=android.intent.action.VIEW dat=crown://addsub/... xflg=0x4 cmp=…/simple.SimpleActivity}
   ```
   Первый запуск несёт `0x00100000` (`FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`), перенаправленный — уже без него.
   Фактически: снова «Add this subscription?», хотя пользователь ответил «Отмена».
5. Страница провайдера (по коду): уведомление «Подписка заканчивается» → «Продлить» → вернуться → «Назад» → открыть из
   «Недавних» — снова открывается браузер.

## What Changes

1. Определять «одноразовость» по исходному intent'у до redirect: в `SimpleUi.simpleIntent` переносить флаг
   `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` (или при этом флаге не переносить одноразовые данные — action/data/extras импорта,
   `PROVIDER_*`, `OPEN_ANNOUNCE`).
2. В `SimpleActivity.onCreate` считать intent свежим только при
   `savedInstanceState == null && (intent.flags and FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0`; иначе не вызывать
   `openAnnouncement`, `openProvider`, `handleImportIntent` и не брать `EXTRA_SECTION`.
3. После обработки заменять intent activity на пустой (`setIntent(Intent(this, SimpleActivity::class.java))`), чтобы другие
   пересоздания тоже не видели одноразовых данных.
4. Classic: та же проверка флага в `MainActivity.handleImportIntent`.

Как проверить: шаги 2–4 — из «Недавних» открывается главная без диалога; уведомление «Продлить» → «Назад» → «Недавние» не
открывает браузер.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `simple-ui` (`openspec/changes/simple-ui-redesign/specs/simple-ui`): в «Подтверждение ссылки из другого приложения» и
  «Проверка после страницы провайдера» добавить сценарий: повторное открытие приложения из «Недавних» не повторяет вопрос,
  импорт и переход на страницу провайдера.

## Impact

- **Код:** `simple/SimpleUi.kt` (`simpleIntent`), `simple/SimpleActivity.kt` (`onCreate`), `ui/MainActivity.kt`
  (`handleImportIntent`).
- **Upstream:** simple — код Crown. `MainActivity.kt` — существующая точка правки 5 (redirect и внешние ссылки); проверка флага
  в её `handleImportIntent` укладывается в описание точки «crown:// links opened from outside» лишь частично — описание стоит
  расширить.
- **Ядро throne, сборка, инструменты:** не затрагиваются.
- **Риски:** из «Недавних» пользователь ждёт просто открыть приложение — пропуск одноразовых действий безопасен. Не
  перепутать с `onNewIntent` (там флага нет, обработка нужна).
- **Проверка исправления:** сценарий эмулятора «до/после»; инструментальный тест: запуск `MainActivity` intent'ом с
  `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` и VIEW `crown://addsub` не показывает диалог.
- **Внешние источники:** `Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`:
  https://developer.android.com/reference/android/content/Intent#FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
