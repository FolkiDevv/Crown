# Crown: сборка и выпуск

Crown — форк ThroneForAndroid со своим идентификатором `dev.folki.crown` и своим ключом подписи. Ставится рядом с оригинальным Throne и обновляется из релизов этого репозитория.

## Где что задано

- `fork.properties` — идентификатор, версия (`VERSION_NAME`), репозиторий обновлений, файл истории изменений, имя APK.
- `nb4a.properties` — как в upstream: ядро (`THRONE_CORE_REF`) и версия Throne, на которой основана сборка. Не редактировать.
- `CROWN_CHANGELOG.md` — заметки к релизам, разделы `#### <версия>`, новые сверху.
- `app/src/oss/res/` — название приложения (Crown / Корона) и значки Crown: значок приложения (`mipmap-anydpi-v26/ic_launcher.xml` со слоями `drawable/crown_launcher_*.xml`, для Android 7 — `mipmap-*dpi/ic_launcher.png`), значок плитки быстрых настроек и строки состояния (`drawable/ic_throne_tile.xml`), баннер Android TV (`drawable-xhdpi/tv_banner.png`). Ресурсы с именами из `src/main` переопределяют их для сборки, файлы upstream не меняются.

`versionCode` вычисляется из версии: `((MAJOR·100 + MINOR)·100 + PATCH)·1000 + N`, где N = 999 у релиза и 1..998 у preview.

## Каналы

- **Preview**: каждый push в `main` публикует pre-release `v<VERSION_NAME>-pre.<N>`. Его получают приложения с включённым «Разрешить обновления до beta-версий» (Настройки → Общие). Хранятся 10 последних preview.
- **Стабильный**: Actions → Release → Run workflow (ветка `main`). Публикует `v<VERSION_NAME>`, после чего в `main` автоматически появляется коммит, поднимающий PATCH. Сделайте `git pull`. Для минорной или мажорной версии поменяйте `VERSION_NAME` вручную.

Перед релизом добавьте раздел `#### <VERSION_NAME>` в `CROWN_CHANGELOG.md`. Без него в заметки попадёт список коммитов.

## Ключ подписи

Секреты репозитория (Settings → Secrets and variables → Actions):

| Секрет | Значение |
|---|---|
| `SIGNING_KEYSTORE_B64` | keystore в base64 (`base64 -w0 crown.keystore`) |
| `SIGNING_STORE_PASSWORD` | пароль keystore |
| `SIGNING_KEY_ALIAS` | alias ключа |
| `SIGNING_KEY_PASSWORD` | пароль ключа (если пуст — берётся пароль keystore) |

Без секретов сборки Release и Preview падают. Сменить ключ после первой установки нельзя: Android не примет обновление с другой подписью, и встроенный механизм обновления тоже его отклонит. Храните keystore и пароли в резервной копии.

## Слияние upstream

```
git fetch upstream main
git merge upstream/main
uv run tools/diagnostics/upstream_touch_points.py
```

Скрипт проверяет, что файлы upstream изменены только в разрешённых местах (`TOUCH_POINTS`, `FORK_IDENTITY_POINTS`).
