# Proposal

> **Категория:** надёжность · **Приоритет:** высокий · **Проверка:** воспроизведено на эмуляторе · **Код:** upstream
> (+ Crown для уведомления) · **Объём:** M

## Why

### Находка
VPN-процесс `:bg` может исчезнуть, пока VPN работает: падение Go-ядра, нехватка памяти, OEM-«оптимизатор», непойманное
исключение (см. `recover-from-background-process-crash`). Система при этом снимает VPN-интерфейс, и без «Постоянного VPN
с блокировкой» трафик сразу идёт напрямую. Приложение ничем не обеспечивает восстановление:

```kotlin
// app/src/main/java/io/nekohasekai/sagernet/bg/BaseService.kt:602-608
fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    DataStore.baseService = this
    if (data.state != State.Stopped) return Service.START_NOT_STICKY
    startProxy()
    return Service.START_NOT_STICKY
}
```

`VpnService.kt:110` и `ProxyService` тоже возвращают `START_NOT_STICKY`; `START_STICKY`/`START_REDELIVER_INTENT` в
`app/src` не встречаются. Постоянного признака «пользователь хочет, чтобы VPN работал» нет: `DataStore.serviceState` —
поле в памяти (`database/DataStore.kt:26`), `BootReceiver`/`PackageReplacedReceiver` смотрят только `rememberEnable` и
`resumeAfterUpdate` при загрузке и обновлении (`BootReceiver.kt:23-51`).

Что происходит после гибели `:bg`:
- UI (если его процесс жив) переподключается с `BIND_AUTO_CREATE` (`HomeViewModel.kt:434-439`, `MainActivity.kt:556-559`)
  — система поднимает новый `:bg` «for bound-service», сервис создаётся в состоянии Stopped, ядро не стартует;
- новый `:bg` в `SagerNet.onCreate` только перерисовывает виджеты (`SagerNet.kt:64-65`);
- единственный признак потери — баннер «Соединение потеряно» в памяти `HomeViewModel` (`HomeViewModel.kt:441-443`), если
  экран открыт; уведомление сервиса исчезает вместе с процессом.

Ближе всего к нужному флагу — `SimpleHooks.KEY_CONNECTED_SINCE` (пишется при Connected, стирается при штатной остановке,
`simple/SimpleHooks.kt:40,52`). Но `HomeViewModel.onServiceConnected` сам стирает его, когда видит не запущенный сервис
(`HomeViewModel.kt:416-421`: «A start left over from a service process that died without stopping»), то есть UI уничтожает
признак ровно в тот момент, когда он нужен для восстановления.

Иногда VPN всё же возвращается — когда платформа сама перезапускает сервис как started-service; приложение на это не влияет.

### Влияние
После гибели `:bg` трафик всего устройства идёт мимо туннеля, пока пользователь сам не заметит пропажу значка VPN; ночью или
в фоне это могут быть часы. Нарушается ожидание из `proxy-connection`, «Connection state machine»: сбой должен давать
видимое пользователю сообщение, а его нет, если UI не открыт.

Замеры (эмулятор Android 17, debug-сборка коммита `0a0fcc4e`, `kill -9` процесса `:bg` при подключённом VPN), две независимые
серии — 12 попыток, VPN не вернулся в 5:

| Условие | Что сделала система | VPN |
|---|---|---|
| первый старт в свежем `:bg`, 4 попытки; плиткой выкл→вкл; UI в фоне | `Start proc … :bg for started-service {…VpnService}` | вернулся через 5–9 с |
| `:bg` с предыдущими stop/start кнопкой в приложении (3 попытки); UI на экране (2 попытки) | `Scheduling restart of crashed service …VpnService in 10999ms for connection`, `Start proc … for bound-service` | не вернулся (наблюдали 30–35 с) |

При отказе: `dumpsys connectivity` — сети VPN нет; проба `nc connectivitycheck.gstatic.com 80` с устройства получает
`HTTP/1.0 204` напрямую (на тестовом прокси входящих нет); на хосте — 29–74 прямых SYN за 30 с при цикле запросов.

Тот же корень у обновления не через встроенный апдейтер (магазин, Obtainium, `adb install -r`): с выключенным автозапуском VPN
после установки не возвращается (замер: 30+ с «Off / Connect», уведомления нет); с автозапуском — через ~10 с.

### Как воспроизвести
1. Debug-сборка, подписка, подключить VPN кнопкой; выключить и снова включить кнопкой (в том же `:bg` — самый надёжный путь к отказу).
2. `adb shell run-as dev.folki.crown.debug kill -9 $(adb shell pidof dev.folki.crown.debug:bg)`.
3. Каждые 2–3 с `adb shell dumpsys connectivity | grep "VPN CONNECTED"` — пусто 35 с и дальше; logcat — `for bound-service`;
   на экране «Подключить», уведомления нет; сетевые запросы устройства идут напрямую.
4. Повторить с приложением в фоне (Home перед шагом 2).

