# Proposal

> **Категория:** скорость · **Приоритет:** средний · **Проверка:** воспроизведено на эмуляторе · **Код:** Crown (точка правки 5 и
> `SimpleUi`), вариант Б — изменение решения D3 · **Объём:** S (вариант А), M (вариант Б)

## Why

### Находка
Лаунчер всегда открывает `MainActivity` (`AndroidManifest.xml:77-86`, `android:launchMode="singleTask"`), а она по решению D3
(`openspec/changes/simple-ui-redesign/design.md`) в начале `onCreate` перенаправляет в `SimpleActivity`:

```kotlin
// app/src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt:86-88
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (SimpleUi.redirect(this)) return
```

После первого перенаправления в задаче остаётся только `SimpleActivity`. Когда пользователь сворачивает приложение и снова
нажимает иконку, система для `singleTask`-активности ищет её экземпляр в задаче, не находит и **создаёт новый** `MainActivity`
поверх `SimpleActivity`. Каждый такой экземпляр проходит:
1. `ThemedActivity.onCreate` (`ui/ThemedActivity.kt:38-47`): `Theme.apply`, `Theme.applyNightTheme` — чтения настроек из Room на
   главном потоке, `enableEdgeToEdge`, `AppCompatActivity.onCreate`;
2. `SimpleUi.redirect` (`simple/SimpleUi.kt:112-127`) → `recordInstallKind` → `packageManager.getPackageInfo(...)` — binder-вызов
   **на каждом запуске** (`SimpleUi.kt:142-148`), хотя результат уже сохранён: `isUpgrade(store, …)` сразу возвращает
   сохранённый `KEY_INSTALL_KIND` (`SimpleUi.kt:80-85`), но `getPackageInfo` вызывается до этой проверки;
3. `startActivity(SimpleActivity)` → `finish()` → `SimpleActivity.onNewIntent`.

### Влияние
Каждое открытие приложения иконкой для системы — тёплый запуск (создаётся новое окно), а не горячий, плюс лишняя activity с темой,
AppCompat, SQL и двумя синхронными binder-вызовами на главном потоке.

Замеры (эмулятор Android 17, release-сборка, `am start -W -a MAIN -c LAUNCHER` после Home, 10 повторов):

| Интерфейс | LaunchState | Медиана TotalTime | min–max |
|---|---|---|---|
| Простой | **WARM** (всегда) | 1061 мс | 809–1369 мс |
| Классический (`MainActivity` — настоящий экран) | **HOT** | 894 мс | 359–1842 мс (последние 5: 359–550 мс) |

Журнал событий одного «горячего» запуска simple UI:
```
wm_create_activity: […,dev.folki.crown/io.nekohasekai.sagernet.ui.MainActivity,android.intent.action.MAIN,…]
wm_new_intent:      […,io.nekohasekai.sagernet.simple.SimpleActivity,…]
wm_on_create_called: [559669,io.nekohasekai.sagernet.ui.MainActivity,performCreate,163]   ← 163 мс в onCreate трамплина
wm_activity_launch_time: […,SimpleActivity,776]
wm_destroy_activity: […,MainActivity,finish-imm:idle]
```
Холодный старт (atrace, пустая БД): `activityStart` трамплина — 470 мс, из них `performCreate:MainActivity` 212 мс (135 мс сна в
binder-вызовах system_server); только потом начинается `SimpleActivity`. В выбросе под нагрузкой (3000 серверов) трамплин занял
4,76 с — `getPackageInfo`/`startActivity` ждали перегруженный system_server 0,98 и 3,3 с.

### Как воспроизвести
1. Release-сборка, simple UI, приложение открыто.
2. `adb shell input keyevent KEYCODE_HOME`.
3. `adb logcat -b events -c; adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n dev.folki.crown/io.nekohasekai.sagernet.ui.MainActivity`.
4. Вывод: `LaunchState: WARM` (ожидалось HOT); `adb logcat -b events -d | grep -E "wm_(create|destroy)_activity|wm_on_create_called|wm_new_intent"` —
   создаётся и уничтожается `MainActivity`.
