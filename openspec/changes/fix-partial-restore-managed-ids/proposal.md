# Proposal

> **Категория:** надёжность · **Приоритет:** средний · **Проверка:** подтверждено по коду · **Код:** Crown (и страховка в
> upstream-файле) · **Объём:** S–M

## Why

### Находка
Режимы трафика simple ссылаются на свои маршруты и автовыборы по id, сохранённым в таблице `settings`:
`simple_managed_route_smart`, `simple_managed_route_whitelist`, `simple_managed_auto`, `simple_managed_auto_whitelist`,
`simple_managed_country` (`app/src/simple/java/io/nekohasekai/sagernet/simple/modes/TrafficModes.kt:55-61`; хранилище —
`DataStore.configurationStore`, `:72`, то есть часть «Настройки» резервной копии). Резервная копия переносит таблицы по
частям и сохраняет id строк (`app/src/main/java/io/nekohasekai/sagernet/database/backup/BackupRestore.kt:350-366`). Для
ссылок upstream между частями восстановление уже чинит рассинхронизацию:
```kotlin
// BackupRestore.kt:367-371
// Database.cpp:590-594: route_id and source_gid point across the two parts; restored apart, they would name unrelated rows.
if (choice.profiles != choice.routes) {
    db.execSQL("UPDATE `$GROUPS` SET `route_id` = -1")
    db.execSQL("UPDATE `$ROUTE_PROFILES` SET `source_gid` = -1")
}
```
Ключи Crown в этой логике не учтены. Опасны два частичных случая:
- восстановлены «Маршруты» без «Настроек» (с компьютера или другого телефона): `route_profiles` заменены строками копии, а
  локальный `simple_managed_route_whitelist = N` указывает на маршрут N из копии;
- восстановлены «Настройки» без «Маршрутов» или «Профилей» из Android-копии другого устройства: заменяются все ключи, кроме
  `DEVICE_LOCAL_KEYS` (`BackupRestore.kt:419-436`, `database/SettingsRegistry.kt:399-405`; ключей `simple_*` там нет), и
  чужие `simple_managed_*` указывают на здешние маршруты и профили с теми же номерами.

Следующий `ensureManaged` (при открытии simple и при каждом применении режима) берёт любой не-raw маршрут с этим id и
переписывает его:
```kotlin
// TrafficModes.kt:188-210 (сокращено)
val existing = if (stored > 0L) RouteManager.get(stored)?.takeIf { !it.is_raw } else null
...
if (upToDate) return existing!!.id to false
profile.default_outbound_id = desired.default_outbound_id
profile.custom_dns = true
profile.dns = desired.dns
profile.rules = desired.rules
val id = RouteManager.save(profile)
```
Имя маршрута остаётся прежним, поэтому подмену трудно заметить. Так же `ensureSelector` (`TrafficModes.kt:247-266`)
переписывает у автовыбора с id `simple_managed_auto` группы, ярусы и стратегию, а `ensureSmartRoute` (`:166-176`) берёт чужой
маршрут как «Умный» режим, и режим работает по чужим правилам.

Полное восстановление Android-копии согласовано (id и ключи из одной копии); «Настройки» из копии десктопа удаляют ключи
`simple_*`, и simple создаёт свои сущности заново — эти случаи безопасны.

### Влияние
Пользователь переносит маршруты с компьютера («Маршруты» без «Настроек» — обычный сценарий переноса), открывает simple, и его
маршрут, например «Обход блокировок» с десятками правил, без предупреждения становится маршрутом «Белых списков» из двух
правил: весь трафик идёт через прокси, правила обхода пропали. Если маршрут был текущим, VPN перезапускается с ним. Отменить
нельзя — только восстановить копию ещё раз. Для автовыбора пропадает ручная настройка ярусов и групп.
`backup-restore`, «Partial backup selection»: восстановление одной части не должно менять другие — здесь оно ведёт к порче
другой части.

### Как воспроизвести
Шаги выведены из кода и не прогонялись.
1. В simple один раз выбрать режим «Белые списки», чтобы создался управляемый маршрут. Узнать его id:
   `adb shell run-as dev.folki.crown.debug sqlite3 databases/<файл БД> "select key, value from settings where key like 'simple_managed%'"`.
2. На другом устройстве (или в десктопной версии) сделать копию с несколькими своими маршрутами так, чтобы маршрут с тем же id
   существовал и не был raw (на свежей установке id начинаются с 1 — пересечение почти гарантировано).
3. На тестовом устройстве: «Перенос на другое устройство» → «Восстановить», отметить только «Маршруты».
4. После перезапуска открыть simple.
5. Ожидаемый неверный результат: у маршрута с id из шага 1 прежнее имя, но правила «Route DNS» и «Private networks» и
   `custom_dns = 1` вместо правил из копии.
Тестом: JVM-тест `TrafficModes.ensureManaged` на БД в памяти, где `simple_managed_route_whitelist` указывает на маршрут с тремя
пользовательскими правилами, — сейчас правила заменяются.

## What Changes
- Рекомендуется (код Crown, `TrafficModes`): перед перезаписью маршрута или автовыбора по сохранённому id проверять, что это
  действительно управляемая сущность. Хранить рядом с id метку или отпечаток последнего записанного содержимого; если
  сущность по id не совпадает ни с меткой, ни с ожидаемым видом, считать ссылку оборванной — создать новую управляемую
  сущность, найденную не трогать.
- Страховка в `BackupRestore.commit` по образцу `route_id`/`source_gid`: сбрасывать `simple_managed_route_*` при
  `choice.routes != choice.settings`, а `simple_managed_auto*` и `simple_managed_country` — при
  `choice.profiles != choice.settings`.
- Дополнительно: внести состояние устройства simple (`simple_connected_since`, `simple_service_starts`, `simple_network_since`,
  `simple_regime`) в `DEVICE_LOCAL_KEYS`, чтобы Android-копия не переносила его на другой телефон.
- Проверка: сценарий выше оставляет маршрут пользователя нетронутым, для «Белых списков» создаётся новый маршрут.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `backup-restore`: в «Partial backup selection» добавляется — после восстановления части ссылки simple на управляемые
  маршруты и автовыборы, указывающие в другую часть, сбрасываются.
- `traffic-modes` (изменение `simple-ui-redesign`): управляемые сущности режимов перезаписываются, только если подтверждено,
  что это они; чужая сущность с тем же id не меняется.

## Impact
- **Код:** `app/src/simple/.../simple/modes/TrafficModes.kt` (основное); `database/backup/BackupRestore.kt` и
  `database/SettingsRegistry.kt` (страховка).
- **Upstream:** основное решение — код Crown. Страховка в `BackupRestore.kt` требует новой точки правки;
  `SettingsRegistry.kt` — существующая точка идентичности форка, но с новым назначением (описание расширить).
- **Ядро throne, сборка, инструменты:** не затрагиваются; `tools/diagnostics/upstream_touch_points.py` — новая точка правки.
- **Риски:** метка или отпечаток должны обновляться при каждой законной перезаписи, включая откат правок из классического
  интерфейса, которые `ensureWhitelistRoute` сознательно возвращает, — иначе simple начнёт плодить новые маршруты. Только
  сброс в `BackupRestore` закрывает восстановление, но не ручную правку ключей в расширенном режиме и будущие пути импорта.
- **Проверка исправления:** JVM-тест из «Как воспроизвести»; сценарий частичного восстановления на устройстве.
- **Внешние источники:** нет.
