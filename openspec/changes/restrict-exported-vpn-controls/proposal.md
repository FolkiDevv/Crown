# Proposal

> **Категория:** надёжность · **Приоритет:** высокий · **Проверка:** воспроизведено на эмуляторе (ярлыки);
> подтверждено по коду (`BootReceiver`) · **Код:** upstream · **Объём:** S–M

## Why

### Находка
Команды сервису по broadcast защищены разрешением уровня `signature`: приёмник регистрируется с
`"$packageName.SERVICE"` (`bg/BaseService.kt:643-649`), разрешение объявлено в `AndroidManifest.xml:8-10`. То есть чужое
приложение не должно уметь послать `Action.CLOSE`/`RELOAD`. Но те же действия доступны без всякого разрешения через три
активности ярлыков:

```xml
<!-- app/src/main/AndroidManifest.xml:321-351 (сокращено) -->
<activity android:name=".QuickToggleShortcut" android:exported="true" android:process=":bg"
    android:theme="@android:style/Theme.Translucent.NoTitleBar"> <intent-filter> CREATE_SHORTCUT </intent-filter>
<activity android:name="io.nekohasekai.sagernet.ui.QuickEnableShortcut"  android:exported="true" … />
<activity android:name="io.nekohasekai.sagernet.ui.QuickDisableShortcut" android:exported="true" … />
```

`intent-filter` не ограничивает явный intent по имени компонента. Activity прозрачная, с `excludeFromRecents`, закрывается
сразу после привязки к сервису. `QuickToggleShortcut` к тому же берёт id сервера у вызывающего:

```kotlin
// app/src/main/java/io/nekohasekai/sagernet/QuickToggleShortcut.kt:55, 65-77
profileId = intent.getLongExtra("profile", -1L)
…
state.canStop -> if (profileId == DataStore.selectedProxy || profileId == -1L) SagerNet.stopService()
                 else { DataStore.selectedProxy = profileId; SagerNet.reloadService() }
state == BaseService.State.Stopped -> { if (profileId >= 0L) DataStore.selectedProxy = profileId; SagerNet.startService() }
```

`QuickDisableShortcut.kt:44-49` — `if (state.canStop) SagerNet.stopService()`, `QuickEnableShortcut` — `startService()`.
Broadcast `CLOSE` уходит уже из процесса приложения, поэтому проверка `signature`-разрешения проходит.

Тот же класс проблемы у `BootReceiver` (`AndroidManifest.xml:451-460`, `exported="true"`, без разрешения): обработчик не
проверяет action, всё, кроме `LOCKED_BOOT_COMPLETED`, считается загрузкой (`BootReceiver.kt:29-34`). Компонент включён,
когда пользователь включил автоподключение (`BaseService.kt:627` `BootReceiver.enabled = DataStore.rememberEnable`).

Все законные вызовы идут изнутри приложения и в экспорте не нуждаются: динамические ярлыки (`utils/AppShortcuts.kt:27-31`) и
закреплённые через `requestPinShortcut` (`ui/profile/ProfileSettingsActivity.kt:434`) запускаются лаунчером через
`LauncherApps.startShortcut` от имени приложения-издателя; виджет — через `PendingIntent` приложения (`appwidget/Widgets.kt:208`).
Экспорт нужен только для `ACTION_CREATE_SHORTCUT` (выдача ярлыка лаунчеру) и для ярлыков старых лаунчеров на API 24–25.

### Влияние
- Любое приложение без разрешений, находящееся на переднем плане (запуск activity из фона Android блокирует), одной строкой
  `startActivity` выключает VPN. Следующий его запрос уходит мимо туннеля и раскрывает реальный IP; затем оно может включить
  VPN обратно — пользователь видит в лучшем случае мерцание. Для аудитории, которая ставит VPN ради обхода блокировок,
  приложения, проверяющие наличие VPN, — реальная угроза.
- Через `profile` можно переключить на любой сервер подписки или записать несуществующий id — сервис останавливается с
  «profile_empty», в simple UI появляется «Соединение потеряно».
- `BootReceiver`: явный broadcast от чужого приложения включает VPN без действия пользователя; если согласия на VPN нет, сервис
  стартует без `startForeground` — это путь к падению `:bg` (см. `recover-from-background-process-crash`). Утечки нет.
- Расхождение с `proxy-connection`, «External control actions»: команды обещаны из уведомления, виджетов и плитки, а не из
  чужих приложений. Simple UI для ссылок `crown://` специально требует подтверждения (`SimpleActivity.kt:457-462`), а
  управление самим VPN снаружи открыто.

