# Proposal

> **Категория:** стабильность · **Приоритет:** средний · **Проверка:** воспроизведено на эмуляторе ·
> **Код:** upstream · **Объём:** M

## Why

### Находка
Экраны маршрутизации передают друг другу правило или весь профиль JSON-строкой в extras Intent, без ограничения
размера:

```kotlin
// app/src/main/java/io/nekohasekai/sagernet/ui/route/RouteProfileActivity.kt:492-499
private fun editRule(index: Int) {
    val rule = model.profile?.rules?.getOrNull(index) ?: return
    undoManager.flush()
    ruleEditor.launch(Intent(this, RouteRuleActivity::class.java).apply {
        putExtra(RouteRuleActivity.EXTRA_RULE, RouteJson.ruleToJson(rule))
        putExtra(RouteRuleActivity.EXTRA_INDEX, index)
    })
}
```

Тот же приём используется:
- в `addRule` (`RouteProfileActivity.kt:482-490`);
- при «Тесте маршрутов» несохранённого профиля — весь черновик в `EXTRA_ROUTE_DRAFT` (`RouteProfileActivity.kt:388-393`);
- для результата редактора правила (`ui/route/RouteRuleActivity.kt:306`);
- при импорте «старого» массива правил — весь профиль в `EXTRA_PROFILE_JSON` (`ui/RouteFragment.kt:340-345`).

`RouteJson.ruleObject` кладёт каждый список правила (`domain`, `domain_suffix`, `ip_cidr`, `package_name` …)
целиком. Строка в Parcel занимает 2 байта на символ, а буфер binder-транзакций процесса — около 1 МБ на все
одновременные вызовы. Размер нигде не проверяется, ошибка запуска не обрабатывается.

Правила с десятками тысяч доменов появляются при импорте списка из буфера, из удалённого профиля маршрутов или
из подписки, которая импортирует маршруты.

### Влияние
- Нажатие на большое правило в редакторе профиля закрывает приложение: система не может доставить запуск экрана
  и убивает процесс интерфейса — дважды подряд, затем открывает список маршрутов. Несохранённые правки профиля
  теряются («no saved state»).
- Повторяется при каждой попытке: такое правило нельзя открыть или поправить в интерфейсе. То же ждёт «Тест
  маршрутов» несохранённого профиля, сохранение в редакторе правила и импорт большого массива правил.
- Данные в БД не портятся: профиль сохраняется внутри процесса. VPN (процесс `:bg`) не затрагивается.

### Как воспроизвести
Debug-сборка `dev.folki.crown.debug`, эмулятор Android 17 (API 37).

1. Подготовить правило с 25 000 записей `domain_suffix` вида `exampleNNNNN.com` (475 001 символ JSON). Проще всего
   прямо в БД: снять `databases/sager_net.db` через `adb exec-out run-as … cat`, на хосте записать список в
   `route_rules.domain_suffix_json` первого правила, вернуть файл через `adb exec-in run-as … sh -c 'cat > …'`.
   Тот же результат даёт импорт такого массива правил из буфера.
2. Настройки → «Расширенный режим» → «Routes and rules» → «Edit» у профиля → карточка правила
   «suffix: example00000.com +24999».
3. Журнал:
   ```
   JavaBinder: !!! FAILED BINDER TRANSACTION !!!  (parcel size = 955456)
   ClientLifecycleManager: android.os.TransactionTooLargeException: data parcel size 955456 bytes
   ClientLifecycleManager: Failed to deliver transaction for ProcessRecord{… dev.folki.crown.debug/u0a243}
   ActivityManager: Process dev.folki.crown.debug (pid 16782) has died: fg  TOP
   ActivityTaskManager: Force removing ActivityRecord{… RouteProfileActivity …}: app died, no saved state
   ClientLifecycleManager: Failed to deliver transaction for ProcessRecord{… 17094:dev.folki.crown.debug/u0a243}
   wm_finish_activity: [… RouteRuleActivity,2nd-crash]
   ```
   Пользователь оказывается на списке маршрутов, редактор правила не открылся.

Unit-тест: собрать `Intent` с `RouteJson.ruleToJson(rule)` для правила на 25 000 записей и проверить, что размер
`Parcel` после `writeToParcel` больше 500 КБ.

## What Changes
1. **(Рекомендуется.)** Передавать между экранами маршрутизации не содержимое, а ключ. Правило или черновик
   профиля записывать во временный файл в `cacheDir` (или в черновик в БД), а в Intent класть только его
   идентификатор. Редактор правила, тестер и редактор профиля читают данные по ключу. Файл переживает смерть
   процесса, в отличие от реестра в памяти.
2. Результат редактора правила возвращать тем же способом: ключ вместо JSON.
3. Импорт «старого» массива правил — сохранять импортированный профиль во временный черновик и передавать путь.
4. Минимальная мера, если п. 1 откладывается: перед `launch` проверять длину строки (например, больше
   200 000 символов) и показывать «Правило слишком большое для редактирования здесь; используйте набор правил
   (rule-set)»; ловить `RuntimeException` вокруг `launch`.
5. Проверка «до/после»: шаг 2 открывает редактор правила без сбоя; тест размера Parcel показывает несколько
   десятков байт вместо ~1 МБ.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `routing-rules`: в «Routing rule definition» добавить сценарий: правило с большим списком (десятки тысяч
  записей) открывается, сохраняется и тестируется без сбоя интерфейса.

## Impact
- **Код:** `ui/route/RouteProfileActivity.kt`, `ui/route/RouteRuleActivity.kt`, `ui/route/RouteTesterActivity.kt`,
  `ui/RouteFragment.kt`, `ui/route/RouteJson.kt` (вспомогательные функции записи и чтения черновика).
- **Upstream:** файлы upstream, точек правки форка в них нет (`tools/diagnostics/upstream_touch_points.py`) —
  патч в upstream или новая точка правки. Новый интерфейс открывает эти экраны визитом, своего кода не требует.
- **Ядро throne, сборка, инструменты:** не затрагиваются.
- **Риски:** временные файлы нужно удалять при закрытии редактора и по возрасту. Сам редактор списка
  (`EditText` на 25 000 строк) будет медленным — разумно ограничить ручную правку очень больших списков и
  предлагать rule-set.
- **Проверка исправления:** unit-тест размера Parcel; сценарий выше; пересоздание activity и смерть процесса во
  время редактирования (черновик восстанавливается).
- **Внешние источники:** «TransactionTooLargeException»
  (https://developer.android.com/reference/android/os/TransactionTooLargeException).