5. Контроль: в классическом интерфейсе те же шаги дают `LaunchState: HOT` без `wm_create_activity`.

## What Changes
**Вариант А (рекомендуется первым: безопасен, в рамках D3 и существующих точек правки).** Сделать трамплин дешёвым:
1. В `SimpleUi.recordInstallKind` сначала читать сохранённый `KEY_INSTALL_KIND` и вызывать `getPackageInfo` только при его
   отсутствии (один раз на установку) — убирает binder-вызов с каждого запуска.
2. Чтения темы на пути трамплина сделать дешёвыми — общая мера с `move-startup-work-off-main-thread` (зеркало ключей оформления
   вне Room).
3. При перенаправлении не делать работу, которая нужна только видимому окну (`enableEdgeToEdge`, применение AMOLED-оверлея):
   это требует флага в `ThemedActivity` (upstream) — либо принять как остаток.

Ожидаемо: `wm_on_create_called … MainActivity` < 50 мс вместо 163; LaunchState останется WARM.

**Вариант Б (полное решение; требует пересмотра D3).** Отдельная лёгкая точка входа только для фильтров `LAUNCHER`/
`LEANBACK_LAUNCHER` со стандартным режимом запуска и темой без окна (`Theme.NoDisplay`), которая открывает выбранный интерфейс.
При живой задаче лаунчер-intent совпадает с корневым intent задачи, и система просто поднимает её — запуск становится горячим.
Уведомление, плитка и ярлыки продолжают вести в `MainActivity` с перенаправлением, поэтому возражение D3 («уведомление, плитка и
ярлыки открывали бы `MainActivity` напрямую») снимается: меняется только фильтр лаунчера. Нужно проверить: иконки на рабочем
столе, созданные для компонента `MainActivity` (часть лаунчеров удаляет иконку, когда у компонента пропадает фильтр `LAUNCHER`),
ТВ-лаунчер, восстановление из «Недавних», выбор классического интерфейса (точка входа открывает `MainActivity` без редиректа).

Проверка: шаги 2–4 по 10 повторов — медиана TotalTime и LaunchState до/после; холодный старт — длительность `activityStart`
трамплина в atrace.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `simple-ui-redesign/specs/simple-ui`: для варианта Б — уточнение точки входа (лаунчер ведёт в лёгкую точку входа, прочие входы —
  в `MainActivity` с перенаправлением) и соответствующая правка `design.md` D3. Для варианта А требования не меняются.

## Impact
- **Код:** вариант А — `simple/SimpleUi.kt` (код Crown), при п.3 — `ui/ThemedActivity.kt`; вариант Б — `AndroidManifest.xml`
  (фильтры лаунчера), новая activity в `app/src/simple`.
- **Upstream:** вариант А — код Crown (п.1), точка правки 5 `MainActivity.kt` не меняется; п.3 — новая точка правки
  `ThemedActivity.kt`. Вариант Б — точка правки 3 (манифест) с расширенным описанием и правка таблицы D2.
- **Ядро throne, сборка:** не затрагиваются.
- **Риски:** вариант А — почти нет (флаг установки уже хранится). Вариант Б — пропавшие иконки на рабочем столе у части лаунчеров
  после обновления, иное поведение «Недавних»; проверять на нескольких лаунчерах и ТВ.
- **Проверка исправления:** замеры выше; ручной обход всех входов (иконка, ТВ, уведомление, плитка, ярлыки, ссылки, «Поделиться»).
- **Внешние источники:**
  - Режимы запуска и поведение задач (`singleTask`, повторный запуск из лаунчера):
    https://developer.android.com/guide/components/activities/tasks-and-back-stack
  - Типы запуска (cold/warm/hot): https://developer.android.com/topic/performance/vitals/launch-time
