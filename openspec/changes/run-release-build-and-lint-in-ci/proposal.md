# Proposal

> **Категория:** надёжность · **Приоритет:** низкий · **Проверка:** подтверждено по коду (конфигурация CI) ·
> **Код:** Crown (workflow), настройки сборки upstream · **Объём:** S

## Why

### Находка
CI на pull request собирает только debug-вариант:

```yaml
# .github/workflows/ci.yml:22-26
- name: Build
  uses: ./.github/actions/android-build
  with:
    tasks: app:testOssDebugUnitTest app:assembleOssDebug
```

Release-вариант отличается от debug тем, что проходит через R8 и удаление неиспользуемых ресурсов
(`buildSrc/src/main/kotlin/Helpers.kt:59` `isMinifyEnabled = true`, `:97` `isShrinkResources = true`). Он собирается
только в `.github/workflows/release.yml:52-58` при push в `main`, и та же сборка в том же запуске публикуется как превью
или стабильный выпуск (`:76-111`). Встроенный обновлятель сразу предлагает её пользователям.

Lint настроен строго (`Helpers.kt:73-80`: `checkAllWarnings = true`, `warningsAsErrors = true`,
`checkReleaseBuilds = true`), но полный lint не запускает ни один workflow. Единственная работающая часть — `lintVital`,
от которого зависит `assembleRelease`: он проверяет только проблемы с серьёзностью fatal и выполняется опять-таки
в момент публикации.

Сейчас R8 защищён широкими правилами (`app/proguard-rules.pro:4-7` держит `io.nekohasekai.sagernet.**` и
`moe.matsuri.nb4a.**`, `:27` `-dontobfuscate`). Но поломки «только в release» здесь уже встречались: правило для
`OutboundFactory`, который создаёт типы профилей рефлексией (`proguard-rules.pro:5-6`), появилось отдельным исправлением.
Новая библиотека с рефлексией (Gson `TypeToken`, ServiceLoader), ресурс, на который ссылаются по имени через
`getIdentifier`, или ошибка совместимости с API проявятся впервые у пользователей.

### Влияние
Падение «только в release» (ClassNotFoundException или NoSuchMethodError после R8, пропавший ресурс) или ошибка,
которую поймал бы lint (`NewApi`, `UnspecifiedRegisterReceiverFlag`, `MutableImplicitPendingIntent`), уходит в
опубликованный выпуск без предварительной проверки. Если fatal-проблема lint найдётся в `lintVital`, выпуск упадёт
уже после слияния в `main`, и его придётся чинить hotfix'ом. Откат опубликованной сборки — только новым выпуском.
Вероятность низкая, но цена — сломанное приложение у всех, кто обновился.

### Как воспроизвести
Шаги выведены из кода, сборки не запускались.
1. Открыть `ci.yml` и `release.yml`: на pull request нет задач `assembleOssRelease` и `lint*`.
2. Тест механизма: в ветке удалить keep-правило `-keep class io.nekohasekai.sagernet.outbound.types.** { *; }` и сузить
   правило `io.nekohasekai.sagernet.**`, открыть PR — CI зелёный; `./gradlew app:assembleOssRelease`, установить APK и
   открыть список профилей — падение при создании типа профиля.
3. Локально `./gradlew app:lintOssDebug`: посмотреть, сколько предупреждений накопилось (при `warningsAsErrors` задача,
   скорее всего, упадёт сразу — это и есть исходная точка для baseline-файла).

## What Changes
- В `ci.yml` добавить job `app:assembleOssRelease` без подписи (подпись для проверки R8 не нужна; без секретов release
  собирается неподписанным). Рекомендуется запускать его на PR в `main` (релизные PR из `develop` и `hotfix/*`): это
  ровно то, что затем опубликует `release.yml`, а обычные PR в `develop` не замедляются. По желанию — и на push в
  `develop`, чтобы узнавать о поломке до релизного PR.
- Добавить `app:lintOssDebug` в CI с baseline-файлом lint (`lint { baseline = file("lint-baseline.xml") }`), чтобы
  зафиксировать текущие предупреждения и ловить только новые.
- По желанию: установить release-APK на эмулятор в CI и проверить запуск simple UI (smoke-тест).
- Проверка: PR в `main` с вызовом API выше minSdk без проверки версии падает на lint; ошибка сборки R8 (например,
  отсутствующий класс) — на release-job. Удалённое keep-правило сборку не ломает — отказ проявляется при запуске, на
  экране, который обращается к классу через рефлексию. Его ловит только smoke-тест, открывающий этот экран (список
  профилей); пока такого теста нет, эта поломка в CI не проверяется.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `repository-governance`: требование «Разработка в develop, выпуск merge в main» дополняется — релизный PR в `main`
  проходит release-сборку (R8, удаление ресурсов) и lint до слияния.

## Impact
- **Код:** `.github/workflows/ci.yml`; для baseline lint — `buildSrc/src/main/kotlin/Helpers.kt` (блок `lint`) и новый
  `app/lint-baseline.xml`.
- **Upstream:** `ci.yml` — существующая точка идентичности форка (`FORK_IDENTITY_POINTS`). `Helpers.kt` — тоже точка
  идентичности, но её описание (пакет, версия, имя APK) придётся расширить: baseline lint задаётся только в DSL блока
  `lint`, который находится в этом файле.
- **Ядро throne, сборка, инструменты:** ядро берётся из кэша CI; сборка APK не меняется.
- **Риски:** время CI на релизном PR растёт на одну R8-сборку (несколько минут). Первый запуск полного lint почти
  наверняка даст много срабатываний — без baseline job будет красным.
- **Проверка исправления:** PR из шага 2 падает в CI; PR без изменений — зелёный.
- **Внешние источники:**
  - Improve your code with lint checks (lintVital и fatal-проблемы в release-сборках): https://developer.android.com/studio/write/lint
  - LintOptions / Lint DSL (`checkReleaseBuilds`, `baseline`): https://developer.android.com/reference/tools/gradle-api/com/android/build/api/dsl/Lint
