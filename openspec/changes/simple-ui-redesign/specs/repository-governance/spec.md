# Spec Delta

## ADDED Requirements

### Requirement: 简化界面代码与 upstream 隔离
简化界面的代码、资源与资产 MUST 存放在独立的 source set 目录 `app/src/simple/`。对来自 upstream（`throneproj/ThroneForAndroid`）的既有文件的修改 MUST 仅限于本变更 design.md 列出的接入点清单；新增接入点 MUST 先更新该清单。仓库 MUST 提供只读诊断脚本 `tools/diagnostics/upstream_touch_points.py`，比对指定的 upstream ref，列出 `app/src/simple/`、`app/src/test/`、`openspec/`、`tools/diagnostics/` 之外被修改的文件，并在出现清单外文件时以非零退出码结束。

#### Scenario: 仅清单内文件被修改
- **GIVEN** 已拉取 upstream 引用
- **WHEN** 从仓库根目录运行 `uv run tools/diagnostics/upstream_touch_points.py --base upstream/main`
- **THEN** 脚本只列出接入点清单内的文件，退出码为 0

#### Scenario: 出现清单外修改
- **GIVEN** 某次改动修改了接入点清单之外的 upstream 文件
- **WHEN** 运行同一脚本
- **THEN** 脚本列出该文件并以非零退出码结束