### Как воспроизвести
Эмулятор Android 17, debug-сборка (`dev.folki.crown.debug`), VPN подключён. `adb shell am start` выполняется от uid shell —
для экспортированной activity без разрешения это равносильно вызову из постороннего приложения.
1. `adb shell am start -n dev.folki.crown.debug/io.nekohasekai.sagernet.QuickToggleShortcut` → через 3–5 с
   `dumpsys connectivity` — VPN нет.
2. Повторить — VPN снова включён.
3. `… QuickToggleShortcut --el profile <id другого сервера>` — VPN перезапущен на другом сервере (видно по журналу
   тестового прокси: входящие сменились с одного inbound на другой).
4. `… QuickToggleShortcut --el profile 999999` — VPN выключен, в simple UI «Connection lost».
5. `BootReceiver` (по коду, не запускалось): включить «Подключаться при включении телефона», один раз подключиться, отключить;
   `adb shell am broadcast -n dev.folki.crown.debug/io.nekohasekai.sagernet.BootReceiver -a foo.bar` — ожидается подключение.

## What Changes
1. **(Рекомендуется) Разделить «выдачу ярлыка» и «исполнителя».** `QuickEnableShortcut`, `QuickDisableShortcut` и
   исполнитель `QuickToggleShortcut` — `exported="false"`. Для `ACTION_CREATE_SHORTCUT` — отдельная маленькая экспортированная
   activity, которая только возвращает `createShortcutResultIntent` и ничего не включает. На API 26+ результат —
   закреплённый ярлык `ShortcutManager`, лаунчер запускает его от имени приложения, неэкспортированный исполнитель доступен.
2. **API 24–25** (устаревший `INSTALL_SHORTCUT`, лаунчер запускает intent сам): оставить экспортированный вход только для
   этих версий и требовать в intent случайный секрет, созданный при первом запуске и хранящийся в настройках; без секрета —
   ничего не делать (или показать подтверждение).
3. **Extra `profile`** принимать только от своих вызовов и только если такой профиль существует; несуществующий id —
   игнорировать, а не останавливать VPN.
4. **`BootReceiver`**: в начале `onReceive` выходить, если action не `BOOT_COMPLETED`/`LOCKED_BOOT_COMPLETED` (как уже делает
   `PackageReplacedReceiver`, `BootReceiver.kt:44`). Эти action защищены системой, подделать их нельзя. Снимать экспорт не
   нужно.
5. По желанию — настройка «Разрешить управление VPN другим приложениям» (выключена по умолчанию) для автоматизаций (Tasker и
   т. п.), с явной exported-точкой под разрешением.

Проверка «до/после»: шаги 1–5 перестают менять состояние VPN; ярлыки с рабочего стола (динамические и закреплённые),
виджет, плитка, уведомление и автоподключение после настоящей перезагрузки работают как раньше.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `proxy-connection`: требование «External control actions» уточняется — действия доступны из уведомления, виджетов, плитки
  и ярлыков самого приложения; посторонние приложения без явного разрешения пользователя не могут остановить, запустить или
  переключить соединение; автозапуск реагирует только на системные события загрузки.

## Impact
- **Код:** `AndroidManifest.xml` (активности ярлыков, новая activity выдачи ярлыка), `QuickToggleShortcut.kt`,
  `ui/QuickEnableShortcut.kt`, `ui/QuickDisableShortcut.kt`, `utils/AppShortcuts.kt`, `BootReceiver.kt`.
- **Upstream:** манифест — существующая точка правки 3, но её описание (SimpleActivity, WhitelistZoneReceiver, `crown://`)
  нужно расширить; Kotlin-файлы ярлыков и `BootReceiver.kt` вне списков — новые точки правки (или патч в upstream: дыра общая).
- **Ядро throne, сборка:** не затрагиваются.
- **Риски:** ярлыки, закреплённые на API 24–25 старыми версиями (без секрета), перестанут работать — их нужно пересоздать
  или принимать с подтверждением; сторонние автоматизации, которые сейчас дёргают эти activity, сломаются (п.5). Прошивки,
  шлющие `QUICKBOOT_POWERON`, — при необходимости добавить в фильтр и проверку явно.
- **Проверка исправления:** `am start`/`am broadcast` из шагов выше на debug и release; ручная проверка ярлыков и виджета
  на Android 8+ и на API 24/25.
- **Внешние источники:**
  - `ShortcutInfo.Builder`: «a shortcut can launch an unexported activity within the publisher app» —
    https://developer.android.com/reference/android/content/pm/ShortcutInfo.Builder
  - Ограничения запуска activity из фона (поэтому атакующему нужен передний план):
    https://developer.android.com/guide/components/activities/background-starts
