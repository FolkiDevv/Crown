# Proposal

> **Категория:** надёжность · **Приоритет:** средний (прямого вреда пользователю нет; это предпосылка для проверки
> исправлений высокого и критического приоритета) · **Проверка:** подтверждено по коду · **Код:** оба ·
> **Объём:** M (инфраструктура и тесты P1) / L (всё)

## Why

### Находка
В проекте 33 файла JVM-тестов (`app/src/test`, 379 `@Test`). Почти все проверяют логику простого интерфейса:
«Починку», режимы, импорт текста, `crown://`-ссылки, сообщения подписок. Есть ещё формат `.thrbackup`, заметки к
выпуску и контракт настроек тестов. Инструментальных тестов (`app/src/androidTest`) нет. Тестовые зависимости —
только `junit` и `org.json` (`app/build.gradle.kts:90-91`): нет Robolectric, `room-testing`, `kotlinx-coroutines-test`,
`okhttp3.mockwebserver`. CI запускает только `app:testOssDebugUnitTest` (`.github/workflows/ci.yml:26`).

Пути, где в этом PR предложены исправления высокого и критического приоритета, не покрыты ни одним тестом:

| Путь | Связанные изменения этого PR | Что есть в тестах |
|---|---|---|
| Открытие и миграции `SagerDatabase` | `stop-wiping-database-on-open-error`, `fix-room-schema-export-race` | ничего; `MigrationTestHelper` не подключён |
| Разбор недоверенного ввода (`ProfileImport`, `ClashYaml`, разбор ссылок) | `catch-deep-nesting-in-import`, `stop-yaml-alias-expansion`, `bound-untrusted-text-parsing` | только классификация текста simple (`ImportTextTest`, `CrownLinksTest`) |
| Загрузка и обновление подписки (`httpGet`, `SubscriptionRefresh`, `SubscriptionQueue`) | `add-http-call-timeouts`, `bound-downloaded-payload-sizes`, `keep-servers-when-subscription-insert-fails` | только отображение (`SubscriptionHealthTest`, `SubscriptionNoticesTest`) |
| Машина состояний `BaseService` и перезапуск на месте | `keep-tunnel-during-in-place-restart`, `restore-vpn-after-service-death` | только отображение состояния в simple (`HomeStatusTest`) |
| Восстановление копии (`BackupRestore`) | `block-restore-during-background-writes`, `fix-partial-restore-managed-ids` | только разбор контейнера (`ThrBackupTest`) |
| Самообновление | `make-update-download-resilient` | только заметки к выпуску (`ReleaseNotesTest`) |

Несколько из этих proposals уже описывают проверку исправления тестом на инструментах, которых в проекте нет:
MockWebServer (`add-http-call-timeouts`, `bound-downloaded-payload-sizes`, `make-update-download-resilient`), Robolectric
(`fix-tile-pending-tap`, `keep-servers-when-subscription-insert-fails`), `MigrationTestHelper` (`fix-room-schema-export-race`).

### Влияние
- Исправления этого PR после слияния ничем не защищены от возврата. Upstream регулярно синхронизируется («re-sync with
  desktop» в истории), а каждое слияние в `main` сразу публикуется пользователям.
- Самые тяжёлые регрессии на этих путях: стёртая БД, падение на недоверенном вводе, удалённые серверы подписки, трафик
  мимо туннеля. Юнит-тест поймал бы их дешевле всего.
- Без общей инфраструктуры каждое исправление добавляет зависимости само, и изменения конфликтуют между собой в
  `app/build.gradle.kts`.

### Как воспроизвести
Это находка о покрытии, воспроизводить нечего:
1. `find app/src/test -name '*.kt' | wc -l` — 33; `ls app/src/androidTest` — нет каталога.
2. `grep -rl "ClashYaml\|ProfileImport\|SubscriptionRefresh\|SagerDatabase\|BackupRestore\|BaseService" app/src/test` — совпадения
   только в `ThrBackupTest.kt` (состав копии), `HomeStatusTest.kt` (импорт типа состояния) и `ServiceMessagesTest.kt`
   (комментарий).
