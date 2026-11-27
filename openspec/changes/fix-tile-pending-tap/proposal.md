# Proposal

> **Категория:** надёжность · **Приоритет:** низкий · **Проверка:** подтверждено по коду · **Код:** upstream · **Объём:** S

## Why

### Находка
Плитка быстрых настроек запоминает нажатие, пришедшее до привязки к сервису, и не забывает его, если привязка оборвалась:
```kotlin
// app/src/main/java/io/nekohasekai/sagernet/bg/TileService.kt (сокращено)
override fun onServiceConnected(service: ISagerNetService) {        // :21-27
    updateTile(...)
    if (tapPending) { tapPending = false; onClick() }
}
override fun onStartListening() { super.onStartListening(); connection.connect(this, this) }   // :34-37
override fun onStopListening() { connection.disconnect(this); super.onStopListening() }        // :39-42
private fun toggle() {                                                // :73-82
    val service = connection.service
    if (service == null) tapPending = true else ...                   // start / stop
}
```
Если `onClick` пришёл раньше `onServiceConnected`, ставится `tapPending = true`. Если затем шторку закрыли до завершения
привязки, `onStopListening` → `SagerConnection.disconnect` отвязывает сервис и обнуляет `service` и `callback`
(`bg/SagerConnection.kt:176-190`), а `tapPending` остаётся `true`. Экземпляр плитки живёт, пока его держит системная шторка.
При следующем открытии шторки: `onStartListening` → `connect` → `onServiceConnected` → `onClick()` — VPN переключается без
нажатия.

Плитка работает в процессе `:bg` (`AndroidManifest.xml:419-425`), привязка к `CoreService` асинхронна. Окно «нажатие до
привязки» шире всего, когда процесс `:bg` только поднимается вместе с плиткой и создание сервиса тяжёлое (загрузка ядра), а
пользователь нажимает плитку сразу и сразу закрывает шторку.

### Влияние
Редко, но выглядит как самопроизвольное включение или выключение VPN: пользователь открыл шторку посмотреть уведомления —
и VPN сам выключился (трафик пошёл мимо туннеля) или включился. Первое нажатие при этом теряется без реакции.
`proxy-connection`, «External control actions», сценарий «Quick settings tile toggle»: нажатие запускает выбранный профиль,
повторное — останавливает, то есть одно нажатие — одно действие.

### Как воспроизвести
Шаги выведены из кода и не прогонялись; окно по времени узкое.
1. VPN выключен, процесс `:bg` выгружен (`adb shell run-as dev.folki.crown.debug kill <pid :bg>`). Плитка приложения в
   быстрых настройках.
2. Открыть шторку, сразу нажать плитку и сразу закрыть шторку; повторить несколько раз. Расширить окно можно нагрузкой на
   CPU (например, во время теста серверов) или медленным устройством.
3. Ожидаемый неверный результат: VPN не включился; при следующем открытии шторки он включается сам — в logcat `:bg` старт
   сервиса без нового нажатия.
4. Тестом (Robolectric): `onStartListening` → `onClick` (сервис ещё не привязан) → `onStopListening` → `onStartListening` →
   привязать сервис; проверить, что во втором цикле `SagerNet.startService` не вызывается.

## What Changes
- Рекомендуется: не ждать привязки. Плитка живёт в одном процессе с сервисом, и `DataStore.serviceState` там точен:
  при `service == null` сразу решить по нему (`Stopped`/`Idle` → запуск, `canStop` → остановка). Это убирает и потерю
  нажатия, и «призрачное» нажатие. Свежий процесс даёт `Idle` — трактовать как «остановлено», как уже делает снимок
  состояния виджетов.
- Минимальная мера: сбрасывать `tapPending = false` в `onStopListening` — убирает «призрак», но первое нажатие по-прежнему
  теряется.
- Проверка: тест из шага 4 и ручной шаг 2 — одно нажатие даёт ровно одно действие.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `proxy-connection`: в «External control actions» уточняется — нажатие плитки выполняется сразу или не выполняется вовсе;
  нажатие не откладывается до следующего открытия шторки.

## Impact
- **Код:** `bg/TileService.kt`.
- **Upstream:** код upstream вне точек правки — новая точка правки или патч в upstream (там то же поведение).
- **Ядро throne, сборка, инструменты:** не затрагиваются.
- **Риски:** решение по `DataStore.serviceState` в момент перехода (`Connecting`/`Stopping`) — повторить поведение текущего
  `toggle()`: `canStop` останавливает, промежуточные состояния не запускают второй старт.
- **Проверка исправления:** Robolectric-тест из шага 4; ручная проверка плитки на холодном `:bg`.
- **Внешние источники:**
  - `TileService` (жизненный цикл `onStartListening`/`onStopListening`/`onClick`): https://developer.android.com/reference/android/service/quicksettings/TileService
