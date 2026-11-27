# Proposal

> **Категория:** надёжность · **Приоритет:** средний ·
> **Проверка:** воспроизведено на эмуляторе (флаг правок); редактор цепочки — подтверждено по коду ·
> **Код:** upstream · **Объём:** S–M

## Why

### Находка

Классические редакторы профилей (`ProfileSettingsActivity` и ~26 подклассов) хранят значения полей в
`DataStore.profileCacheStore`, а признак «есть несохранённые правки» — там же, в `DataStore.dirty`. Признак выставляет
слушатель записи и читает обработчик «Назад»:

```kotlin
// app/src/main/java/io/nekohasekai/sagernet/ui/profile/ProfileSettingsActivity.kt:327-333
override fun handleOnBackPressed() {
    if (DataStore.dirty) {
        UnsavedChangesDialogFragment().apply { key() }.show(supportFragmentManager, null)
    } else {
        finish()
```
Но при каждом создании view фрагмента признак принудительно перезаписывается полем нового экземпляра activity:

```kotlin
// ProfileSettingsActivity.kt:385-393
override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    ...
        DataStore.dirty = dirtyAfterRebuild      // у нового экземпляра activity — false
        dirtyAfterRebuild = false
```
`onViewCreated` вызывается и для фрагмента, который FragmentManager восстанавливает после пересоздания activity. Редакторы
в манифесте обрабатывают сами только `uiMode` (`app/src/main/AndroidManifest.xml:223-224` и далее для каждого редактора),
поэтому поворот, изменение размера окна (split-screen, складной экран), смена языка или шрифта пересоздают activity.
Значения полей это переживают (кэш в памяти процесса цел), а признак — нет: после поворота «Назад» закрывает редактор без
вопроса и правки пропадают.

Редактор цепочки (`ui/profile/ChainSettingsActivity.kt`) теряет больше. Список звеньев живёт только в поле activity
`proxyList` (`:71`); `KEY_CHAIN_IDS` пишется в кэш только в `init()` (`:353-355`). Добавление, замена, перетаскивание и
удаление звеньев меняют лишь `proxyList`. После пересоздания `reload()` (`:443-444`) читает из кэша исходный список
(`storedHopIds()`, `:108`), то есть все правки звеньев откатываются, а `replacing` (`:532`) снова 0. Результат выбора
профиля из `ProfileSelectActivity` применяется асинхронно (`:536-561`) параллельно с `reload()`, который заполняет тот же
`ArrayList` в фоне, — порядок звеньев зависит от гонки.

### Влияние

- Тихая потеря введённых данных (адрес, порт, ключи, звенья цепочки) после поворота или изменения окна — без диалога
  «Сохранить изменения?». На планшетах, складных устройствах и в split-screen пересоздание бывает без участия пользователя.
- Для цепочки — ещё и неверный порядок звеньев при сохранении после гонки; цепочка с перепутанными звеньями не подключается
  или идёт не тем маршрутом (`outbound-protocols`, «Chained outbounds»).
- Редакторы открываются и из simple UI («Изменить параметры» в расширенном режиме).

### Как воспроизвести

Проверено на эмуляторе Android 17 (API 37), debug-сборка коммита `0a0fcc4e`.

1. Классический интерфейс → карандаш у профиля Shadowsocks → «Profile name» → изменить имя → OK.
2. Контроль: «Назад» показывает «Save changes? — Cancel / Discard / Save». Нажать «Cancel».
3. Повернуть экран: `adb shell settings put system accelerometer_rotation 0; adb shell settings put system user_rotation 1`.
   Имя в форме по-прежнему новое.
4. Нажать «Назад». Фактически: редактор закрывается без вопроса, в списке профиль с прежним именем — правка потеряна.
5. Цепочка (по коду): создать «Цепочку», добавить два звена, повернуть экран — список звеньев пуст для нового профиля или
   исходный для существующего; вариант: «+» → в списке выбора повернуть экран → выбрать сервер → прежние добавленные звенья
   исчезли.
6. Вернуть поворот: `user_rotation 0`, `accelerometer_rotation 1`.

## What Changes

1. Не терять признак при восстановлении: сохранять `DataStore.dirty` (или собственное поле) в `onSaveInstanceState` activity
   и восстанавливать; в `onViewCreated` сбрасывать признак только при первом создании (`savedInstanceState == null`) или после
   пересборки из JSON (`dirtyAfterRebuild`).
2. В `ChainSettingsActivity` писать `KEY_CHAIN_IDS` в кэш при каждом изменении `proxyList` (добавление, замена, перенос,
   удаление) и сохранять `replacing` в `onSaveInstanceState`; заполнять `proxyList` в `reload()` на главном потоке (собрать
   список в фоне, присвоить в главном), а результат выбора применять после загрузки.
3. Дешёвая частичная мера (не вместо п. 1–2): `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize"`
   для редакторов в манифесте — закрывает поворот и размер окна, но не смену языка/шрифта и не смерть процесса
   (см. `keep-editor-drafts-after-process-death`).

Как проверить: шаги 1–5 — после поворота «Назад» показывает «Save changes?», звенья цепочки и их порядок сохраняются.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `outbound-protocols`: в «Chained outbounds» уточнить, что порядок звеньев, заданный в редакторе, сохраняется при
  пересоздании экрана.

## Impact

- **Код:** `ui/profile/ProfileSettingsActivity.kt` (`onViewCreated`, `onSaveInstanceState`), `ui/profile/ChainSettingsActivity.kt`
  (`reload`, операции со звеньями, `replacing`); для п. 3 — `app/src/main/AndroidManifest.xml`.
- **Upstream:** `ProfileSettingsActivity.kt` и `ChainSettingsActivity.kt` вне `TOUCH_POINTS` — новая точка правки или патч в
  upstream. Манифест — существующая точка правки 3, но её описание (декларации simple, `crown://`) пришлось бы расширить.
- **Ядро throne, сборка, инструменты:** не затрагиваются.
- **Риски:** при п. 3 activity должна корректно перерисоваться без пересоздания (Preference-экраны это умеют). При п. 1 не
  поднимать признак ложно после пересборки из JSON.
- **Проверка исправления:** сценарий эмулятора «до/после»; Robolectric-тест `ActivityScenario.recreate()` с изменённым полем —
  после пересоздания «Назад» показывает диалог.
