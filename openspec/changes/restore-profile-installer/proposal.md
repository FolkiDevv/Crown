# Proposal

> **Категория:** скорость · **Приоритет:** средний · **Проверка:** подтверждено по коду и по содержимому release-APK ·
> **Код:** upstream · **Объём:** S

## Why

### Находка
Манифест удаляет провайдер androidx.startup целиком:

```xml
<!-- app/src/main/AndroidManifest.xml:446-449 -->
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    tools:node="remove" />
```

Цель удаления — отключить автоматическую инициализацию WorkManager: приложение настраивает его само через
`Configuration.Provider` с процессом по умолчанию (`app/src/main/java/io/nekohasekai/sagernet/SagerNet.kt:42`, `:127`).
Но вместе с провайдером пропадают все инициализаторы, в том числе `androidx.profileinstaller.ProfileInstallerInitializer`.
Именно он через несколько секунд после запуска переносит профиль из APK (`assets/dexopt/baseline.prof`) в каталог
профилей ART, чтобы система заранее скомпилировала горячий код.

- В release-APK профиль есть: `assets/dexopt/baseline.prof` (6301 байт) и `baseline.profm` (646 байт). Их собрал AGP
  из профилей библиотек (Compose, Material 3, Lifecycle, Activity).
- В объединённом манифесте release нет `InitializationProvider`. От ProfileInstaller остался только
  `ProfileInstallReceiver`, а он реагирует лишь на служебные broadcast'ы от adb и Macrobenchmark.
- Ручного вызова `ProfileInstaller.writeProfile` в коде нет (поиск по `app/src` — 0 совпадений).

Crown распространяется APK-файлами с GitHub и встроенным обновлятелем, без Play. При такой установке система сама
профиль из APK не берёт: ART Service компилирует приложение по профилю, только если вместе с APK передан DM-файл
(«cloud profile»), иначе ставит фильтр `verify`. Встроенный обновлятель передаёт в сессию установки только APK.
В итоге собранный профиль до компилятора не доходит.

### Влияние
- Экраны simple UI на Compose (открываются по умолчанию) после установки и после каждого обновления работают через
  интерпретатор и JIT, пока ART не соберёт собственный профиль по факту использования и не выполнит фоновую
  оптимизацию (в простое на зарядке). Медленнее холодный старт и первые прокрутки списка серверов, больше пропущенных
  кадров. Документация Android оценивает выигрыш baseline-профиля примерно в 30 % скорости выполнения кода с первого
  запуска; для Crown число не измерено.
- Эффект повторяется после каждого обновления, а превью выходят часто.

### Как воспроизвести
Шаги выведены из кода и документации, на устройстве не прогонялись.
1. Собрать или скачать release-APK под архитектуру устройства и установить: `adb install -r Crown-<ver>-<abi>.apk`.
2. Запустить приложение, подождать 15 с, закрыть.
3. `adb shell pm art dump dev.folki.crown` (Android 14+) или `adb shell dumpsys package dexopt`: ожидается
   `[status=verify]`, а не `speed-profile`. На эмуляторе с root `adb shell ls -l /data/misc/profiles/ref/dev.folki.crown/`
   — профиля из APK нет.
4. Для сравнения запустить установку профиля вручную:
   `adb shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE -n dev.folki.crown/androidx.profileinstaller.ProfileInstallReceiver`,
   затем `adb shell pm bg-dexopt-job`. Статус станет `speed-profile`.
5. Метрика: `adb shell am force-stop dev.folki.crown; adb shell am start -W -n dev.folki.crown/io.nekohasekai.sagernet.ui.MainActivity`
   10 раз до и после шага 4, медиана `TotalTime`; `adb shell dumpsys gfxinfo dev.folki.crown` — доля janky-кадров при
   прокрутке списка серверов.

## What Changes
- Рекомендуется: оставить провайдер androidx.startup (вместо `tools:node="remove"` на провайдере — слияние) и удалять
  поштучно только ненужные инициализаторы, например
  `<meta-data android:name="androidx.work.WorkManagerInitializer" tools:node="remove"/>`. Перед этим выписать все
  инициализаторы из объединённых манифестов debug и release и решить по каждому; ProfileInstaller оставить.
- Альтернатива без провайдера: вызывать `ProfileInstaller.writeProfile(context)` из кода Crown в фоновом потоке через
  5–10 с после первого кадра `SimpleActivity` (так требует документация при отключённом инициализаторе). Нужна явная
  зависимость `androidx.profileinstaller:profileinstaller`. Минус — профиль ставится, только если открыт simple UI.
- По желанию, отдельным изменением: собственный baseline/startup-профиль для горячего пути simple UI (модуль
  `baselineprofile` + Macrobenchmark).
- Проверка: после фоновой оптимизации шаг 3 показывает `speed-profile` без ручного broadcast'а; медиана `TotalTime`
  и доля janky-кадров в шаге 5 уменьшаются.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
(нет) — меняются манифест и сборка; обещанное приложением поведение не меняется.

## Impact
- **Код:** `app/src/main/AndroidManifest.xml` (провайдер androidx.startup); для альтернативы — `SimpleActivity` и
  `app/build.gradle.kts` (зависимость).
- **Upstream:** манифест — существующая точка правки 3 (`app/src/main/AndroidManifest.xml`), но с новым назначением:
  описание точки в `TOUCH_POINTS` (`tools/diagnostics/upstream_touch_points.py`) и в design.md D2 нужно расширить.
  Альтернатива ложится в код Crown и в точку правки 2 (зависимости в `app/build.gradle.kts`).
- **Ядро throne, сборка, инструменты:** ядро не затрагивается; меняется только описание точки 3 в инструменте проверки.
- **Риски:** провайдер выполняется во всех процессах, включая `:bg`. Если не удалить `WorkManagerInitializer`,
  WorkManager проинициализируется по умолчанию в обход `Configuration.Provider` и сломает настройку для двух процессов.
  Новые библиотеки с собственным инициализатором начнут запускаться автоматически, поэтому список надо пересматривать
  при обновлении зависимостей. Сам ProfileInstaller откладывает работу и пишет профиль один раз на версию.
- **Проверка исправления:** шаги 3–5; logcat после старта VPN — в `:bg` нет лишних инициализаций; фоновые задачи
  WorkManager по-прежнему выполняются.
- **Внешние источники:**
  - ProfileInstallerInitializer (androidx.profileinstaller 1.4): https://developer.android.com/reference/androidx/profileinstaller/ProfileInstallerInitializer
  - ProfileInstaller.writeProfile: https://developer.android.com/reference/androidx/profileinstaller/ProfileInstaller
  - Baseline Profiles overview: https://developer.android.com/topic/performance/baselineprofiles/overview
  - Manually create and measure Baseline Profiles: https://developer.android.com/topic/performance/baselineprofiles/manually-create-measure
  - ART Service README, раздел «During app installation»: https://android.googlesource.com/platform/art/+/refs/heads/main/libartservice/service/README.md
