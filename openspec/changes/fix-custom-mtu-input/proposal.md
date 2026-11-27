# Proposal

> **Категория:** стабильность, надёжность · **Приоритет:** низкий · **Проверка:** воспроизведено на эмуляторе ·
> **Код:** upstream · **Объём:** S

## Why

### Находка
Строка «MTU» в настройках TUN (`MTUPreference`) по долгому нажатию даёт ввести своё значение. Экран открывается из
классического интерфейса и из нового («Расширенный режим» → «Сеть и TUN»).

```kotlin
// app/src/main/java/moe/matsuri/nb4a/ui/MTUPreference.kt:33-40
MaterialAlertDialogBuilder(context).setTitle("MTU")
    .setView(view)
    .setPositiveButton(android.R.string.ok) { _, _ ->
        val mtu = view.text.toString().toInt()
        if (mtu < 1000 || mtu > 10000) return@setPositiveButton
        value = mtu.toString()
    }
```

1. `toInt()` бросает `NumberFormatException` на пустом поле и на числе длиннее `Int` (`TYPE_CLASS_NUMBER` длину не
   ограничивает). В обработчике кнопки его никто не ловит, и процесс интерфейса падает.
2. `value = …` сохраняет значение, **не вызывая** `OnPreferenceChangeListener`. А именно на слушателе экран TUN
   держит и проверку, и предложение перезапуска:
   ```kotlin
   // app/src/main/java/io/nekohasekai/sagernet/ui/settings/NetworkSettingsScreens.kt:151-157
   reloadOn(SettingsRegistry.VPN_MTU.key, SettingsRegistry.VPN_IPV6.key, SettingsRegistry.ENABLE_TUN_ROUTING.key)
   ...
   validation.bind(mtu.key, Validation.Rules.int(MIN_MTU.toLong(), MAX_MTU.toLong(), Empty.Required)).gate()
   ```
   Своё значение MTU сохраняется молча: снекбара «Применить» нет, работающий VPN остаётся со старым MTU.
   Значение вне диапазона 1000–10000 тоже молча отбрасывается, без сообщения.

### Влияние
- Очевидное действие в диалоге («стереть и нажать OK») роняет приложение.
- MTU меняют как раз при проблемах со связью. Своё значение не применяется до переподключения, и пользователь
  решает, что «MTU не помогает». Требование `openspec/specs/settings-storage` «Restart-required hints» (сообщить,
  что изменение применится после переподключения) на этом пути не выполняется.
- Функция спрятана за долгим нажатием, поэтому приоритет низкий.

### Как воспроизвести
Debug-сборка `dev.folki.crown.debug`, эмулятор Android 17 (API 37).

1. Настройки → «Расширенный режим» → «Network and TUN». Долгое нажатие на «MTU» → в поле «9000».
2. Стереть поле и нажать OK:
   ```
   E AndroidRuntime: FATAL EXCEPTION: main
   E AndroidRuntime: Process: dev.folki.crown.debug, PID: 17384
   E AndroidRuntime: java.lang.NumberFormatException: For input string: ""
   E AndroidRuntime:     at java.lang.Integer.parseInt(Integer.java:789)
   ```
3. Подключить VPN, снова долгое нажатие → «1400» → OK. Снекбара «Reload proxy service to apply changes» нет; за 8 с
   в журнале ни одной строки `Vpn: …`. Повторное открытие диалога показывает 1400 — значение сохранено.
4. Контроль: обычное нажатие на «MTU» → выбрать «9000» из списка. Появляется снекбар «Reload proxy service to apply
   changes / Apply»; после «Apply» — `Vpn: Established by dev.folki.crown.debug on tun0`.

## What Changes
1. В обработчике OK разбирать число через `toIntOrNull()`. Пустое, нечисловое или вне диапазона значение — не
   закрывать диалог молча, а показать ошибку у поля (диапазон уже задан `MIN_MTU`/`MAX_MTU`).
2. Сохранять значение стандартным путём `Preference`: `if (callChangeListener(newValue)) value = newValue`. Тогда
   сработают и проверка экрана (gate), и `reloadOn` со снекбаром «Применить».
3. Проверка «до/после»: шаг 2 не роняет приложение и показывает ошибку; шаг 3 показывает снекбар, как в шаге 4.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `settings-storage`: в «Restart-required hints» уточнить, что сигнал о перезапуске показывается и для значения,
  введённого вручную (свой MTU).

## Impact
- **Код:** `app/src/main/java/moe/matsuri/nb4a/ui/MTUPreference.kt`.
- **Upstream:** файл upstream, точки правки форка нет (`tools/diagnostics/upstream_touch_points.py`) — патч в upstream
  или новая точка правки.
- **Ядро throne, сборка, инструменты:** не затрагиваются.
- **Риски:** практически нет; поведение выбора из списка не меняется. В новом интерфейсе снекбар всё равно
  пропадает при выходе из визита — это закрывает `deliver-advanced-settings-to-running-vpn`.
- **Проверка исправления:** сценарий выше; unit-тест разбора ввода («», «abc», «99999999999», «999», «1400»).
