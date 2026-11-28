# Proposal

> **Категория:** стабильность, надёжность · **Приоритет:** критический · **Проверка:** воспроизведено на эмуляторе ·
> **Код:** upstream · **Объём:** S (завершение процесса) + S–M (отчёт о падении без запуска activity)

## Why

### Находка
Первой строкой `Application.onCreate` во **всех** процессах приложения (основной, `:bg`, `:phoenix`) системный обработчик
непойманных исключений заменяется собственным, а прежний не сохраняется:

```kotlin
// app/src/main/java/io/nekohasekai/sagernet/SagerNet.kt:57
Thread.setDefaultUncaughtExceptionHandler(CrashHandler)
```

Обработчик пишет журнал и сам процесс не завершает (кроме уже идущего отчёта), а поручает это ProcessPhoenix:

```kotlin
// app/src/main/java/io/nekohasekai/sagernet/utils/CrashHandler.kt:33-39
if (reporting) {
    Process.killProcess(Process.myPid())
    exitProcess(10)
}
ProcessPhoenix.triggerRebirth(app, Intent(app, BlankActivity::class.java).apply {
    putExtra(BlankActivity.EXTRA_SEND_LOG, true)
})
```

`ProcessPhoenix.triggerRebirth` (process-phoenix 2.1.2, `app/build.gradle.kts:88`) только вызывает `startActivity` для своей
activity в процессе `:phoenix`; упавший процесс убивает уже она. Запуск activity из фона Android 10+ блокирует, и наличие
foreground-сервиса исключением не является. VPN-процесс `:bg` почти всегда работает без видимого окна, поэтому система
отклоняет запуск (молча для вызывающего: `startActivity` не бросает), обработчик возвращается — процесс **продолжает жить**.

Последствия зависят от потока:
- **Главный поток `:bg`** (ресиверы, `onStartCommand`, колбэки сервиса и `TileService`, корутины на `Dispatchers.Main`):
  Looper завершён, но процесс с ядром, открытым TUN и foreground-уведомлением жив. Всё управление сервисом идёт через
  главный поток — остановка (`bg/BaseService.kt:423` `runOnMainDispatcher { … }`), приёмник `RELOAD`/`CLOSE`
  (`BaseService.kt:84-85`), `onStartCommand`, привязка плитки, — поэтому VPN перестаёт управляться.
- **Фоновые потоки** (`runOnDefaultDispatcher`/`runOnIoDispatcher` — это `GlobalScope.launch` без
  `CoroutineExceptionHandler`, `ktx/Asyncs.kt:9-19`): исключение доходит до того же обработчика, он возвращается, операция
  остаётся недоделанной, пользователь ничего не узнаёт.
- **Основной процесс в фоне**: главный поток мёртв, при возврате в приложение — застывший кадр.
- **Приложение на экране**: путь работает как задуман, но `:bg` убит, задача пользователя очищается (`BlankActivity` с
  `NEW_TASK|CLEAR_TASK`), поверх открывается лист «Поделиться»; VPN сам не поднимается (см. `restore-vpn-after-service-death`).

### Влияние
- После любого непойманного исключения на главном потоке `:bg` в фоне VPN становится неуправляемым: «Отключить» в
  приложении, «Стоп» в уведомлении и плитка ничего не делают; через 20 с после нажатия плитки — системный ANR-диалог.
  Выход — только «Остановить» в системных настройках приложения. Настройки и смена сервера до сервиса не доходят.
- Падение UI-процесса в фоне — зависший экран при следующем открытии до принудительной остановки.
- Нарушается `proxy-connection`, «External control actions» (сценарий «tapping again stops it») и «Connection state
  machine» (остановка не доходит до Stopped).

### Как воспроизвести
Проверено на эмуляторе Android 17, debug (`dev.folki.crown.debug`) и release (`dev.folki.crown`, R8) сборки.
1. Добавить подписку, подключить VPN (`adb shell dumpsys connectivity` → `VPN CONNECTED`). Нажать Home.
2. `adb shell am crash $(adb shell pidof dev.folki.crown.debug:bg)` — исключение на главном потоке `:bg`.
3. В logcat сразу после `E AndroidRuntime: Process: dev.folki.crown.debug:bg`:
   ```
   E ActivityTaskManager: Background activity launch blocked! … callingUidHasVisibleActivity: false; …
     callingUidProcState: FOREGROUND_SERVICE; … cmp=dev.folki.crown.debug/com.jakewharton.processphoenix.ProcessPhoenix
   ```
4. `pidof` — процесс `:bg` жив, `dumpsys connectivity` — VPN по-прежнему CONNECTED.
5. Открыть приложение → «Отключить»; в шторке → «Стоп»; нажать плитку. VPN остаётся (наблюдали 30+ с; на release —
   10 с, 8 с, 8 с соответственно). Через 20 с после плитки:
   ```
   I am_anr  : [0,<pid>,dev.folki.crown.debug:bg,…,executing service …/io.nekohasekai.sagernet.bg.TileService, waited 20001ms]
   E ActivityManager: ANR in dev.folki.crown.debug:bg
   ```
