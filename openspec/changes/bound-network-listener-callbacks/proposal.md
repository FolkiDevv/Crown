# Proposal

> **Категория:** скорость, стабильность · **Приоритет:** средний · **Проверка:** задержка очереди воспроизведена на
> эмуляторе; уход работы в главный поток — по коду; взаимная блокировка с ядром — гипотеза · **Код:** upstream · **Объём:** M

## Why

### Находка
`DefaultNetworkListener` — один актёр на `Dispatchers.Unconfined` с каналом-рандеву
(`app/src/main/java/io/nekohasekai/sagernet/utils/DefaultNetworkListener.kt:37`). События сети `Put`, `Update`, `Lost`
синхронно вызывают по очереди всех слушателей (`:65-89`). Колбэки `ConnectivityManager` отправляют события через
`runBlocking { networkActor.send(...) }` на потоке `DefaultNetworkListener` (`:115-134`).

Слушатели в `:bg`: по одному на каждый живой бокс ядра (главный VPN и пробные боксы тестов), слушатель сервиса
(`bg/BaseService.kt:558-576`) и слушатель приложения (`SagerNet.kt:70-74`). Слушатель бокса блокирует поток:
```kotlin
// moe/matsuri/nb4a/NativeInterface.kt:149-160 (сокращено)
repeat(10) {
    val linkProperties = SagerNet.connectivity.getLinkProperties(network)
    if (linkProperties == null) { Thread.sleep(100); return@repeat }
    val interfaceIndex = try { NetworkInterface.getByName(linkProperties.interfaceName).index }
        catch (e: Exception) { Thread.sleep(100); return@repeat }
    ...  // затем getNetworkCapabilities и JNI updateDefaultInterface в ядро
```
На одного слушателя — до ~1 с сна, два binder-вызова и вызов в ядро; слушатели идут подряд.

Последствия:
1. **Старт и остановка бокса ждут очередь без срока.** `startDefaultInterfaceMonitor`/`closeDefaultInterfaceMonitor`
   делают `runBlocking { DefaultNetworkListener.start/stop(...) }` (`NativeInterface.kt:96-108`) внутри `box.start()` и
   `box.close()` — на потоке `CoreLifecycle` и потоках тестов. `send` в канал-рандеву ждёт, пока актёр закончит текущее
   событие со всеми слушателями; `start` ещё и ждёт обработки (`DefaultNetworkListener.kt:94-101`).
2. **Работа может попасть в главный поток `:bg`.** Слушатель сервиса добавляется в `preInit` из `connectingJob` на
   `Dispatchers.Main` (`BaseService.kt:675-677`), снимается при остановке (`:374-375`). Актёр на `Unconfined`
   возобновляется в потоке отправителя: если в этот момент поток колбэков уже ждёт в `send(Put)`, актёр примет и этот `Put`
   и выполнит всех слушателей (сон, IPC, JNI) в главном потоке. `Start` к тому же сам вызывает нового слушателя на месте
   (`:46`): для сервиса это `getLinkProperties` и `setUnderlyingNetworks` в главном потоке.
3. **Гипотеза о взаимной блокировке.** Поток колбэков внутри `Put` вызывает `updateDefaultInterface` бокса B; одновременно
   `box.close()` того же бокса ждёт актёра в `runBlocking { send(Stop) }`. Если Go-сторона `updateDefaultInterface` ждёт
   блокировку, которую держит путь закрытия, получится вечное «Остановка». Исходников ядра в репозитории нет — не проверено.

Утечка слушателей закрытых боксов из upstream уже исправлена в форке (ключ по refnum в `NativeInterface.kt`), поэтому
число слушателей равно числу живых боксов плюс два.

