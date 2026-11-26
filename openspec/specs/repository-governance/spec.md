# Repository Governance Specification

## Purpose

定义 ThroneForAndroid 的仓库边界、规范来源、构建协作方式、版本与核心来源以及发布工作流，避免项目知识和维护约定随人员或大版本更替再次散落。

## Requirements

### Requirement: OpenSpec 是当前规范的唯一入口

仓库当前有效的架构、功能约束和协作规则 MUST 维护在 `openspec/specs/` 的主规范中。新增或修改项目结构、关键模块、协议、配置、构建流程或维护工具布局时，实施改动 MUST 同步更新受影响的主规范。已完成的大型迁移记录 MAY 保存在 `openspec/history/`，但 MUST NOT 被当作尚待执行的 change。

#### Scenario: 架构改动同步规范

- **WHEN** 一次实现改变了核心接驳、Android 关键模块、构建链或工具目录
- **THEN** 对应的 `openspec/specs/` 主规范已同步反映新状态
- **AND** 历史记录仅承担背景和决策追溯职责

### Requirement: 仓库目录职责明确

仓库 MUST 按以下边界组织：`app/` 承载 Android 应用，`buildScript/` 与 `buildSrc/` 承载构建辅助逻辑，`gradle/` 承载 Gradle Wrapper，`.github/` 承载 GitHub 自动化（可复用的构建步骤以 `actions/` 下的复合动作组织），`openspec/` 仅承载 OpenSpec 配置、规范、变更和历史资料，`tools/diagnostics/` 承载仓库维护用诊断脚本。OpenSpec 运行目录中 MUST NOT 放置项目诊断程序或其运行产物。

#### Scenario: 添加维护工具

- **WHEN** 一次性调研、格式解析或静态校验程序需要纳入仓库供后续复用
- **THEN** 程序被放入 `tools/diagnostics/`
- **AND** `.roo/` 与 `openspec/` 中没有新增该程序

### Requirement: Python 工具使用 uv 文件工作流

仓库维护用 Python 代码 MUST 先写入 `tools/diagnostics/` 下的 `.py` 文件，再从仓库根目录使用 `uv run tools/diagnostics/<script>.py [arguments]` 执行。维护流程 MUST NOT 使用 `python`、`python3` 或 `python -c` 直接执行项目辅助代码。脚本若需要定位仓库根目录，MUST 从脚本路径推导（例如 `Path(__file__).resolve().parents[2]`）。

#### Scenario: 执行临时分析

- **WHEN** 需要用 Python 扫描或转换仓库内容
- **THEN** 完整逻辑先保存为 `tools/diagnostics/` 下的脚本并使用 `uv run` 执行
- **AND** 不通过命令行内联 Python 代码

### Requirement: 本地开发不依赖 Go 或 Android 编译环境

开发流程 MUST 假定本地未安装 Go 环境且未克隆核心源码。AI/开发者 MUST NOT 要求用户在本地编译核心 AAR 或 Android APK；可执行不依赖这些环境的静态校验。核心 AAR、Android APK 和真机行为 MUST 由 GitHub Actions 与用户真机验证。外部依赖的源码、版本和 API 定义 SHOULD 通过官方网络来源查询。

#### Scenario: 验证跨核心与 Android 的修改

- **WHEN** 一次修改影响核心或 Android 编译
- **THEN** 本地只运行可用的静态检查
- **AND** 最终编译交由 GitHub Actions，运行时行为交由真机验证

### Requirement: 核心由 CI 按 THRONE_CORE_REF 构建并缓存

throne 核心 MUST 以 `nb4a.properties` 的 `THRONE_CORE_REF` 为唯一真实来源。CI MUST 从该 ref 检出 `throneproj/Throne` 构建 `ThroneCore.aar` 并生成 Android 构建标签一致的 sing-box 选项 schema，两者 MUST 以解析后的核心 commit 与核心构建脚本哈希为缓存键。核心 ref 或构建脚本变化 MUST 使缓存失效。

#### Scenario: 升级核心 ref

- **WHEN** 维护者修改 `nb4a.properties` 的 `THRONE_CORE_REF` 并触发构建
- **THEN** CI 检出并构建对应核心 commit，重新生成 AAR 与 schema
- **AND** 旧缓存不再被复用

