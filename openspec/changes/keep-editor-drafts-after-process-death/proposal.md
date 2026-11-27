# Proposal

> **Категория:** надёжность, стабильность · **Приоритет:** средний ·
> **Проверка:** воспроизведено на эмуляторе ·
> **Код:** upstream · **Объём:** S (идентичность и перезагрузка формы) / M (сохранение самих правок)

## Why

### Находка

Классические редакторы профиля и группы держат всю форму — id редактируемого объекта, его группу, значения полей и признак
правок — в `DataStore.profileCacheStore`, а это Room-база **в памяти** процесса:

```kotlin
// app/src/main/java/moe/matsuri/nb4a/TempDatabase.kt:16-17
private val instance by lazy {
    Room.inMemoryDatabaseBuilder(SagerNet.application, TempDatabase::class.java)
// app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt:344-346
var dirty by profileCacheStore.boolean(Key.PROFILE_DIRTY)
var editingId by profileCacheStore.long(Key.PROFILE_ID)
var editingGroup by profileCacheStore.long(Key.PROFILE_GROUP)
```

Кэш заполняется только при первом создании activity (`savedInstanceState == null`). После поворота процесс жив и кэш цел.
Но если система убила процесс UI, пока редактор был в фоне, Android восстанавливает activity с `savedInstanceState != null`,
а кэш уже пуст.

**Редактор профиля** (`ui/profile/ProfileSettingsActivity.kt`, ~26 подклассов; его открывает и simple UI — «Изменить
параметры» в расширенном режиме). Ветка восстановления ничего не загружает (`:128-158`, в `else` только `isSubscription`),
поэтому `DataStore.editingId == 0`, а `ensureEditingOutbound()` создаёт пустой объект (`:162-166`). Форма показывает
значения по умолчанию, меню — как у нового профиля. «Применить» идёт по ветке нового профиля:

```kotlin
// ProfileSettingsActivity.kt:235-239
val editingId = DataStore.editingId
if (editingId == 0L) {
    ProfileManager.createProfile(DataStore.editingGroup, outbound)   // editingGroup == 0 → текущая группа
```
Исходный профиль не меняется, в текущей группе появляется новый. JSON-редактор (`ui/json/JsonEditorActivity.kt`) сам
сохраняет текст в `onSaveInstanceState`, поэтому после правки JSON экран выглядит правильно, но «Сохранить» тоже создаёт копию.

**Редактор группы/подписки** (`ui/GroupSettingsActivity.kt`; simple UI открывает его в «Настройках подписки»,
`simple/SimpleActivity.kt:1168-1170`). Данные группы перечитываются из БД (`:195-199`), но форма заполняется только при
`fresh` (`:200-201`, `writeToCache(group)`). После смерти процесса фрагмент строится по пустому кэшу, и
`MultiSelectListPreference` из `group_preferences.xml:205` получает `null` от `RoomPreferenceDataStore.getStringSet`
(`database/preference/RoomPreferenceDataStore.kt:22-23`, значение по умолчанию `null`) — экран падает:

```
java.lang.NullPointerException: … java.util.Collection.iterator() on a null object reference
    at androidx.preference.MultiSelectListPreference.setValues(MultiSelectListPreference.java:149)
    at androidx.preference.MultiSelectListPreference.onSetInitialValue(MultiSelectListPreference.java:209)
    …
    at io.nekohasekai.sagernet.ui.GroupSettingsActivity$GroupPreferenceFragment.onCreatePreferences(GroupSettingsActivity.kt:519)
    at io.nekohasekai.sagernet.ui.GroupSettingsActivity$onCreate$3.invokeSuspend(GroupSettingsActivity.kt:234)
```
Само падение не даёт дойти до «Сохранить» с пустой формой, поэтому URL подписки в этой сборке не затирается. Но если
падение устранят, не устранив причину, `save()` (`:433-461`) соберёт группу из пустого кэша: `groupType` по умолчанию
«обычная», `subscriptionLink` пуст — и подписка потеряет URL, фильтры и метаданные. Исправлять надо причину.

### Влияние

- Профиль: пользователь меняет адрес или ключ, уходит в браузер или мессенджер за данными, система выгружает процесс UI.
  По возвращении — пустая форма («профиль стёрся»). Если заполнить её и сохранить, в группе появляется дубликат, а VPN
  продолжает работать со старым профилем без вопроса о перезапуске. Правки потеряны.
