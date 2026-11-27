# Proposal

> **Категория:** надёжность · **Приоритет:** высокий · **Проверка:** воспроизведено на эмуляторе ·
> **Код:** upstream · **Объём:** S–M

## Why

### Находка
Все данные пользователя — группы и подписки, профили, маршруты и таблица `settings` — лежат в одном файле
`sager_net.db` (`Key.DB_PROFILE`). Код его открытия удаляет файл при **любом** исключении, а не только при
повреждении:

```kotlin
// app/src/main/java/io/nekohasekai/sagernet/database/SagerDatabase.kt:217-230
val instance by lazy {
    ...
    val opened = try {
        db.openHelper.writableDatabase
        db
    } catch (e: Exception) {
        Log.e(TAG, "open failed", e)
        runCatching { db.close() }
        backupCorruptedDatabase()
        SagerNet.application.deleteDatabase(Key.DB_PROFILE)
        buildProfileDatabase()
    }
```

`db.openHelper.writableDatabase` не только открывает файл: внутри него Room выполняет миграции и проверку схемы.
Поэтому в `catch` попадают ошибки, не связанные с порчей файла: сбой миграции (`SQLiteException` из `ALTER TABLE`,
`IllegalStateException` Room о несовпадении схемы), нехватка места (`SQLiteFullException`, `SQLiteDiskIOException`),
`SQLiteCantOpenDatabaseException` при недоступном файле, `SQLiteDatabaseLockedException`.

Копия перед удалением делается в `runCatching` (`SagerDatabase.kt:236-252`), её сбой только пишется в лог, а
`deleteDatabase` выполняется всё равно. Если файл нельзя прочитать или нет места, данные удаляются **без копии**. Если
копия создалась, она лежит в приватном `databases/` под именем `sager_net.db.bak_<время>`: пользователь о ней не
узнаёт, и приложение не умеет её восстановить.

Второй путь к той же потере — `fallbackToDestructiveMigration()` и `fallbackToDestructiveMigrationOnDowngrade()`
(`SagerDatabase.kt:211-212`). Сегодня цепочка миграций 8→20 полная (`autoMigrations` в `@Database` и
`addMigrations(...)` в `SagerDatabase.kt:203-205`), но если в следующем повышении `version` миграцию забыть, Room не
упадёт, а молча пересоздаст все таблицы у пользователей.

Отдельный признак повреждения (`SQLiteDatabaseCorruptException`) в коде не выделяется, `DatabaseErrorHandler` не задан.

### Влияние
Пользователь открывает приложение и видит экран первого запуска: нет подписок, своих серверов, маршрутов, все
настройки сброшены. VPN не подключится, пока подписку не добавят заново, а ссылки может уже не быть. Сообщения об
ошибке нет. Каждый триггер редок (переполненная память во время обновления приложения, ошибка в будущей миграции,
сбой ввода-вывода, недоступный файл), но исход — полная потеря данных. Нарушается
`openspec/specs/profile-management` «Profile persistence» (профили и статистика сохраняются между перезапусками) и
`openspec/specs/settings-storage` «Typed settings access» (настройки в той же БД).

### Как воспроизвести
Debug-сборка `dev.folki.crown.debug`, Android 17 (API 37), эмулятор x86_64.

1. `pm clear dev.folki.crown.debug`, пройти первый запуск, добавить любой сервер (например,
   `socks5://10.0.2.2:21500#Local`). В БД: groups 1, profiles 3, route_profiles 2, route_rules 5, settings 12.
2. `adb shell am force-stop dev.folki.crown.debug`; сохранить копию `databases/sager_net.db*` через
   `adb exec-out run-as dev.folki.crown.debug cat databases/<файл>`.

Опыт A — файл временно недоступен:

3. `adb shell run-as dev.folki.crown.debug chmod 000 databases/sager_net.db`, запустить приложение.
4. Журнал:
   ```
   E/SagerDatabase: open failed
   E/SagerDatabase: android.database.sqlite.SQLiteCantOpenDatabaseException: ... (code 14 SQLITE_CANTOPEN): Permission denied
   W/SagerDatabase: Failed to backup corrupted database before rebuild
   W/SagerDatabase: java.io.FileNotFoundException: .../databases/sager_net.db: open failed: EACCES (Permission denied)
   ```
   В `databases/` — новый пустой `sager_net.db` (4096 байт), **ни одной копии `.bak_*`**; приложение показывает
   первый запуск.