6. UI-процесс: VPN подключён, Home, `am crash $(pidof dev.folki.crown.debug)`, открыть приложение — застывший кадр,
   `dumpsys window` → `mCurrentFocus=null`, касания не обрабатываются 30+ с.
7. Сброс: `adb shell am force-stop dev.folki.crown.debug`.

Не проверено: цикл перерождений при падении в `Application.onCreate` основного процесса (цель перерождения — та же
`BlankActivity` в том же процессе, а `CrashHandler.reporting` выставляется только в её `onCreate`). Нужен опыт с
искусственным исключением в ветке `isMainProcess`.

## What Changes
1. **(Рекомендуется, обязательно)** `CrashHandler` после записи журнала **всегда завершает процесс**: сохранить системный
   обработчик (`Thread.getDefaultUncaughtExceptionHandler()` до замены) и передать ему исключение. Системный обработчик
   сообщает о падении в ActivityManager (учёт падений, `ApplicationExitInfo`, защита от цикла частых падений) и убивает
   процесс. Явный `Process.killProcess` + `exitProcess` — запасной вариант, но он обходит учёт падений системой.
2. **Отчёт без запуска activity из упавшего процесса.** Записать маркер «последнее падение: процесс, время, класс
   исключения» в `filesDir` (синхронно, до передачи системному обработчику) и показывать отчёт:
   - для `:bg` — уведомлением «VPN остановился из-за ошибки — отправить журнал» с `PendingIntent` на `BlankActivity`
     (открытие activity по нажатию на уведомление из фона разрешено); уведомление публикует новый процесс `:bg` или UI
     при следующем старте по маркеру;
   - для основного процесса — при следующем открытии UI (simple и classic) по маркеру.
   ProcessPhoenix для отчёта больше не нужен; задача пользователя не очищается.
3. Защита от цикла: если маркер падения основного процесса повторяется в течение N секунд, отчёт не открывать сразу.
4. Восстановление VPN после завершения `:bg` — отдельный change `restore-vpn-after-service-death`; без него п.1 превращает
   «зомби» в чистое выключение VPN (лучше, чем неуправляемый VPN, но трафик пойдёт мимо туннеля).

Проверка «до/после»: шаги 2–6. Ожидается: `am_proc_died` для `:bg`, ANR нет, UI/плитка/виджет показывают «выключено»
(или VPN поднят заново вместе с вторым change), появляется уведомление об ошибке; повторное открытие UI после падения
UI-процесса работает.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `diagnostics-logging`: требование «Crash and error reporting path» уточняется — непойманное исключение в любом процессе
  завершает этот процесс; отчёт пользователю предлагается уведомлением или при следующем открытии UI, а не запуском activity
  из упавшего процесса.
- `proxy-connection`: «Connection state machine» — после сбоя сервиса UI, уведомление, плитка и виджеты не показывают
  состояние «подключено» у неуправляемого процесса.

## Impact
- **Код:** `utils/CrashHandler.kt`, `SagerNet.kt:57` (сохранение прежнего обработчика), `ui/BlankActivity.kt`, новое
  уведомление (канал уже есть в `updateNotificationChannels`); simple UI — показ маркера при открытии.
- **Upstream:** `CrashHandler.kt` и `BlankActivity.kt` вне списков точек правки — нужна новая точка правки (или патч в
  upstream, проблема общая для upstream). `SagerNet.kt` — уже точка идентичности форка, но правка обработчика к ней по смыслу
  не относится: её тоже стоит внести в таблицу точек правки `design.md` D2 и `TOUCH_POINTS`.
- **Ядро throne, сборка:** не затрагиваются; зависимость process-phoenix можно удалить.
- **Риски:** сейчас исключение в фоновой корутине `:bg` в фоне «переживается» (процесс живёт дальше); после п.1 оно
  завершит процесс и разорвёт VPN. Это правильнее (состояние после исключения не гарантировано), но частота таких падений
  неизвестна — стоит выпускать вместе с восстановлением VPN и учётом причин завершения (`ApplicationExitInfo`). Системный
  обработчик может показать диалог «Приложение остановлено», если у процесса есть видимое окно.
- **Проверка исправления:** сценарии выше на debug и release; JVM-тест, что обработчик вызывает сохранённый прежний
  обработчик и пишет маркер.
- **Внешние источники:**
  - Ограничения запуска activity из фона (Android 10+; foreground-сервис не в списке исключений; разрешён запуск из
    `PendingIntent`, отправленного системой, например нажатия на уведомление):
    https://developer.android.com/guide/components/activities/background-starts
  - `Thread.setDefaultUncaughtExceptionHandler`: https://developer.android.com/reference/java/lang/Thread#setDefaultUncaughtExceptionHandler(java.lang.Thread.UncaughtExceptionHandler)
