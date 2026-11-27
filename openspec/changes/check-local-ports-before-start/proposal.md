# Proposal

> **Категория:** надёжность · **Приоритет:** низкий · **Проверка:** воспроизведено на эмуляторе ·
> **Код:** upstream · **Объём:** S

## Why

### Находка
Основная конфигурация ядра всегда слушает на loopback два порта с постоянными номерами:

```kotlin
// app/src/main/java/io/nekohasekai/sagernet/outbound/config/ConfigGenerator.kt:658-664
inbounds.add(jsonObjectOf("tag" to Tags.DNS_IN, "type" to "direct", "listen" to "127.0.0.1", "listen_port" to settings.dnsInPort))
if (settings.mixedInboundEnabled) {
    val mixed = jsonObjectOf(
        "tag" to Tags.MIXED_IN, "type" to "mixed",
        "listen" to settings.mixedListen, "listen_port" to settings.mixedPort,
```

Значения по умолчанию: `inbound_socks_port` = 2080 плюс индекс пользователя Android
(`database/SettingsRegistry.kt:160`, `database/DataStore.kt:153-157`), `core_dns_in_port` = 5533
(`SettingsRegistry.kt:362`). Mixed-вход включён и в режиме VPN (`disable_mixed_inbound` = false,
`SettingsRegistry.kt:162`).

Перед стартом занятость проверяется только у порта дашборда, и при конфликте он переезжает:

```kotlin
// app/src/main/java/io/nekohasekai/sagernet/bg/proto/BoxInstance.kt:55-59
if (DataStore.apiDashboardEnabled && !isLoopbackPortFree(DataStore.coreBoxApiPort)) {
    val port = mkPort()
    Logs.w("sing-box API port ${DataStore.coreBoxApiPort} is in use, the dashboard moves to $port")
    DataStore.coreBoxApiPort = port
}
```

Для mixed-входа и dns-входа такой проверки нет: `isLoopbackPortFree` (`ktx/Nets.kt:346`) вызывается только здесь.
Если порт занят, ядро падает на bind, а `BaseService` показывает общий текст с сырой Go-ошибкой
(`bg/BaseService.kt:709-721`, `failRunner("${getString(R.string.service_failed)} ${exc.readableMessage}")`).

### Влияние
- Пока порт 2080 (или 5533) держит процесс, который **не** является VPN-клиентом, Crown не подключается вообще:
  при каждом нажатии «Подключить» старт падает. Такой процесс — другой клиент этой линии (NekoBox, Throne, вторая
  сборка Crown) в режиме «только прокси», локальный прокси на том же порту, инструменты разработчика.
- Сообщение не говорит, что делать: в classic — сырая ошибка bind, в simple — баннер «Соединение потеряно — VPN
  неожиданно отключился» (это отдельная проблема, см. `explain-start-failures-in-simple-ui`).
- Частый сценарий «переход с другого VPN-клиента, пока тот подключён» на эмуляторе **не** ломается: система
  отзывает чужой VPN уже на `VpnService.prepare`, чужое ядро освобождает порт примерно за 1,5–2 с до того, как наше
  ядро делает bind. Поэтому приоритет низкий.

### Как воспроизвести
Debug-сборка `dev.folki.crown.debug`, эмулятор Android 17 (API 37), режим VPN, согласие на VPN дано, есть рабочий
профиль.

1. Занять порт процессом, который не является VPN:
   `adb shell "nohup toybox nc -L -s 127.0.0.1 -p 2080 cat >/dev/null 2>&1 &"`
   (в `/proc/net/tcp` появляется `0100007F:0820 … 0A`).
2. В приложении нажать «Подключить».
3. Журнал ядра (`files/core.log`):
   ```
   [BoxInstance] box start failed for profile 1: start service: start inbound:
     start inbound/mixed[mixed-in]: listen tcp 127.0.0.1:2080: bind: address already in use
   ```
   Сервис стал foreground и через 2,2 с остановился; TUN не открывался. Simple показывает «Connection lost — The
   VPN turned off unexpectedly».
4. Контроль, другой VPN-клиент: подключить release-сборку `dev.folki.crown` (она слушает 127.0.0.1:2080), затем
   «Подключить» в debug:
   ```
   Vpn: Switched from dev.folki.crown to dev.folki.crown.debug
   am_foreground_service_stop: [0,dev.folki.crown/…VpnService,…]
   Vpn: Established by dev.folki.crown.debug on tun0
   ```
   Подключение проходит.

## What Changes
1. **(Рекомендуется.)** В `BoxInstance.buildConfig` проверять dns-вход так же, как дашборд: если
   `!isLoopbackPortFree(dnsInPort)`, взять свободный порт и записать предупреждение в лог. Этот порт нужен только
   самому ядру, переезд безопасен.
2. Для mixed-входа: в режиме VPN, если порт занят, тоже переезжать на свободный порт. Собственные запросы
   приложения читают сохранённый `inboundSocksPort`, так что переезд для них прозрачен. В режиме «только прокси»
   внешние приложения могут ждать именно этот порт, поэтому там не переезжать, а сразу завершать старт понятной
   ошибкой: «Порт 2080 занят другим приложением. Остановите его или смените порт в настройках».
3. Дешевле: только распознать в `BaseService` ошибку `address already in use` и показать текст с номером порта и
   подсказкой. Отказ при этом останется.
4. Проверка «до/после»: шаги 1–3 в режиме VPN дают подключение и строку в логе о переезде порта; в режиме прокси —
   понятное сообщение.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `proxy-connection`: в «VPN and proxy modes» добавить сценарий: занятый локальный порт входа в режиме VPN не
  мешает подключению; в режиме прокси пользователь получает сообщение с номером порта.

## Impact
- **Код:** `bg/proto/BoxInstance.kt` (`buildConfig`), при варианте 3 — `bg/BaseService.kt` (обработка ошибки старта).
- **Upstream:** `BoxInstance.kt` нет в `tools/diagnostics/upstream_touch_points.py` — нужна новая точка правки или
  патч в upstream. В `BaseService.kt` есть точка правки 6 (вызовы `SimpleHooks`), но эта правка в неё не ложится.
- **Ядро throne, сборка, инструменты:** не затрагиваются.
- **Риски:** между проверкой и стартом ядра порт могут занять — тогда остаётся нынешнее поведение. Тихий переезд
  mixed-порта в режиме VPN заметит пользователь, который вручную прописал 127.0.0.1:2080 в другом приложении при
  включённом «доступе из LAN»; поэтому переезд нужно писать в лог и, по возможности, показывать в настройках.
- **Проверка исправления:** сценарий выше в режимах VPN и прокси; unit-тест на выбор порта при занятом значении.
