# Proposal

> **Категория:** стабильность · **Приоритет:** низкий · **Проверка:** подтверждено по коду ·
> **Код:** upstream · **Объём:** S (общий обработчик) / M (точечная обработка)

## Why

### Находка
Классический интерфейс запускает фоновую работу помощниками из `ktx/Asyncs.kt`. Это корутины без владельца и без
обработчика ошибок:

```kotlin
// app/src/main/java/io/nekohasekai/sagernet/ktx/Asyncs.kt:9-10, 15-19
fun runOnDefaultDispatcher(block: suspend CoroutineScope.() -> Unit) =
    GlobalScope.launch(Dispatchers.Default, block = block)
fun runOnIoDispatcher(...) = GlobalScope.launch(Dispatchers.IO, block = block)
fun runOnMainDispatcher(...) = GlobalScope.launch(Dispatchers.Main.immediate, block = block)
```

Исключение внутри такой корутины уходит в обработчик необработанных исключений процесса — `CrashHandler`
(`utils/CrashHandler.kt:19-39`). Он перезапускает процесс UI в `BlankActivity` с отчётом о падении.

В этих корутинах classic пишет в БД на обычных действиях пользователя, и `try` там нет:

| Место | Действие | Запись |
|---|---|---|
| `ui/ConfigurationFragment.kt:662-671` | выбор сервера в списке | `DataStore.selectedProxy`, `ProfileManager.postUpdate`, `reloadService` |
| `ui/GroupFragment.kt:186-191` | «Очистить группу» | `ProfileManager.batchDeleteProfiles` |
| `ui/profiles/ProfileListAdapter.kt:799-801` | удаление выбранных серверов | `ProfileManager.deleteProfile` в цикле |
| `ui/MainActivity.kt:620-623`, `ui/SwitchActivity.kt:32-35` | смена сервера из уведомления или плитки | `ProfileManager.postUpdate` ×2 |

В простом интерфейсе те же действия обёрнуты: `ServersViewModel.manage/choose`
(`app/src/simple/java/io/nekohasekai/sagernet/simple/servers/ServersViewModel.kt:425-455`) ловят `Exception`, пишут в
журнал и показывают сообщение «Не удалось…». В classic та же ошибка записи роняет процесс.

Типичные причины исключения:
- `SQLiteFullException` или `SQLiteDiskIOException` при заполненной памяти устройства;
- `SQLiteDatabaseLockedException`, если запись UI ждёт дольше busy-timeout SQLite, пока `:bg` держит транзакцию (оба
  процесса пишут в одну БД).

Часть записей classic делает прямо в главном потоке (например, `MainActivity.cbSelectorUpdate`, `MainActivity.kt:616-618`).
Общий обработчик корутин эти места не закроет. Поэтому решение ниже снижает риск, но не устраняет его полностью.

### Влияние
На телефоне с почти заполненной памятью выбор или удаление сервера в classic приводит к перезапуску приложения с
экраном отчёта о падении, хотя должно появиться сообщение об ошибке. VPN в `:bg` продолжает работать. Classic
открывают те, кто выбрал его вручную, и пользователи расширенного режима simple. Сценарий редкий, поэтому приоритет низкий.

### Как воспроизвести
Шаги выведены из кода и не прогонялись.
1. Эмулятор с debug-сборкой, classic-интерфейс, группа с несколькими серверами.
2. Заполнить `/data`, оставив единицы МБ: `adb shell dd if=/dev/zero of=/data/local/tmp/fill bs=1m count=<свободно − 5>`.
3. В «Профилях» выбрать другой сервер, затем удалить несколько серверов, затем «Очистить группу».
4. Открыть `adb logcat -s AndroidRuntime CrashHandler`. Ожидается `SQLiteFullException` в потоке `DefaultDispatcher-worker-N`
   и перезапуск в `BlankActivity`. Для сравнения: то же действие в simple показывает сообщение и не падает.
5. Удалить `/data/local/tmp/fill`.

Тестом: JVM-тест над `Asyncs`, где блок бросает `SQLiteFullException`. Сейчас исключение доходит до
`Thread.getDefaultUncaughtExceptionHandler`. После исправления оно должно перехватываться и записываться в журнал.

## What Changes
1. **Рекомендуется:** дать помощникам `Asyncs.kt` общий `CoroutineExceptionHandler` для процесса UI. Он перехватывает
   только `SQLiteException` и `IOException`, пишет их в журнал (`Logs.w`) и показывает короткое сообщение «Не удалось
   сохранить изменение». Остальные исключения по-прежнему уходят в `CrashHandler`, чтобы программные ошибки не скрывались.
   Это одна правка, и она закрывает все пути classic, запущенные через эти помощники.
2. Дополнительно на путях из таблицы: точечный `try/catch` с понятным сообщением, как в simple (`manage/choose`).
   После ошибки перечитать состояние, чтобы список не показывал удаление, которое не прошло.
3. Записи в главном потоке (`cbSelectorUpdate` и др.) в этот change не входят: их перенос в фон — часть общего вопроса
   о доступе к БД из главного потока.

Как проверить: в сценарии выше вместо перезапуска появляется сообщение. Обычные программные ошибки в тех же корутинах
по-прежнему видны в `CrashHandler`.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `profile-management`: уточнить, что ошибку записи при выборе, удалении сервера или очистке группы пользователь видит
  как сообщение, и интерфейс не падает (так уже работает simple).

## Impact
- **Код:** `app/src/main/java/io/nekohasekai/sagernet/ktx/Asyncs.kt`; для п. 2 — `ui/ConfigurationFragment.kt`, `ui/GroupFragment.kt`,
  `ui/profiles/ProfileListAdapter.kt`, `ui/SwitchActivity.kt`, `ui/MainActivity.kt`.
- **Upstream:** все файлы — код upstream. `Asyncs.kt` и `ui/*` не входят в `TOUCH_POINTS`. Исключение — `MainActivity.kt`
  (точка 5), но её назначение — перенаправление в simple. Нужна новая точка правки (`Asyncs.kt`) или патч в upstream.
  Патч полезен и самому upstream, его стоит предложить туда.
- **Ядро throne, сборка, инструменты:** не затрагиваются.
- **Риски:** общий обработчик меняет поведение всех мест, запущенных через эти помощники, в обоих процессах. Его нужно
  ограничить процессом UI и перечисленными типами исключений. Нужно убедиться, что ни одно место не рассчитывает на падение
  как на остановку. В `:bg` ошибка записи сейчас тоже роняет процесс, и это отдельный вопрос.
- **Проверка исправления:** сценарий с заполненной памятью; JVM-тест обработчика.
