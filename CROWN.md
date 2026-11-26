# Crown: сборка и выпуск

Crown — форк ThroneForAndroid со своим идентификатором `dev.folki.crown` и своим ключом подписи. Ставится рядом с оригинальным Throne и обновляется из релизов этого репозитория.

## Где что задано

- `fork.properties` — идентификатор, версия (`VERSION_NAME`), репозиторий обновлений, файл истории изменений, имя APK.
- `nb4a.properties` — как в upstream: ядро (`THRONE_CORE_REF`) и версия Throne, на которой основана сборка. Не редактировать.
- `CROWN_CHANGELOG.md` — заметки к релизам, разделы `#### <версия>`, новые сверху.
- `app/src/oss/res/` — название приложения (Crown / Корона) и значки Crown: значок приложения (`mipmap-anydpi-v26/ic_launcher.xml` со слоями `drawable/crown_launcher_*.xml`, для Android 7 — `mipmap-*dpi/ic_launcher.png`), значок плитки быстрых настроек и строки состояния (`drawable/ic_throne_tile.xml`), баннер Android TV (`drawable-xhdpi/tv_banner.png`). Ресурсы с именами из `src/main` переопределяют их для сборки, файлы upstream не меняются.

`versionCode` вычисляется из версии: `((MAJOR·100 + MINOR)·100 + PATCH)·1000 + N`, где N = 999 у релиза и 1..998 у preview.

## Ветки и выпуск

| Ветка | Что в ней | Как туда попадают |
|---|---|---|
| `develop` | текущая разработка, ветка по умолчанию | pull request из веток фич (`feature/…`, `t3/…` и т. п.) |
| `main` | то, что выпущено | pull request из `develop` (выпуск) или из `hotfix/…` |

- **Фича**: ветка от `develop` → pull request в `develop`. Прогоняется CI (тесты и debug-сборка), ничего не публикуется.
- **Выпуск**: pull request `develop` → `main`. После merge workflow **Release** собирает и публикует сборку. Проверка **Release plan** в этом pull request заранее пишет, что именно выйдет.
- **Hotfix**: ветка `hotfix/…` от `main` → pull request в `main` (тоже публикует сборку), затем `main` сливается обратно в `develop`.

В `main` нельзя слить pull request из другой ветки: Release plan падает и просит перенацелить его на `develop`. Если pull request перенацелили, **CI retarget** сам перезапускает Release plan, и проверка берёт новый target.

### Что публикует merge в main

Канал задаёт `fork.properties`:

```properties
VERSION_NAME=1.0.0
RELEASE=preview
```

- `RELEASE=preview` — следующий preview `v<VERSION_NAME>-pre.<N>`: N на единицу больше последнего опубликованного preview этой версии. Его получают приложения с включённым «Разрешить обновления до beta-версий». Хранятся 10 последних preview.
- `RELEASE=stable` — стабильный релиз `v<VERSION_NAME>`, его получают все. Перед ним добавьте раздел `#### <VERSION_NAME>` в `CROWN_CHANGELOG.md`, иначе в заметки попадёт список коммитов.

Обычный цикл:

1. В `develop` копятся фичи, каждый выпуск `develop` → `main` даёт `1.0.0-pre.12`, `1.0.0-pre.13`, …
2. Перед стабильной версией pull request в `develop` ставит `RELEASE=stable` и раздел changelog, затем выпуск `develop` → `main` публикует `v1.0.0`.
3. Сразу после этого pull request в `develop` поднимает `VERSION_NAME=1.0.1` и возвращает `RELEASE=preview`. Пока этого нет, Release plan в pull request'ах в `develop` предупреждает, а в `main` — падает: preview уже выпущенной версии или повтор релиза опубликовать нельзя.

Release plan также падает, если `versionCode` новой сборки не выше уже опубликованных (например, `VERSION_NAME` понизили). Workflow сам ничего не коммитит. Если публикация упала, перезапустите его или запустите **Release** вручную на `main`. Ручной запуск на другой ветке собирает APK только как artifact запуска.

### Настройки репозитория

- Ветка по умолчанию — `develop` (Settings → General), чтобы новые pull request'ы и `git clone` смотрели на неё.
- Защита веток (Settings → Branches/Rules): обе ветки только через pull request с зелёным CI («Unit tests and debug build», «Release plan») и одобрением CodeRabbit. CodeRabbit проверяет pull request'ы в обе ветки (`.coderabbit.yaml`).

## Ключ подписи

Секреты репозитория (Settings → Secrets and variables → Actions):

| Секрет | Значение |
|---|---|
| `SIGNING_KEYSTORE_B64` | keystore в base64 (`base64 -w0 crown.keystore`) |
| `SIGNING_STORE_PASSWORD` | пароль keystore |
| `SIGNING_KEY_ALIAS` | alias ключа |
| `SIGNING_KEY_PASSWORD` | пароль ключа (если пуст — берётся пароль keystore) |

Без секретов workflow Release падает. Сменить ключ после первой установки нельзя: Android не примет обновление с другой подписью, и встроенный механизм обновления тоже его отклонит. Храните keystore и пароли в резервной копии.

## Слияние upstream

Как обычная фича: ветка от `develop`, merge upstream в неё, pull request в `develop`.

```
git fetch upstream main
git switch -c upstream-sync origin/develop
git merge upstream/main
uv run tools/diagnostics/upstream_touch_points.py
```

Скрипт проверяет, что файлы upstream изменены только в разрешённых местах (`TOUCH_POINTS`, `FORK_IDENTITY_POINTS`).
