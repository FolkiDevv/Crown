# Proposal

> **Категория:** надёжность · **Приоритет:** низкий · **Проверка:** воспроизведено на эмуляторе ·
> **Код:** upstream (вызывается из Crown) · **Объём:** S

## Why

### Находка
`ScannerActivity` — единственный сканер QR в обоих интерфейсах. Новый интерфейс открывает его из «Добавить
сервер» → «Scan QR code, With the camera or from a picture» (`simple/SimpleActivity.kt:504-511`), классический —
из меню импорта (`ui/profiles/ProfileImports.kt:72`), маршрутов (`ui/RouteFragment.kt:134`) и ярлыка
(`utils/AppShortcuts.kt:33`). При старте сканер сразу просит камеру и при любом отказе закрывается:

```kotlin
// app/src/main/java/io/nekohasekai/sagernet/ui/ScannerActivity.kt:209-217
fun requestCameraPermissionResult(permissions: Array<String>, grantResults: IntArray) {
    if (PermissionUtils.requestPermissionsResult(Manifest.permission.CAMERA, permissions, grantResults)) {
        startCamera()
    } else {
        finish()
    }
}
```

После второго отказа (Android 11+) он становится постоянным: система больше не показывает диалог, запрос сразу
возвращает отказ, и сканер открывается и тут же закрывается при каждом нажатии. Объяснения, тоста или ссылки в
настройки приложения нет. Кнопка «из галереи» (`ScannerActivity.kt:49-51`) живёт в той же activity, хотя камера ей
не нужна, — пользователь до неё не доходит. Путь «только изображения» есть лишь для устройств без камеры
(`ScannerActivity.kt:53-61`).

### Влияние
- Пользователь, который однажды дважды отказал в камере (часто случайно), больше не может добавить подписку по QR
  ни с камеры, ни из картинки, хотя пункт в новом интерфейсе прямо обещает «или из картинки». Кнопка «ничего не
  делает».
- QR — один из четырёх способов добавления, которые требует `simple-ui-redesign` «Универсальное добавление
  серверов». Обходы есть (буфер, ссылка, файл), поэтому приоритет низкий.

### Как воспроизвести
Debug-сборка `dev.folki.crown.debug`, эмулятор Android 17 (API 37).

1. Постоянный отказ в камере:
   `adb shell pm revoke dev.folki.crown.debug android.permission.CAMERA` и
   `adb shell pm set-permission-flags dev.folki.crown.debug android.permission.CAMERA user-set user-fixed`
   (то же, что дважды нажать «Не разрешать»).
2. Новый интерфейс → «Servers» → «Add server» → «Scan QR code, With the camera or from a picture».
3. Журнал событий:
   ```
   05:33:14.600 wm_create_activity: [… ScannerActivity …]
   05:33:15.476 wm_finish_activity: [… ScannerActivity,app-request]
   ```
   Через 0,9 с пользователь снова на списке серверов; ни тоста, ни диалога.

## What Changes
1. При отказе не закрывать сканер, а показать на его экране объяснение и две кнопки:
   - «Выбрать изображение» — существующий импорт QR из картинки (`importCodeFile`), камера не нужна;
   - «Открыть настройки» — если отказ постоянный (`shouldShowRequestPermissionRationale` = false), через
     `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`; при возврате (`onResume`) проверять разрешение снова и, если оно
     есть, запускать камеру.
2. Минимальная мера: тост «Нет доступа к камере» и сразу выбор изображения — как на устройствах без камеры.
3. Проверка «до/после»: шаги 1–2 оставляют пользователя на экране сканера с объяснением; «Выбрать изображение»
   импортирует QR из картинки; после выдачи разрешения в настройках камера включается без перезапуска.

## Capabilities

### New Capabilities
(нет)

### Modified Capabilities
- `simple-ui` (в `openspec/changes/simple-ui-redesign/specs/simple-ui`): в «Универсальное добавление серверов»
  добавить сценарий: без разрешения на камеру добавление по QR остаётся доступно через картинку, а пользователь
  видит, как вернуть доступ к камере.

## Impact
- **Код:** `app/src/main/java/io/nekohasekai/sagernet/ui/ScannerActivity.kt` (и его разметка `layout_scanner`).
- **Upstream:** файл upstream, точки правки форка нет (`tools/diagnostics/upstream_touch_points.py`) — патч в upstream
  или новая точка правки. Альтернатива только в коде Crown: новый интерфейс сам проверяет разрешение до запуска
  сканера и при постоянном отказе предлагает выбор картинки; classic тогда остаётся как есть.
- **Ядро throne, сборка, инструменты:** не затрагиваются.
- **Риски:** почти нет; нужно не зациклить запрос разрешения в `onResume`.
- **Проверка исправления:** сценарий выше; поворот экрана на экране объяснения.
- **Внешние источники:** «Request runtime permissions»
  (https://developer.android.com/training/permissions/requesting — поведение после повторного отказа на Android 11+).
