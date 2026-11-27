# Proposal

> **Категория:** надёжность · **Приоритет:** средний ·
> **Проверка:** воспроизведено на эмуляторе ·
> **Код:** Crown · **Объём:** S–M

## Why

### Находка

«Удалить» у своего сервера в simple UI удаляет его из БД сразу, а возможность отмены передаётся одним сообщением с
замыканием:

```kotlin
// app/src/simple/java/io/nekohasekai/sagernet/simple/servers/ServersViewModel.kt:361-376
val outcome = ProfileManager.batchDeleteProfiles(listOf(id), stopRunning = true)
...
mutableMessages.tryEmit(
    ServersMessage(R.string.simple_server_deleted, name, R.string.simple_server_undo) {
        manage {
            val copy = ProfileManager.createProfile(profile.groupId, profile.outbound)
            TrafficModeApplier.serverRestored(copy.id, wasSelected)
```

`messages` — `MutableSharedFlow(extraBufferCapacity = 4)` без replay (`ServersViewModel.kt:120`): выпущенное без подписчика
пропадает. Подписчик — `LaunchedEffect` в композиции раздела «Серверы», а снекбар с действием показывается в дочерней
корутине этого эффекта:

```kotlin
// app/src/simple/java/io/nekohasekai/sagernet/simple/SimpleActivity.kt:1124-1137
LaunchedEffect(servers) {
    servers.messages.collect { message ->
        ...
            launch {
                val result = snackbar.showSnackbar(text, actionLabel = …, duration = SnackbarDuration.Long)
                if (result == SnackbarResult.ActionPerformed) action()
```

Значит, «Вернуть» существует, только пока раздел «Серверы» в композиции и activity не пересоздана:
- переход на «Главную» или в «Настройки» убирает раздел из дерева, эффект отменяется, `showSnackbar` прерывается и снимает
  снекбар — `action()` уже не вызвать;
- `SimpleActivity` не объявляет `android:configChanges` для поворота и размера окна, поэтому поворот, сгиб/разворот,
  изменение окна в многооконном режиме, смена языка или шрифта пересоздают activity; `SnackbarHostState` создан через
  `remember` (`SimpleActivity.kt:790`), а сообщение уже прочитано из SharedFlow;
- если пользователь ушёл из раздела раньше, чем удаление в `manage {}` дошло до `tryEmit`, сообщения нет вовсе.
Ни в одном случае пользователь не видит, что отмены больше нет.

Та же причина у других снекбаров раздела, которые выпускаются без подписчика (например, «Ошибка обновления подписки»),
но там пользователь ничего не теряет.

### Влияние

- Спецификация simple-ui, «Управление своими серверами»: «"Удалить" MUST удалять сразу со снекбаром "Вернуть", который
  восстанавливает сервер и его выбор» (сценарий «Отмена удаления»). Удалив свой сервер по ошибке и в ту же секунду повернув
  телефон или нажав «Главная», пользователь теряет его без возможности вернуть; если сервер был выбран, теряется и выбор.
- Свой сервер часто добавлен однократно по ссылке из мессенджера — восстановить его пользователю неоткуда. Частота
  невелика, но цена — потеря данных, от которой «Вернуть» и должен защищать.

### Как воспроизвести

Проверено на эмуляторе Android 17 (API 37), debug-сборка коммита `0a0fcc4e`, simple UI.

1. В «Моих серверах» есть хотя бы два своих сервера.
2. «Серверы» → «Server actions» у сервера → «Delete». Появляется снекбар с кнопкой «Undo».
3. Вариант А: нажать «Home» в нижней панели, затем снова «Servers» (≈7 с, меньше длительности `SnackbarDuration.Long` — 10 с).
   Фактически: снекбара «Undo» нет, сервер удалён.
4. Вариант Б: сразу после «Delete» повернуть экран
   (`adb shell settings put system accelerometer_rotation 0; adb shell settings put system user_rotation 1`). Через 4 с после
   удаления в дереве доступности нет ни «Undo», ни сервера.
5. Для сравнения: без шага 3/4 «Undo» в течение 10 с восстанавливает сервер (путь `action()` → `createProfile`; на
   эмуляторе отдельно не прогонялся).
6. Вернуть поворот: `user_rotation 0`, `accelerometer_rotation 1`.

## What Changes

1. (Рекомендуется.) Держать отложенную отмену в состоянии ViewModel, а не в замыкании сообщения: например,
   `ServersUiState.undo = UndoDelete(groupId, outbound, wasSelected, expiresAt)` (все данные уже есть в `deleteServer`).
   Снекбар рисовать по этому состоянию в хосте уровня всего экрана (`SnackbarHostState` в `SimpleApp`, общий для разделов),
   по «Вернуть» или по истечении срока — очищать.
2. Чтобы отмена пережила и смерть процесса, положить `UndoDelete` в `SavedStateHandle` (у `ModesViewModel` и
   `NoticesViewModel` он уже есть). Outbound сериализуется в JSON — это небольшая строка.
3. Для остальных одноразовых сообщений раздела — `Channel` вместо `SharedFlow` (как в `AddViewModel`), чтобы сообщение
   дождалось подписчика.
4. Альтернатива — «мягкое» удаление (скрыть строку сразу, удалить из БД по истечении снекбара). Сложнее из-за `:bg` и
   автовыбора, который должен перестать использовать сервер сразу.

Как проверить: шаги 3 и 4 — после возврата в раздел или поворота «Вернуть» виден до истечения срока и восстанавливает
сервер и его выбор; повторное «Вернуть» не создаёт второй копии.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `simple-ui` (`openspec/changes/simple-ui-redesign/specs/simple-ui`): в «Управление своими серверами» добавить сценарий:
  «Вернуть» доступен в течение показа снекбара и после смены раздела или поворота экрана.

## Impact

- **Код:** `simple/servers/ServersViewModel.kt` (`deleteServer`, состояние отмены), `simple/SimpleActivity.kt` (показ снекбара
  на уровне `SimpleApp`, `ServersSection`).
- **Upstream:** код Crown, точки правки не нужны.
- **Ядро throne, сборка, инструменты:** не затрагиваются.
- **Риски:** снекбар отмены станет виден и на «Главной» — для отмены это правильно. Нужно не восстановить сервер дважды:
  очищать состояние до запуска восстановления.
- **Проверка исправления:** сценарий эмулятора «до/после»; Compose UI-тест: удалить → сменить раздел → вернуться →
  нажать «Вернуть» → сервер в списке и выбран, если был выбран.