- Группа: при возвращении — экран отчёта о падении вместо настроек подписки; несохранённые правки потеряны.
- Частота: только при смерти процесса UI в фоне — обычное дело на устройствах с 3–4 ГБ памяти и на прошивках с агрессивной
  выгрузкой. `AGENTS.md`: «Android can recreate an activity or kill the process at any time. State kept only in memory is lost.»

### Как воспроизвести

Проверено на эмуляторе Android 17 (API 37), debug-сборка коммита `0a0fcc4e`, VPN выключен.

1. Профиль: классический интерфейс → группа «Default» → карандаш у профиля Shadowsocks «Injected». Форма заполнена
   (имя, сервер, порт, метод, пароль).
2. Home → `adb shell am kill dev.folki.crown.debug` → открыть Crown из «Недавних».
3. Фактически: «Profile name: Not set», «Server: Not set», «Remote port: Not set», «Encryption method: none».
4. Ввести имя «Edited», сервер и порт, нажать ✓. В группе «Default» два профиля: исходный «Injected» без изменений и новый
   «Edited».
5. Группа: «Группы» → карандаш у подписки (поля заполнены, «URL …») → Home → `am kill` → «Недавние». Фактически:
   `NullPointerException` в `MultiSelectListPreference.setValues` (стек выше), процесс перезапущен с экраном отчёта о падении.

## What Changes

1. (Рекомендуется, S.) Связать кэш формы с сеансом редактирования. При первом заполнении писать в `profileCacheStore` маркер
   «форма загружена для объекта N» (отдельный ключ для профиля и для группы). При `savedInstanceState != null` и отсутствии
   или несовпадении маркера заново загрузить объект из БД, как при первом открытии:
   - профиль: id брать из intent (`EXTRA_PROFILE_ID` уже там) и выполнить ту же загрузку, что в ветке `savedInstanceState == null`;
   - группа: вызвать `writeToCache(group)` и для восстановления.
   Тогда после смерти процесса теряются только несохранённые правки, но не идентичность объекта, и не возникает ни пустой
   формы, ни дубликата, ни падения.
2. (M.) Сохранять сами правки: снимок ключей формы в `onSaveInstanceState` (простые типы, десятки ключей) и восстановление в
   кэш до создания фрагмента. Для JSON-редактора уже есть сохранение текста — его достаточно применить к загруженному профилю.
3. Страховка: `save()` у существующей группы не сохраняет, если маркера нет (форма не загружена в этом сеансе);
   `saveAndExit()` у профиля при пустом `editingId`, но ненулевом `EXTRA_PROFILE_ID` не создаёт новый профиль.

Как проверить: шаги 1–5 — форма показывает сохранённый профиль/группу (с правками для п. 2), «Сохранить» обновляет исходный
id, падения нет; при повороте экрана живые правки не перезаписываются из БД (маркер совпадает).

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `profile-management`: добавить в «Profile persistence» сценарий «редактор, восстановленный после смерти процесса,
  редактирует тот же профиль или группу; сохранение не создаёт новый профиль и не меняет тип и URL группы».

## Impact

- **Код:** `ui/profile/ProfileSettingsActivity.kt` (onCreate, `ensureEditingOutbound`, `saveAndExit`),
  `ui/GroupSettingsActivity.kt` (onCreate, `save`), возможно `ui/RouteRuleActivity.kt` (та же схема кэша, не проверялся).
- **Upstream:** файлы upstream вне `TOUCH_POINTS` — нужна новая точка правки (`ProfileSettingsActivity.kt`,
  `GroupSettingsActivity.kt`; оба экрана открываются из simple UI) или патч в upstream. `DataStore.kt` — точка идентичности
  форка, но правка в нём не обязательна.
- **Ядро throne, сборка, инструменты:** не затрагиваются.
- **Риски:** перезагрузка из БД не должна срабатывать при повороте (иначе сотрёт живые правки) — для этого маркер хранится
  в самом кэше, который живёт ровно столько, сколько процесс. `profileCacheStore` общий для всех редакторов: маркер должен
  быть отдельным ключом.
- **Проверка исправления:** Robolectric/инструментальный тест: activity создаётся с непустым `savedInstanceState` и пустой
  `TempDatabase` → форма заполнена из БД, сохранение обновляет исходный id; сценарий эмулятора из шагов 1–5.
