# Proposal

> **Категория:** надёжность · **Приоритет:** низкий · **Проверка:** подтверждено по коду и сетевым запросом ·
> **Код:** Crown · **Объём:** S

## Why

### Находка
Репозиторий переименован в `FolkiDevv/Crown` (так указывают `git remote` и `README.md`), но источники данных, вшитые
в сборку, по-прежнему называют старое имя:

```properties
# fork.properties:12-13
# GitHub repository whose Releases the in-app updater reads (owner/name).
UPDATE_REPOSITORY=FolkiDevv/ThroneForAndroid
```

```kotlin
// app/src/main/java/io/nekohasekai/sagernet/update/UpdateChecker.kt:29-34
const val REPOSITORY = BuildConfig.UPDATE_REPOSITORY
const val RELEASES_PAGE = "https://github.com/$REPOSITORY/releases"
private const val RELEASES_API = "https://api.github.com/repos/$REPOSITORY/releases?per_page=20"
private const val CHANGELOG_API = "https://api.github.com/repos/$REPOSITORY/contents/${BuildConfig.CHANGELOG_FILE}"
```

```kotlin
// app/src/simple/java/io/nekohasekai/sagernet/simple/config/SimpleConfigRepo.kt:27-28
const val SOURCE_URL =
    "https://raw.githubusercontent.com/FolkiDevv/ThroneForAndroid/main/app/src/simple/assets/simple/config.json"
```

Из того же имени строятся ссылка на GitHub в «О приложении» (`ui/AboutFragment.kt:83`, `:121`) и ссылка на страницу
выпусков в окне обновления (`update/UpdateActivity.kt:230`). Удалённая конфигурация simple качается через зеркало
jsDelivr (`SimpleConfigRepo.kt:58` → `route/RuleSets.kt:36-56`), которое тоже получает старое имя.

Сейчас всё работает только за счёт записи GitHub о переименовании. Проверка 2026-11-27 (`curl -sI`, без токена):
`api.github.com/repos/FolkiDevv/ThroneForAndroid/releases` отвечает `301` на `/repositories/<id>/releases`,
`github.com/FolkiDevv/ThroneForAndroid` — `301` на `FolkiDevv/Crown`; OkHttp идёт по перенаправлениям по умолчанию.
Документация GitHub предупреждает: если позже создать в аккаунте репозиторий со старым именем, перенаправления
перестанут работать.

Что тогда будет с уже установленными сборками:
- `fetchReleases` читает выпуски нового репозитория. Их нет или у них чужой `packageName`
  (`UpdateChecker.kt:108` пропускает такой манифест) — результат `UpToDate`. Встроенное обновление молча перестаёт
  находить выпуски Crown, ошибки пользователь не видит. Поставить чужую сборку нельзя: её отсеют проверки
  `packageName` и подписи.
- `SimpleConfigRepo.refresh()` получает 404 или чужой файл. Чужой валидный файл с большим `version` применится:
  проверяются формат и версия, но не источник (`SimpleConfigRepo.kt:61-79`).
- Ссылки на страницу выпусков ведут в чужой репозиторий.

Вероятность невысока: сам `FolkiDevv/Crown` — GitHub-форк upstream, поэтому повторный форк под старым именем GitHub, по
сообщениям пользователей, не создаёт. Остаётся ручное создание репозитория `ThroneForAndroid` (зеркало, проба).

### Влияние
Пока перенаправление живо, пользователь ничего не замечает. Если оно пропадёт, все установленные сборки потеряют
встроенное обновление, и сообщить им о новой версии будет нечем: обновлятель и есть этот канал. Исправить уже
установленные сборки нельзя — только новые, и только для тех, кто успел обновиться. Удалённая конфигурация simple
перестанет обновляться или будет взята из чужого репозитория.

### Как воспроизвести
1. `grep -rn ThroneForAndroid fork.properties app/src/simple` и `git remote -v` — расхождение имён.
2. `curl -sI "https://api.github.com/repos/FolkiDevv/ThroneForAndroid/releases?per_page=1"` → `301`;
   `curl -sI https://github.com/FolkiDevv/ThroneForAndroid` → `301` на новое имя (выполнено 2026-11-27).
3. Модель сбоя без изменения GitHub: собрать debug с `UPDATE_REPOSITORY=<существующий публичный репозиторий без выпусков
   Crown>` и открыть окно обновления — ответ «установлена последняя версия», ошибки нет. Шаг не прогонялся.

## What Changes
- `fork.properties`: `UPDATE_REPOSITORY=FolkiDevv/Crown`.
- `SimpleConfigRepo.SOURCE_URL` строить из `BuildConfig.UPDATE_REPOSITORY`, чтобы имя репозитория задавалось в одном месте.
- В `CROWN.md` (раздел о выпуске) записать правило: не создавать в аккаунте репозиторий `ThroneForAndroid`, пока
  сборки со старым именем могут оставаться у пользователей (фактически — никогда).
- По желанию: если `html_url` выпуска не начинается с `https://github.com/<UPDATE_REPOSITORY>/`, писать в журнал
  предупреждение — это покажет подмену источника в отчётах пользователей.
- Проверка: `curl -sI` на новые адреса отвечает `200` без перенаправления; окно обновления находит текущее превью.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `repository-governance`: требование «Идентичность форка Crown» уточняется — репозиторий обновлений и источник удалённой
  конфигурации simple совпадают с текущим именем репозитория и задаются в одном месте (`fork.properties`).

## Impact
- **Код:** `fork.properties`, `app/src/simple/java/io/nekohasekai/sagernet/simple/config/SimpleConfigRepo.kt`, `CROWN.md`.
- **Upstream:** только файлы форка (`fork.properties`, `app/src/simple/`, `CROWN.md`); `UpdateChecker.kt` и
  `AboutFragment.kt` подхватят новое имя через `BuildConfig` без правок.
- **Ядро throne, сборка, инструменты:** сборка — новое значение поля `BuildConfig.UPDATE_REPOSITORY`.
- **Риски:** почти нет: старое имя сейчас перенаправляется на тот же репозиторий. Кэш ответа выпусков с ETag
  (`UpdateChecker.kt:185-215`) после смены адреса просто получит один полный ответ `200`.
- **Проверка исправления:** шаг 2 по новым адресам; ручная проверка обновления на debug-сборке.
- **Внешние источники:**
  - GitHub Docs, Renaming a repository: https://docs.github.com/en/repositories/creating-and-managing-repositories/renaming-a-repository
  - GitHub Docs, Fork a repository (имя форка по умолчанию): https://docs.github.com/en/pull-requests/how-tos/work-with-forks/fork-a-repo
