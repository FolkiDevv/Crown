# Proposal

> **Категория:** надёжность, стабильность · **Приоритет:** средний · **Проверка:** воспроизведено на эмуляторе ·
> **Код:** upstream · **Объём:** S

## Why

### Находка
Оба сервиса подключения объявлены с типом foreground-сервиса `systemExempted`:

```xml
<!-- app/src/main/AndroidManifest.xml:391-396 -->
<service
    android:name="io.nekohasekai.sagernet.bg.ProxyService"
    android:exported="false"
    android:foregroundServiceType="systemExempted"
    android:process=":bg"
    tools:ignore="ForegroundServicePermission" />
```

На API 34+ уведомление сервиса поднимается так, а отказ превращается в тост:

```kotlin
// app/src/main/java/io/nekohasekai/sagernet/bg/ServiceNotification.kt:278-292
if (Build.VERSION.SDK_INT >= 34) {
    (service as Service).startForeground(notificationId, it.build(), FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
} ...
} catch (e: Exception) {
    Toast.makeText(SagerNet.application, "startForeground: $e", Toast.LENGTH_LONG).show()
}
```

По документации Android тип `systemExempted` разрешён VPN-приложениям (то есть получившим согласие на VPN),
владельцам устройства, обладателям `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM` и нескольким системным ролям.
Последних разрешений в манифесте нет. Для `VpnService` это верно: он работает только после согласия. А режиму
прокси (`ProxyService`) согласие не нужно и не запрашивается: `VpnRequestActivity.StartService` в `MODE_PROXY`
сразу вызывает `SagerNet.startService()` (`ui/VpnRequestActivity.kt:73-78`). Больше того, на устройствах без окна
согласия (ТВ, AOSP-сборки) приложение само предлагает перейти в режим прокси (`VpnRequestActivity.kt:99-108`,
кнопка `vpn_dialog_use_proxy`). Именно там, где согласия нет, `ProxyService` не может стать foreground.

Получив отказ, сервис продолжает работать как обычный фоновый. Пока интерфейс открыт, это незаметно. Когда
пользователь уходит из приложения, процесс `:bg` становится кэшированным, и система его замораживает: локальный
прокси перестаёт отвечать, а приложение считает себя подключённым.

### Влияние
- Режим «только прокси» на Android 14+ без согласия на VPN работает около двух минут после ухода из приложения,
  потом молча перестаёт. На экране при каждом старте — непонятный тост `startForeground: …`.
- Самая затронутая аудитория — ТВ и AOSP-устройства без VpnDialogs: для них режим прокси — единственный путь, и
  приложение само его предлагает.
- Нарушается `openspec/specs/proxy-connection` «VPN and proxy modes»: сервис ДОЛЖЕН поддерживать режим «только
  прокси».
- Исходное предположение «`:bg` упадёт по тайм-ауту `ForegroundServiceDidNotStartInTimeException`» на Android 17 не
  подтвердилось: падения нет, есть заморозка.

### Как воспроизвести
Debug-сборка `dev.folki.crown.debug`, эмулятор Android 17 (API 37). На хосте — sing-box с socks-входом; в
приложении — профиль `socks5://10.0.2.2:<порт>`.

1. `adb shell appops set dev.folki.crown.debug ACTIVATE_VPN default` (согласия на VPN нет).
2. Настройки → «Расширенный режим» → «Все классические настройки» → General → Service mode → «Proxy only».
3. На главном экране — «Подключить». В журнале событий:
   ```
   I am_foreground_service_denied: [0,dev.folki.crown.debug/io.nekohasekai.sagernet.bg.ProxyService,1,PROC_STATE_TOP,...]
   ```
   На экране — тост `startForeground: …`, главный экран — «Protected». Запрос через локальный порт работает:
   `printf 'GET http://connectivitycheck.gstatic.com/generate_204 HTTP/1.1\r\nHost: …\r\n\r\n' | toybox nc 127.0.0.1 2080`
   → `HTTP/1.1 204 No Content`.
4. Нажать Home и повторять запрос:
   ```
   ~20 с:  isFrozen=false  → 204
   ~60 с:  isFrozen=false  → 204
   ~120 с: isFrozen=true   → нет ответа
   dumpsys activity processes: ProcessRecord{… dev.folki.crown.debug:bg} curProcState=15 … isFrozen=true
   ```
5. Контроль: `adb shell appops set dev.folki.crown.debug ACTIVATE_VPN allow`, повторить шаги 3–4. Теперь
   `am_foreground_service_start` для `ProxyService`, и через 60, 120 и 180 с в фоне процесс не заморожен, запросы
   получают 204.

## What Changes
1. **(Рекомендуется.)** Объявить для `ProxyService` тип, не требующий согласия на VPN: `specialUse` со свойством
   `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` (например, «local proxy») и разрешением
   `FOREGROUND_SERVICE_SPECIAL_USE`. У него нет суточного лимита, в отличие от `dataSync` (6 часов в сутки на
   Android 15+), поэтому `dataSync` не подходит.
2. Передавать тип в `startForeground` по классу сервиса: `VpnService` — `systemExempted`, `ProxyService` —
   `specialUse`. Сейчас тип один на оба (`ServiceNotification.kt:281`).
3. Отказ `startForeground` не глушить тостом, а завершать старт понятной ошибкой через `failRunner` («Система не
   разрешила работу в фоне: …»). Тогда пользователь не увидит ложного «Protected».
4. Проверка «до/после»: шаги 1–4 без согласия на VPN дают `am_foreground_service_start`, без тоста; через 3 минуты
   в фоне процесс не заморожен, прокси отвечает.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `proxy-connection`: в «VPN and proxy modes» добавить сценарий: режим «только прокси» работает в фоне без согласия
  на VPN; если система отказала сервису в работе на переднем плане, старт завершается ошибкой, а не мнимым
  подключением.

## Impact
- **Код:** `app/src/main/AndroidManifest.xml` (тип `ProxyService`, разрешение), `bg/ServiceNotification.kt`
  (`show()`).
- **Upstream:** манифест и `ServiceNotification.kt` — upstream; правок форка в этих местах нет
  (`tools/diagnostics/upstream_touch_points.py`) — нужна новая точка правки или патч в upstream.
- **Ядро throne, сборка, инструменты:** не затрагиваются.
- **Риски:** при публикации в Google Play `specialUse` требует обоснования в консоли; вне Play это не важно. Если
  после согласия на VPN `ProxyService` по-прежнему будет `specialUse`, поведение для пользователей с согласием не
  меняется (тип разрешён всем).
- **Проверка исправления:** сценарий выше; ручная проверка на API 33 (типы FGS там не применяются) и API 34+.
- **Внешние источники:** «Foreground service types»
  (https://developer.android.com/develop/background-work/services/fgs/service-types — разделы systemExempted,
  specialUse, dataSync), «Cached app freezer» (https://source.android.com/docs/core/perf/cached-apps-freezer).
