# Spec Delta

## ADDED Requirements

### Requirement: 简化界面代码与 upstream 隔离
简化界面的代码、资源与资产 MUST 存放在独立的 source set 目录 `app/src/simple/`。对来自 upstream（`throneproj/ThroneForAndroid`）的既有文件的修改 MUST 仅限于本变更 design.md 列出的接入点清单及 Crown 的 `FORK_IDENTITY_POINTS`；新增接入点 MUST 先更新相应清单。仓库 MUST 提供只读诊断脚本 `tools/diagnostics/upstream_touch_points.py`，比对 `git merge-base <ref> HEAD`（不计 upstream 之后的提交），列出 `app/src/simple/`、`openspec/`、`tools/diagnostics/` 与 Crown 分叉文件之外被修改的文件，并在出现清单外文件时以非零退出码结束。`app/src/test/` 下仅允许新增文件；修改或删除 upstream 基线中已存在的测试文件 MUST 视为清单外修改。

#### Scenario: 仅清单内文件被修改
- **GIVEN** 已拉取 upstream 引用
- **WHEN** 从仓库根目录运行 `uv run tools/diagnostics/upstream_touch_points.py --base upstream/main`
- **THEN** 脚本只列出接入点清单与 `FORK_IDENTITY_POINTS` 内的文件，退出码为 0

#### Scenario: 修改 upstream 既有测试文件
- **GIVEN** 某次改动修改了 upstream 基线中已存在的 `app/src/test/java/io/nekohasekai/sagernet/ktx/WrappedHostResolverTest.kt`
- **WHEN** 运行 `uv run tools/diagnostics/upstream_touch_points.py --base upstream/main`
- **THEN** 脚本列出该文件并以非零退出码结束；仅新增的测试文件不会被列出

#### Scenario: 出现清单外修改
- **GIVEN** 某次改动修改了接入点清单与 `FORK_IDENTITY_POINTS` 之外的 upstream 文件
- **WHEN** 运行同一脚本
- **THEN** 脚本列出该文件并以非零退出码结束

### Requirement: Проверка обновлений Crown по умолчанию
В сборках со встроенным механизмом обновления ежедневная проверка обновлений (`updateCheckAuto`) MUST быть включена, пока пользователь её не выключил. Поиск pre-release (`allow_beta_update`) MUST быть включён по умолчанию в preview-сборке (`v${VERSION_NAME}-pre.${N}`, `BuildConfig.PREVIEW`) и выключен в стабильной; значение, сохранённое пользователем, MUST оставаться в силе. Правки upstream ради этих значений по умолчанию MUST ограничиваться точками `FORK_IDENTITY_POINTS`.

#### Scenario: Новая установка preview
- **WHEN** на устройство впервые установлена preview-сборка, и переключатели обновлений не трогали
- **THEN** ежедневная проверка запланирована, и в классических, и в расширенных настройках оба переключателя включены, а проверка обновлений идёт по каналу «стабильные и pre-release»

#### Scenario: Стабильная сборка
- **WHEN** установлена стабильная сборка, и переключатели обновлений не трогали
- **THEN** ежедневная проверка включена, а pre-release не предлагаются

#### Scenario: Выбор пользователя
- **WHEN** пользователь выключил ежедневную проверку или beta-версии
- **THEN** после перезапуска и обновления приложения значение остаётся выключенным

### Requirement: Ссылки crown://
Crown MUST принимать ссылки `crown://`, открытые в браузере или другом приложении, из буфера обмена и QR-кода в обоих интерфейсах. Подписку MUST добавлять `crown://addsub?url=<URL>&name=<имя>` (некодированный URL берётся как есть), `crown://addsub/<URL>[#имя]` и `crown://install-config?url=…`; остальные `crown://<команда>/…` MUST работать как `throne://<команда>/…`. Правки upstream MUST ограничиваться точками D2 и `FORK_IDENTITY_POINTS`.

#### Scenario: Подписка со страницы панели
- **WHEN** на странице подписки нажата ссылка `crown://addsub?url=https://sub.example.com/abc&name=My%20VPN`
- **THEN** открывается Crown и добавляет подписку `https://sub.example.com/abc` с именем «My VPN» после подтверждения «Добавить подписку?» в обоих интерфейсах

#### Scenario: Список ссылок crown://
- **WHEN** из буфера обмена вставлены две строки `crown://add/<base64>`
- **THEN** Crown добавляет оба сервера, как из двух строк `throne://add/<base64>`

#### Scenario: Ссылка с переводом строки
- **WHEN** другое приложение открывает Crown ссылкой, в данных которой есть перевод строки (`crown://addsub?url=…` и на следующей строке `vless://…`)
- **THEN** Crown её игнорирует: такая ссылка не попадает в импорт текста мимо подтверждения

#### Scenario: Некодированный URL со своими параметрами
- **WHEN** открыта ссылка `crown://addsub?url=https://a.b/s?token=a%26c&b=1&name=n`
- **THEN** Crown добавляет подписку `https://a.b/s?token=a%26c&b=1` с именем «n»: `&b=1` не теряется, `%26` не декодируется

#### Scenario: Ссылка Throne под схемой Crown
- **WHEN** открыта ссылка `crown://add/<base64>` или `crown://route/…`
- **THEN** Crown обрабатывает её так же, как `throne://add/<base64>` или `throne://route/…`