Опыт B — сбой миграции (имитация ошибки в будущей миграции):

5. На хосте в сохранённой копии сделать checkpoint WAL и выставить `PRAGMA user_version=19` (колонка
   `groups.pinned_json` уже есть). Вернуть файл: `adb exec-in run-as dev.folki.crown.debug sh -c 'cat > databases/sager_net.db'`.
6. Запустить приложение. Журнал:
   ```
   E/SagerDatabase: open failed
   E/SagerDatabase: android.database.sqlite.SQLiteException: duplicate column name: pinned_json ... ALTER TABLE `groups` ADD COLUMN `pinned_json` ...
   I/SagerDatabase: Corrupted database backed up as sager_net.db.bak_1795744530361
   ```
   БД пустая, копия 69632 байта лежит в приватном каталоге, пользователь ничего не видит.

Тестом: инструментальный тест создаёт БД версии 19 с уже добавленной колонкой и проверяет, что после
`SagerDatabase.instance` строки `groups`/`profiles` на месте. Сейчас он упадёт.

## What Changes
1. Удалять и пересоздавать файл **только при повреждении**: `SQLiteDatabaseCorruptException` (в том числе через
   `DatabaseErrorHandler`, который SQLite вызывает именно при порче). Любое другое исключение при открытии или
   миграции — не трогать файл: показать экран «Не удалось открыть данные» с кнопками «Повторить» и «Сохранить файл
   данных», либо дать упасть с понятным отчётом. Рекомендуется экран: данные остаются на диске до исправления, а
   цикл падений не возникает.
2. Перед удалением повреждённого файла убедиться, что копия реально создана (иначе не удалять). После пересоздания
   показать пользователю, что данные были повреждены, и предложить восстановление из резервной копии `.thrbackup`
   (связано с `add-automatic-backups`).
3. Заменить `fallbackToDestructiveMigration()` на явный список старых версий (`fallbackToDestructiveMigrationFrom(...)`
   для версий ниже 8, если они вообще встречаются) — пропущенная миграция должна ломать сборку или тест, а не
   данные. Коммит схем 18–20 и тесты `MigrationTestHelper` — в `fix-room-schema-export-race`.
4. Понижение версии (`OnDowngrade`) — оставить решение владельцу; минимум — не стирать молча, а предупреждать.

Проверка «до/после»: опыты A и B оставляют профили на месте (после возврата прав или исправления миграции
приложение открывает старые данные); файл с мусором в первых 100 байтах по-прежнему пересоздаётся с копией.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `profile-management`: в «Profile persistence» добавить сценарий: ошибка открытия или миграции БД, не являющаяся
  повреждением файла, не удаляет данные; при повреждении удаление выполняется только после успешной копии, и
  пользователь видит сообщение.
- `settings-storage`: то же для таблицы настроек (уточнение к «Typed settings access»).

## Impact
- **Код:** `app/src/main/java/io/nekohasekai/sagernet/database/SagerDatabase.kt` (`instance`, `backupCorruptedDatabase`,
  `buildProfileDatabase`); экран ошибки — в обоих интерфейсах (simple и classic), так как оба стартуют через
  `DataStore` → `SagerDatabase.instance`.
- **Upstream:** `SagerDatabase.kt` нет в `tools/diagnostics/upstream_touch_points.py` — нужна новая точка правки
  (или патч в upstream: код открытия написан там же).
- **Ядро throne, сборка, инструменты:** ядро не затрагивается; сборка — только тест миграций.
- **Риски:** первым открывает БД любой из двух процессов (UI или `:bg`); экран ошибки нужен в UI, а `:bg` должен
  остановиться без записи, иначе процессы разойдутся по разным файлам. Без автоматического пересоздания при не
  распознанной порче возможен цикл падений — поэтому ветка для `SQLiteDatabaseCorruptException` остаётся.
- **Проверка исправления:** инструментальные тесты на опыты A и B; ручной сценарий с испорченным заголовком файла.
- **Внешние источники:** Android `SQLiteOpenHelper` / `DatabaseErrorHandler`
  (https://developer.android.com/reference/android/database/DatabaseErrorHandler), Room
  `fallbackToDestructiveMigrationFrom` (https://developer.android.com/reference/androidx/room/RoomDatabase.Builder, Room 2.6).