### Влияние
Замер на эмуляторе Android 17 (debug-сборка, VPN подключён, переключения Wi-Fi ↔ сотовая сеть), строка журнала, которая
уже есть в коде (`DefaultNetworkListener.kt:72`):
```
DefaultNetworkListener Put network=123 listeners=3 elapsed=1484ms thread=DefaultNetworkListener
DefaultNetworkListener Put network=122 listeners=3 elapsed=1172ms thread=DefaultNetworkListener
```
Даже с тремя слушателями одно событие держит очередь 1,2–1,5 с; остальные события — 0–360 мс. Во время теста серверов
каждый пробный бокс добавляет слушателя, и задержка растёт. Пользователь видит: «Подключить» или «Стоп», нажатые сразу
после смены сети (выход из дома, Wi-Fi ↔ LTE), выполняются на секунды дольше; `setUnderlyingNetworks` и сброс сети ядра
приходят позже — VPN дольше сидит на старой сети. Если работа попадёт в главный поток `:bg`, подвисают кнопки уведомления,
broadcast'ы и binder-вызовы UI; в худшем случае ANR `:bg`. В журналах эмулятора `thread=main` не встречался.

### Как воспроизвести
Шаги 1–3 дают числа выше; шаги 4–5 выведены из кода и не прогонялись.
1. Debug-сборка, VPN подключён.
2. Переключать сеть: `adb shell svc wifi disable`, через 5 с `adb shell svc wifi enable`, повторить несколько раз.
3. `adb logcat | grep "DefaultNetworkListener Put"` — `elapsed` около секунды и больше.
4. Запустить URL-тест большой группы (много пробных боксов) и повторить шаг 2: `elapsed` растёт с `listeners`.
5. Нажать «Стоп» сразу после переключения сети и сравнить по `ServiceStopTrace` время от `stage=kill begin` до
   `stage=kill done` с обычной остановкой. Признак ухода в главный поток — `thread=main` в той же строке журнала.

## What Changes
- Рекомендуется: перевести актёр на собственный однопоточный диспетчер (поток `HandlerThread` или выделенный
  `CoroutineDispatcher`) вместо `Unconfined` — обработка никогда не попадёт в главный поток или поток ядра. Регистрацию и
  снятие слушателя (`start`/`stop`) обрабатывать вне очереди событий (отдельная потокобезопасная таблица слушателей),
  чтобы старт и остановка бокса не ждали чужих слушателей; это же снимает сценарий взаимной блокировки.
- Вызывать слушателей независимо (каждого на своей корутине с сохранением порядка событий для каждого), а ожидание
  `LinkProperties` (10 × 100 мс) делать через `delay`, а не `Thread.sleep`, — один медленный бокс не задерживает остальных.
- Ограничить ожидание в `startDefaultInterfaceMonitor` сроком с понятной записью в журнал.
- Проверка: строка журнала из шага 3 — `elapsed` не растёт с числом боксов, `thread` никогда не `main`; время остановки
  при смене сети не отличается от обычного.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `proxy-connection`: в «Connection state machine» уточняется — смена сети не задерживает переходы «Подключение» и
  «Остановка» и не выполняет работу в главном потоке сервиса.

## Impact
- **Код:** `utils/DefaultNetworkListener.kt`, `moe/matsuri/nb4a/NativeInterface.kt`; вызовы из `bg/BaseService.kt` не меняются.
- **Upstream:** `NativeInterface.kt` — существующая точка правки 8 («network listener keyed by the gomobile refnum»), её
  описание нужно расширить. `DefaultNetworkListener.kt` в списки не входит — нужна новая точка правки или патч в upstream.
- **Ядро throne, сборка, инструменты:** не затрагиваются; гипотезу о блокировке проверить по исходникам ядра
  (`updateDefaultInterface` и закрытие монитора интерфейса).
- **Риски:** нарушение порядка `Put` → `Lost` для одного слушателя отдаст боксу старую сеть — порядок для каждого слушателя
  сохранять. Дешёвая частичная мера: только заменить `Unconfined` на выделенный поток — закрывает главный поток, но не
  задержку очереди.
- **Проверка исправления:** шаги 1–5 до и после; юнит-тест на актёр — `start` нового слушателя не ждёт медленного слушателя.
- **Внешние источники:**
  - kotlinx.coroutines, `Dispatchers.Unconfined` (возобновление в потоке, который возобновил корутину): https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-dispatchers/-unconfined.html