### Requirement: 外部仓库由用户提供明确路径

开发过程中若需要参考外部仓库的本地副本，AI/开发者 MUST 先告知用户所需仓库及参考目的，由用户提供具体路径。AI/开发者 MUST NOT 自行遍历、搜索或猜测用户机器上的其他项目目录，也 MUST NOT 将其中未获用户明确指定的项目视为可用参考资料。

#### Scenario: 实现需要参考外部仓库

- **WHEN** 仅凭本仓库内容和官方网络来源无法可靠完成实现
- **THEN** 先向用户说明所需仓库及参考目的并等待其提供具体路径
- **AND** 仅访问用户明确提供的路径

### Requirement: Идентичность форка Crown

Форк MUST собираться как отдельное приложение Crown: идентификатор `PACKAGE_NAME`, версия `VERSION_NAME`, репозиторий обновлений `UPDATE_REPOSITORY`, файл истории изменений `CHANGELOG` и префикс имён APK `ARTIFACT_NAME` MUST браться из `fork.properties`. `nb4a.properties` MUST оставаться совпадающим с upstream (`THRONE_CORE_REF` по-прежнему задаёт ядро, его `VERSION_NAME` — версия Throne, на которой основана сборка); MUST NOT вносить в него правки форка. Название приложения («Crown», в русской локали «Корона») MUST задаваться ресурсами `app/src/oss/res/`, не правкой `strings.xml` upstream. Значки Crown (значок приложения, значок плитки быстрых настроек и строки состояния, баннер Android TV) MUST задаваться там же ресурсами с теми же именами, не заменой файлов upstream в `app/src/main/res/`. Отображаемая версия MUST содержать версию Throne (`1.0.0 (Throne 2.0.7)`), а User-Agent запросов по умолчанию MUST иметь вид `Crown/Android/<версия Throne>` (`Crown/Android/2.0.7`): название форка, но версия Throne, а не форка. Правки файлов upstream ради идентичности форка MUST ограничиваться перечнем `FORK_IDENTITY_POINTS` в `tools/diagnostics/upstream_touch_points.py`; новые файлы форка — `fork.properties`, `CROWN_CHANGELOG.md`, `CROWN.md`, `buildSrc/src/main/kotlin/Fork.kt`, `app/src/oss/res/`, `buildScript/release_plan.sh`, `.github/images/crown/`. `README.md` форка MUST описывать Crown (название, значок, интерфейс, возможности, установку), MUST указывать, что форк делается для определённой аудитории и не будет влит в оригинальный Throne, и MUST сохранять ссылки на оригинальный репозиторий, его авторов и способы их поддержать.

#### Scenario: User-Agent запросов

- **WHEN** Crown на основе Throne 2.0.7 запрашивает подписку, а пользователь не задал свой User-Agent
- **THEN** запрос уходит с заголовком `User-Agent: Crown/Android/2.0.7`

#### Scenario: Установка рядом с оригиналом

- **WHEN** на устройстве установлен оригинальный Throne (`com.nb4a.throne`)
- **THEN** Crown (`dev.folki.crown`) устанавливается отдельно, со своими данными и подписью

#### Scenario: Слияние upstream

- **WHEN** upstream меняет `VERSION_NAME`/`VERSION_CODE` в `nb4a.properties` и изменения сливаются в форк
- **THEN** слияние не конфликтует с версией форка, а отображаемая версия Throne обновляется
- **AND** `uv run tools/diagnostics/upstream_touch_points.py` не находит правок вне перечней

### Requirement: Версия приложения выводится из fork.properties

Версия MUST задаваться только `VERSION_NAME` (и номер preview — `RELEASE`) из `fork.properties` в виде `MAJOR.MINOR.PATCH` (MAJOR 0..20, MINOR и PATCH 0..99); сборка с другим значением MUST падать. `versionCode` MUST вычисляться как `((MAJOR·100 + MINOR)·100 + PATCH)·1000 + N`, где N = 999 для стабильного релиза, 1..998 для preview и 0 для локальной сборки, — так любая следующая версия и стабильный релиз старше всех preview своей версии без отдельно поддерживаемого `VERSION_CODE`. Tag, заголовок релиза и версия preview MUST выводиться из того же значения.

