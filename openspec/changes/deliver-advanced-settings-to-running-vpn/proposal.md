# Proposal

> **Категория:** надёжность · **Приоритет:** средний · **Проверка:** воспроизведено на эмуляторе ·
> **Код:** Crown (классические экраны — upstream, без правки) · **Объём:** S

## Why

### Находка
Новый интерфейс открывает классические экраны настроек «визитом» (`ClassicVisit`): DNS, «Сеть и TUN», пресеты
протоколов, ядро, тестирование, маршруты, все классические настройки (`simple/settings/ClassicScreens.kt:45-74`).
Классические экраны сохраняют каждое изменение сразу, а применить его к работающему VPN предлагают только
снекбаром с кнопкой:

```kotlin
// app/src/main/java/io/nekohasekai/sagernet/ktx/Utils.kt:160-166
fun Fragment.needReload() {
    if (DataStore.serviceState.started) {
        snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
            SagerNet.reloadService()
        }.show()
```

Снекбар вызывается из `SettingsScreenFragment.reloadOn` (`ui/settings/SettingsScreenFragment.kt:80-86`) и живёт в
окне `MainActivity`. Выход из визита сразу закрывает это окно:

```kotlin
// app/src/simple/java/io/nekohasekai/sagernet/simple/settings/ClassicScreens.kt:238-245
private fun leave() {
    if (main.isFinishing) return
    main.startActivity(Intent(main, SimpleActivity::class.java)
        .putExtra(SimpleActivity.EXTRA_SECTION, SimpleSection.SETTINGS.name))
    main.finish()
}
```

Вместе с окном пропадает и снекбар. `SimpleActivity.onStart` (`SimpleActivity.kt:379-405`) обновляет режимы,
серверы и настройки, но сервис не перезагружает и не сообщает, что нужна перезагрузка. Других вызовов
`reloadService` после визита в `app/src/simple` нет. Для редактора серверов такая же проблема уже закрыта
(`SimpleActivity.kt:183`, `EXTRA_RESTART_NEEDED` → `servers.settingsChanged()`), для настроек — нет.

### Влияние
- Пользователь «Расширенного режима» меняет DNS-сервер, MTU, стек TUN, стратегию доменов и сразу нажимает «Назад».
  Изменение сохранено, но VPN продолжает работать со старой конфигурацией до следующего переподключения, смены сети
  с перезагрузкой или перезапуска. Ни один экран нового интерфейса об этом не говорит, и естественный вывод
  пользователя — «настройка не работает».
- Чаще всего правят DNS (из-за блокировок и утечек) и TUN — как раз когда «что-то не открывается».
- Нарушается `openspec/specs/settings-storage` «Restart-required hints»: при изменении настройки во время работы VPN
  приложение ДОЛЖНО сообщить, что изменение применится после переподключения. В визите сигнал живёт до первого
  «Назад».

### Как воспроизвести
Debug-сборка `dev.folki.crown.debug`, эмулятор Android 17 (API 37), VPN подключён.

1. Настройки → включить «Расширенный режим» → «DNS» → «Remote DNS» → ввести другой адрес → OK.
   Внизу появляется снекбар «Reload proxy service to apply changes» с кнопкой «Apply».
2. Не трогая снекбар, нажать системную «Назад». В журнале:
   `wm_finish_activity … ui.MainActivity`, `wm_create_activity … simple.SimpleActivity`.
3. Наблюдать журнал 20 с: ни одной строки `Vpn: …` и `am_foreground_service_*`. Главная показывает «Protected /
   Connected for 4:36» — счётчик не сбросился, переподключения не было. Сообщения о перезагрузке нет.
4. Контроль: снова открыть DNS, изменить адрес и нажать «Apply» в снекбаре:
   ```
   Vpn: setting state=DISCONNECTED, reason=agentDisconnect
   Vpn: Established by dev.folki.crown.debug on tun0
   ```
   Туннель пересоздаётся. Значит, без «Apply» новая настройка до сервиса не доходит.

## What Changes
1. **(Рекомендуется.)** Визит запоминает, что за время его работы менялись настройки, влияющие на конфигурацию, и
   сообщает об этом новому интерфейсу при выходе:
   - при открытии визита снять снимок значений ключей, влияющих на конфигурацию (их перечень уже есть в
     `SettingsRegistry`), а в `leave()` сравнить и передать в `SimpleActivity` флаг «нужна перезагрузка»;
   - `SimpleActivity` при этом флаге и запущенном сервисе вызывает тот же путь, что после редактора серверов
     (`TrafficModeApplier.settingsChanged()`) — один раз, а не на каждое изменение.
   Сравнение снимков надёжнее перехвата `needReload`: часть экранов (приложения без VPN, JSON-редакторы) применяет
   изменения через слушатель в `MainActivity`, а не через снекбар.
2. Альтернатива: при выходе из визита, если за визит показывался `needReload`, спросить «Применить изменения
   сейчас?».
3. Минимальная мера: после визита показать в новом интерфейсе сообщение «Изменения применятся после
   переподключения» с кнопкой.
4. Проверка «до/после»: шаги 1–3 — после «Назад» в журнале `Vpn: Established …`, в конфигурации ядра новый
   DNS-сервер; без изменений визит не вызывает перезагрузки.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `settings-storage`: в «Restart-required hints» добавить сценарий: изменение, сделанное в классическом экране,
  открытом из нового интерфейса, применяется (или явно предлагается применить) после выхода из этого экрана.
- `simple-ui` (в `openspec/changes/simple-ui-redesign/specs/simple-ui`): в «Расширенный режим» — то же обещание для
  технических экранов.

## Impact
- **Код:** `app/src/simple/java/io/nekohasekai/sagernet/simple/settings/ClassicScreens.kt` (`ClassicVisit`),
  `app/src/simple/java/io/nekohasekai/sagernet/simple/SimpleActivity.kt` (обработка флага), при необходимости
  `SettingsRegistry` — только чтение перечня ключей.
- **Upstream:** код Crown; классические экраны и `needReload` не меняются.
- **Ядро throne, сборка, инструменты:** не затрагиваются.
- **Риски:** перезагрузка рвёт соединения на секунду — делать её один раз при выходе. Флаг должен пережить
  пересоздание `SimpleActivity` (класть в intent или в сохранённое состояние, а не только в память). Изменения,
  которые применяются без перезагрузки (например, уровень лога), не должны её вызывать — перечень ключей берётся
  из реестра настроек.
- **Проверка исправления:** сценарий выше; unit-тест сравнения снимков для «изменено / не изменено».
