# Proposal

> **Категория:** надёжность · **Приоритет:** низкий · **Проверка:** подтверждено по коду и по dex опубликованного выпуска ·
> **Код:** оба (workflow Crown, правила R8 upstream) · **Объём:** S

## Why

### Находка
Release-сборка проходит через R8 (`buildSrc/src/main/kotlin/Helpers.kt:59` `isMinifyEnabled = true`, `:172-174`
`proguard-android-optimize.txt` + `app/proguard-rules.pro`). Имена не переименовываются (`app/proguard-rules.pro:27`
`-dontobfuscate`), но номера строк R8 переписывает. В dex опубликованного выпуска у `CrashHandler.uncaughtException`
таблица строк выглядит так:

```
name          : 'uncaughtException'
positions     :
  0x0000 line=1
  0x0001 line=2
  0x0002 line=3
```

вместо реальных строк исходника. Атрибут `LineNumberTable` при этом сохранён: AGP 8.8.1 (`buildSrc/build.gradle.kts:10`)
с версии 8.6 сохраняет его в стандартном `proguard-android-optimize.txt`. Соответствие «новая строка → исходная строка»,
а также кадры методов, встроенных R8 (inline), записаны только в `app/build/outputs/mapping/ossRelease/mapping.txt`.

Этот файл нигде не сохраняется. Workflow выпуска собирает `app:assembleOssRelease` (`.github/workflows/release.yml:52-58`),
`buildScript/release_assets.py` складывает в `dist/` только APK, контрольные суммы и манифест обновления (`:59-63`),
в релиз уходит `gh release create "$TAG" dist/*` (`:99`, `:111`), в артефакт — `path: dist/` (`:64-71`). Карта
пропадает вместе с раннером.

### Влияние
Журнал падения, который пользователь присылает из экрана сбоя, содержит имена методов, но бессмысленные номера строк
и не содержит встроенных кадров. Для длинных методов (`BaseService`, `SubscriptionQueue`, Compose-функции simple UI)
место падения по такому стеку часто не найти. Восстановить карту задним числом нельзя: её надо сохранить при сборке
того же выпуска, а превью выходят по нескольку в день.

### Как воспроизвести
Шаги выведены из кода; шаг 1 выполнен на опубликованном выпуске.
1. Скачать APK любого выпуска, распаковать `classes*.dex`, выполнить `dexdump -d classes.dex` и найти класс
   `Lio/nekohasekai/sagernet/utils/CrashHandler;`, метод `uncaughtException`, блок `positions`: строки 1, 2, 3…
2. `gh release view <tag> --json assets --jq '.assets[].name'` — `mapping.txt` среди ассетов нет; у запуска workflow
   (`gh run view <id>`) артефактов с картой тоже нет.
3. Вызвать падение на этом выпуске и открыть журнал сбоя: номера строк в кадрах `io.nekohasekai.sagernet.*` не
   совпадают с исходником тега.

## What Changes
- В `release.yml` после шага сборки сохранять `app/build/outputs/mapping/ossRelease/mapping.txt` (сжатым) как ассет
  релиза, например `Crown-<ver>-mapping.txt.gz`. Рекомендуется именно ассет: он живёт столько же, сколько выпуск, и
  находится по тегу. Карта не содержит секретов: только имена классов и методов, которые и так не обфусцированы.
  Если ассет в списке выпуска нежелателен — артефакт workflow с максимальным сроком хранения (но тогда карты превью
  исчезнут через срок хранения, а старые превью и так удаляются шагом «Prune old previews»).
- Для ручных запусков не на `main` класть карту в тот же артефакт, что и APK.
- В `CROWN.md` описать, как расшифровать присланный стек: `retrace mapping.txt stacktrace.txt` (утилита из Android SDK
  cmdline-tools или R8).
- Правило `-keepattributes LineNumberTable` не добавлять: атрибут уже сохранён, номера строк R8 переписывает и с ним.
- Проверка: у нового выпуска есть ассет с картой; `retrace` по стеку из шага 3 даёт строки, совпадающие с исходником тега.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `diagnostics-logging`: требование «Crash and error reporting path» уточняется — у каждого опубликованного выпуска
  сохраняется карта R8, по которой стек из отчёта о сбое переводится в строки исходника.
- `repository-governance`: требование «Разработка в develop, выпуск merge в main» — состав ассетов выпуска пополняется картой.

## Impact
- **Код:** `.github/workflows/release.yml`, при желании `buildScript/release_assets.py` (если карту класть в `dist/`),
  `CROWN.md`.
- **Upstream:** `release.yml` и `release_assets.py` — существующие точки идентичности форка (`FORK_IDENTITY_POINTS`);
  `CROWN.md` — файл форка. Upstream-код и `proguard-rules.pro` не меняются.
- **Ядро throne, сборка, инструменты:** только workflow выпуска; сборка APK не меняется.
- **Риски:** лишний ассет в списке выпуска (сжатый файл — порядка единиц МБ). Встроенному обновлятелю он не мешает:
  APK выбирается по точному имени и размеру из `throne-update.json` (`app/src/main/java/io/nekohasekai/sagernet/update/UpdateChecker.kt:143-144`).
- **Проверка исправления:** выпуск превью на тестовой ветке через `workflow_dispatch`, наличие карты в артефакте,
  `retrace` по стеку с известной строкой.
- **Внешние источники:**
  - Add global options (атрибуты, сохраняемые AGP по умолчанию; LineNumberTable — с AGP 8.6): https://developer.android.com/topic/performance/app-optimization/global-options
  - R8, Retrace and map file versioning: https://r8.googlesource.com/r8/+/main/doc/retrace.md
  - Decode an obfuscated stack trace: https://developer.android.com/studio/build/shrink-code#decode-stack-trace