#### Scenario: Preview следующей версии старше предыдущего релиза

- **WHEN** выпущен `v1.0.0` (versionCode 10000999) и `VERSION_NAME` поднят до `1.0.1`
- **THEN** `1.0.1-pre.1` получает versionCode 10001001 и устанавливается поверх `1.0.0`

### Requirement: Релизы объявляются в git (GitOps)

Какой релиз выпускать, MUST объявляться только в `fork.properties`: `VERSION_NAME` и `RELEASE` — `stable` (релиз `v${VERSION_NAME}`, N = 999) или `pre.<N>` (pre-release `v${VERSION_NAME}-pre.<N>`, N = 1..998). Объявление MUST меняться pull request'ом в `main`; merge, не меняющий объявление, MUST NOT собирать и публиковать релиз или preview. Каждое новое объявление MUST поднимать `versionCode` относительно объявления в базовой ветке (`pre.<N+1>`, затем `stable`, затем более высокий `VERSION_NAME`). Тег, канал, номер сборки и `versionCode` MUST вычисляться из объявления одним скриптом `buildScript/release_plan.sh`.

`release.yml` MUST запускаться при push в `main`, меняющем `fork.properties`, и вручную. С ветки по умолчанию он MUST публиковать объявленный релиз, если его тега ещё нет, и ничего не делать (с уведомлением), если тег уже есть: повторный или ручной запуск MUST NOT перезаписывать опубликованный релиз. Ручной запуск с другой ветки MUST выкладывать только workflow artifact. `stable` MUST публиковаться как latest с заметками из раздела `#### ${VERSION_NAME}` файла `CHANGELOG` (при его отсутствии — список коммитов с предыдущего стабильного релиза); `pre.<N>` — как pre-release со списком коммитов с предыдущего релиза или preview. Объявление preview уже выпущенной версии MUST приводить к ошибке. Workflow MUST NOT сам коммитить в `main`: следующую версию объявляет человек. На GitHub MUST оставаться не более 10 последних pre-release (механизм обновления читает 20 последних релизов).

Обе сборки MUST подписываться ключом из секретов `SIGNING_*`, без них MUST падать; SHA-256 сертификата MUST закрепляться в `BuildConfig.UPDATE_SIGNER_SHA256`. Каждый релиз и preview MUST содержать APK по ABI, `SHA256SUMS` и манифест `throne-update.json`, по которому встроенный механизм обновления скачивает и проверяет APK того же пакета.

`ci.yml` MUST запускаться для pull request'ов и push в `main` (не для каждой ветки) и для каждого pull request MUST проверять объявление (`release_plan.sh --check` против базы): сообщать, что опубликует merge, и падать, если объявлен новый релиз, который нельзя опубликовать (тег уже есть, версия уже выпущена, `versionCode` не выше базового).

#### Scenario: Обычный merge в main

- **WHEN** в `main` сливается pull request, не меняющий `RELEASE` и `VERSION_NAME`
- **THEN** ни релиз, ни preview не собираются и не публикуются

#### Scenario: Выпуск preview

- **WHEN** в `main` сливается pull request, меняющий `RELEASE=pre.11` на `RELEASE=pre.12` при `VERSION_NAME=1.0.0`
- **THEN** публикуется pre-release `v1.0.0-pre.12` (versionCode 10000012) с манифестом, и приложения с включёнными бета-обновлениями предлагают его установить

#### Scenario: Стабильный релиз

- **WHEN** в `main` сливается pull request, объявляющий `RELEASE=stable`
- **THEN** публикуется `v${VERSION_NAME}` как latest, его получают и стабильный, и бета-канал
- **AND** в `main` не появляется автоматических коммитов; preview следующей версии начинаются с pull request'а, объявляющего `VERSION_NAME=<следующая>` и `RELEASE=pre.1`

#### Scenario: Недопустимое объявление

- **WHEN** pull request объявляет релиз, тег которого уже опубликован, preview уже выпущенной версии или релиз с `versionCode` не выше базового
- **THEN** проверка «Release plan» в CI падает с объяснением до merge
