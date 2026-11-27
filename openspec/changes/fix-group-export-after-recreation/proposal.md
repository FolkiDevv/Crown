# Proposal

> **Категория:** надёжность · **Приоритет:** средний ·
> **Проверка:** воспроизведено на эмуляторе ·
> **Код:** upstream · **Объём:** S

## Why

### Находка

«Экспорт в файл» на экране групп классического интерфейса хранит экспортируемую группу только в полях фрагмента:

```kotlin
// app/src/main/java/io/nekohasekai/sagernet/ui/GroupFragment.kt:73-79
private var exportGroup: ProxyGroup? = null
private var exportDeepLinks = false

private val exportProfiles = registerForActivityResult(SaveDocument("text/plain")) { uri ->
    val group = exportGroup ?: return@registerForActivityResult
    if (uri == null) return@registerForActivityResult
```

`exportToFile` (`GroupFragment.kt:176-180`) записывает эти поля и открывает системный выбор файла (`CreateDocument`). Пока он
на экране, `MainActivity` остановлена. Если за это время она пересоздаётся, результат получает новый `GroupFragment` с
`exportGroup == null`, и колбэк выходит на первой строке — ни записи, ни сообщения. Пересоздание происходит при повороте
(у `MainActivity` в манифесте только `configChanges="uiMode"`), изменении размера окна (складной экран, split-screen), смене
языка, а также после смерти процесса UI, пока открыт выбор файла (например, при выборе облачной папки).

Системный выбор файла к этому моменту уже **создал** документ: `CreateDocument` возвращает URI созданного пустого файла.
В итоге у пользователя пустой `profiles_<группа>.txt` и никакого сообщения об ошибке.

Соседний код делает это правильно: экспорт одного профиля кладёт содержимое в `DataStore.serverConfig` до открытия выбора
файла (`ui/profiles/ProfileItemMenu.kt:244`, запись в колбэке — `:42`).

### Влияние

- Экспорт профилей группы — часто резервная копия перед удалением группы или подписки — даёт пустой файл без сообщения об
  ошибке. Пользователь может удалить группу, рассчитывая на этот файл.
- Поворот телефона в окне выбора папки — обычное действие; на складных и планшетах пересоздание бывает и без поворота.
- Спецификация `profile-management`, «Import and share»: «SHALL export a profile as a share link or config text» — экспорт
  молча не выполняется.

### Как воспроизвести

Проверено на эмуляторе Android 17 (API 37), debug-сборка коммита `0a0fcc4e`.

1. Классический интерфейс → «Группы». В группе «Default» 4 профиля.
2. ⋮ у группы → «Export to file». Открывается системный выбор файла с именем `profiles_Default.txt`.
3. Повернуть экран: `adb shell settings put system accelerometer_rotation 0; adb shell settings put system user_rotation 1`.
4. Нажать «SAVE».
5. Фактически: снэкбара «Экспортировано» нет; `adb shell ls -l /sdcard/Download/` →
   `-rw-rw---- … 0 … profiles_Default.txt` (0 байт).
6. Контроль без поворота: тот же экспорт даёт `profiles_Default (1).txt` размером 2776 байт.
7. Вернуть поворот: `user_rotation 0`, `accelerometer_rotation 1`.

## What Changes

1. (Рекомендуется.) Сохранять id группы и флаг `deepLinks` в `onSaveInstanceState` фрагмента и восстанавливать в `onCreate`;
   колбэк читает группу заново из БД (`GroupRepo.get(id)`). Это переживает и поворот, и смерть процесса.
2. Если группы уже нет (удалена) или состояние не восстановилось — показать сообщение об ошибке вместо молчаливого выхода и
   удалить созданный пустой документ (`DocumentsContract.deleteDocument`).

Как проверить: шаги 1–5 — файл содержит ссылки профилей, показан снэкбар; вариант со смертью процесса (Home →
`am kill` в окне выбора файла → «SAVE») тоже даёт непустой файл.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
(нет) — требование «Import and share» в `profile-management` уже обещает экспорт; меняется только его выполнение.

## Impact

- **Код:** `app/src/main/java/io/nekohasekai/sagernet/ui/GroupFragment.kt` (`exportToFile`, `exportProfiles`, сохранение состояния).
- **Upstream:** файл upstream вне `TOUCH_POINTS` — нужен патч в upstream или новая точка правки. Simple UI этот экран не открывает.
- **Ядро throne, сборка, инструменты:** не затрагиваются.
- **Риски:** почти нет; удаление пустого документа при ошибке требует права записи, которое у результата `CreateDocument` есть.
- **Проверка исправления:** сценарий эмулятора «до/после» (поворот, `am kill`); Robolectric-тест с `ActivityScenario.recreate()`
  между запуском контракта и результатом.
- **Внешние источники:** Activity Result API — состояние для обработки результата должно переживать пересоздание:
  https://developer.android.com/training/basics/intents/result#separate