## What Changes
1. **(Рекомендуется) Постоянный признак «VPN должен работать»** в общей БД настроек (отдельный ключ, не
   `KEY_CONNECTED_SINCE`, который стирает UI): ставится, когда пользователь (или автозапуск) запускает сервис, снимается при
   явной остановке пользователем (кнопка, уведомление, плитка, виджет), при ошибке старта (`failRunner`) и в `onRevoke`
   (пользователь отключил VPN в системе или его вытеснило другое VPN-приложение).
2. **`START_STICKY` пока признак стоит**, `START_NOT_STICKY` после остановки. При перезапуске системой (`intent == null`) сервис
   поднимает выбранный профиль, если признак стоит, иначе сразу останавливается. В `VpnService.onStartCommand` перезапуск
   идёт обычным путём с `prepare()`: если согласие отозвано, признак снимается и показывается уведомление вместо запуска
   `VpnRequestActivity` (запуск activity из фона заблокирован).
3. **Защита от цикла**: счётчик перезапусков в БД (например, не больше 3 за 10 минут). После лимита — признак снять и показать
   уведомление «VPN остановлен после сбоя» с действием «Подключить». Система сама увеличивает задержку перезапуска
   sticky-сервиса после повторных падений.
4. **Уведомление о потере**, даже если автоподъём не удался: новый `:bg` в `SagerNet.onCreate` (там уже есть `Widgets.push`)
   при стоящем признаке и не запущенном сервисе показывает «VPN отключился неожиданно — переподключить». Минимальная мера, если
   п.2 решат не делать.
5. **Обновление не через апдейтер**: `PackageReplacedReceiver` учитывает тот же признак (VPN работал до установки —
   поднять после), а не только `resumeAfterUpdate`/`rememberEnable`.
6. Simple UI: `HomeViewModel.onServiceConnected` перестаёт быть местом, где решается судьба признака; баннер «Соединение
   потеряно» показывается и по постоянному признаку, а не только из памяти.

Проверка: 10 убийств `:bg` (UI на экране, в фоне, после stop/start в том же процессе) — VPN снова `CONNECTED` за ≤ 15 с во
всех; после явной остановки и `kill -9` — VPN не поднимается; при искусственном падении на каждом старте — не больше 3
попыток, затем уведомление.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `proxy-connection`: новое требование «Восстановление после гибели процесса сервиса» — если пользователь не останавливал
  соединение, сервис после гибели процесса перезапускается с тем же профилем с ограничением частоты, а при неудаче
  пользователь получает уведомление; явная остановка или отзыв VPN-разрешения отменяют восстановление.
- `simple-ui-redesign/specs/simple-ui`: баннер «Соединение потеряно» опирается на хранимый признак, а не на память UI.

## Impact
- **Код:** `bg/BaseService.kt` (`onStartCommand`, `stopRunner`, `failRunner`), `bg/VpnService.kt` (`onStartCommand`,
  `onRevoke`), `bg/ProxyService.kt`, `BootReceiver.kt` (`PackageReplacedReceiver`), `SagerNet.kt` (уведомление в `:bg`),
  `simple/home/HomeViewModel.kt`, `simple/SimpleHooks.kt`.
- **Upstream:** `BaseService.kt` — существующая точка правки 6 (SimpleHooks start/stop), признак можно ставить/снимать в
  `SimpleHooks.onServiceStarted/onServiceStopped` (код Crown); смена `START_NOT_STICKY` в `BaseService.onStartCommand` —
  расширение той же точки. `VpnService.kt`, `ProxyService.kt`, `BootReceiver.kt` вне списков — нужна новая точка правки
  (или патч в upstream: проблема общая).
- **Ядро throne, сборка:** не затрагиваются.
- **Риски:** цикл перезапусков при конфиге, роняющем ядро (закрывается п.3); перезапуск без ведома пользователя после того,
  как он выключил VPN в системных настройках (закрывается снятием признака в `onRevoke`). Запуск foreground-сервиса при
  перезапуске из фона на Android 12+: в списке документированных исключений VPN не назван, но в AOSP есть исключение для
  приложений с выданным appop `ACTIVATE_VPN` (`REASON_OP_ACTIVATE_VPN` в `ActiveServices`), а в опытах выше перезапуск
  «for started-service» успешно выводил сервис на передний план. Режим «только прокси» (`ProxyService`) этого appop не
  имеет — там перезапуск разрешён только при исключении из оптимизации батареи; нужна проверка на Android 14–17 и запасной
  путь (уведомление) при `ForegroundServiceStartNotAllowedException`.
- **Проверка исправления:** сценарии выше на debug и release, Android 14 и 17; JVM-тест логики признака и счётчика.
- **Внешние источники:**
  - `Service.START_STICKY`: https://developer.android.com/reference/android/app/Service#START_STICKY
  - Ограничения запуска FGS из фона и исключения (в т.ч. «The user turns off battery optimizations for your app»):
    https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
  - VpnService (сеть восстанавливается при закрытии дескриптора, в т.ч. при падении приложения; always-on):
    https://developer.android.com/reference/android/net/VpnService