3. `grep -n testImplementation app/build.gradle.kts` — две зависимости.

## What Changes
По порядку, каждый шаг отдельно полезен:
1. **Инфраструктура (S):** добавить тестовые зависимости `okhttp3:mockwebserver` (версия в пару к OkHttp проекта),
   `kotlinx-coroutines-test`, Robolectric, `androidx.room:room-testing`. Подключить экспорт схем Room как вход тестов
   (`sourceSets.androidTest.assets` → каталог схем). Разрешить новые файлы в `app/src/androidTest/` правилом точек правки.
2. **P1, JVM (M):** регрессионные тесты разбора недоверенного ввода. Вложенность 10⁴ (`[[[…`, `{"a":{"a":…`), YAML с
   алиасами-«бомбами», строки в сотни КБ. Ожидается ошибка разбора без `StackOverflowError`/OOM и в разумное время.
   Тесты `httpGet`/`fetchText` против MockWebServer: перенаправление https→http, предел тела, «капающий» ответ, общий срок.
3. **P1, инструментальные или Robolectric (M):** `MigrationTestHelper` для 17→18→19→20 и тест «ошибка открытия не удаляет
   файл БД».
4. **P2 (L):** выделить из `SubscriptionRefresh` чистую функцию плана сверки и проверить «ошибка вставки → старые серверы
   остаются». Таблица переходов `BaseService` (состояние × действие → допустимо ли) как чистая функция с тестом.
5. **CI:** job инструментальных тестов на эмуляторе (например, `reactivecircus/android-emulator-runner`) хотя бы для
   миграций. Его можно запускать только на PR в `main` или ночью, чтобы не замедлять каждый PR. Эмулятор в CI нужен и
   smoke-тесту из `run-release-build-and-lint-in-ci`: стоит делать один общий job.

Правило на будущее: proposal, который исправляет ошибку на путях из таблицы, приносит регрессионный тест в том же PR.

Как убедиться: CI запускает тесты P1. Намеренно возвращённая ошибка (например, снятый предел вложенности или удаление БД
при ошибке открытия) роняет соответствующий тест.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `repository-governance`: добавить требование к проверкам перед выпуском. CI запускает регрессионные тесты открытия и
  миграций БД, разбора недоверенного ввода и загрузки подписки, а исправление на этих путях приходит с тестом.

## Impact
- **Код:** `app/build.gradle.kts` (тестовые зависимости, схемы Room для тестов), новые файлы в `app/src/test/` и
  `app/src/androidTest/`, `.github/workflows/ci.yml`; для п. 4 — небольшое выделение чистых функций в
  `group/SubscriptionRefresh.kt` и `bg/BaseService.kt`.
- **Upstream:** тесты в `app/src/test/` — только новые файлы, это разрешено (`NEW_ONLY_DIRS`). Для `app/src/androidTest/`
  нужно добавить каталог в `NEW_ONLY_DIRS` (`tools/diagnostics/upstream_touch_points.py`) и в таблицу D2 `design.md`.
  `app/build.gradle.kts` — существующая точка правки 2, её описание надо расширить тестовыми зависимостями. `ci.yml` —
  файл идентичности форка. Выделение функций в `SubscriptionRefresh.kt` (точка 9) и `BaseService.kt` (точка 6) выходит за
  описание этих точек, их нужно расширить или оставить п. 4 для патча upstream.
- **Ядро throne, сборка, инструменты:** сборка и CI; ядро не затрагивается.
- **Риски:** инструментальные тесты добавляют минуты к CI. Robolectric и Room-тесты могут расходиться с поведением
  реального Android 16–17, поэтому миграции лучше проверять на эмуляторе.
- **Проверка исправления:** зелёный CI с новыми тестами; намеренная регрессия роняет тест.
