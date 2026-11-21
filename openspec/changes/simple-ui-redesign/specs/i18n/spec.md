# Spec Delta

## MODIFIED Requirements

### Requirement: 基准资源作为翻译唯一来源
基准资源 `app/src/main/res/values/strings.xml` 与 `app/src/main/res/values/arrays.xml` MUST 作为全部翻译的唯一来源；标记 `translatable="false"` 的条目（当前为 24 条 `<string>` 与 52 个 `<string-array>`）MUST NOT 出现在任何语言目录中。判定 MUST 按条目类型分别对照：语言目录 `strings.xml` 中的 `<string>`/`<plurals>` 仅与基准 `strings.xml` 的同类型条目比对，语言目录 `arrays.xml` 中的 `<string-array>` 仅与基准 `arrays.xml` 的同类型条目比对（基准中存在 `<string>` 与 `<string-array>` 同名但可翻译性不同的条目，如 `log_level`）。本变更 MUST NOT 修改基准资源的文案内容，也 MUST NOT 新增或删除任何现有语言目录（`values-*`）。

#### Scenario: 基准与语言集合保持稳定
- **WHEN** 本变更完成后比对基准 `values/strings.xml`、`values/arrays.xml` 的内容与变更前一致，且 `values-*` 语言目录集合与变更前一致
- **THEN** 基准文案与语言集合未被改动，符合要求

#### Scenario: 不可翻译条目不泄漏到语言目录
- **WHEN** 检查任一 `values-*/strings.xml` 或 `values-*/arrays.xml` 中的条目，按其类型与基准 `strings.xml`（`<string>`/`<plurals>`）或基准 `arrays.xml`（`<string-array>`）中的同类型条目比对
- **THEN** 语言目录的 `<string>`/`<plurals>` 不包含基准 `strings.xml` 中标记 `translatable="false"` 的名字，语言目录的 `<string-array>` 不包含基准 `arrays.xml` 中标记 `translatable="false"` 的名字

#### Scenario: 简化界面字符串不计入经典基准
- **GIVEN** 简化界面字符串资源 `app/src/simple/res/values/strings_simple.xml` 是独立基准，由「简化界面字符串资源」requirement 单独约束
- **WHEN** 对任一语言目录统计经典基准条目覆盖情况
- **THEN** `strings_simple.xml` 中的条目不参与该统计，既不算缺失也不算多余

### Requirement: 覆盖率诊断脚本可静态校验
仓库 MUST 提供维护用诊断脚本 `tools/diagnostics/i18n_coverage.py`，从仓库根目录经 `uv run tools/diagnostics/i18n_coverage.py` 执行。脚本 MUST 输出每个语言目录相对基准的缺失条目、多余条目与占位符不匹配项；当存在任一缺失、多余或占位符不匹配时 MUST 以非零退出码结束，全部通过时 MUST 以 0 退出。脚本 MUST 只读取资源文件，不修改任何文件。脚本 MUST 同时按「简化界面字符串资源」requirement 校验 `app/src/simple/res` 下的 `strings_simple.xml`，并以相同的退出码规则报告结果。

#### Scenario: 覆盖率全部达标时通过
- **GIVEN** 所有语言目录已补齐且占位符一致
- **WHEN** 从仓库根目录运行 `uv run tools/diagnostics/i18n_coverage.py`
- **THEN** 脚本输出各语言 0 缺失、0 多余、0 占位符不匹配，退出码为 0

#### Scenario: 存在缺失时失败
- **GIVEN** 某语言目录缺少一条基准可翻译字符串
- **WHEN** 运行 `uv run tools/diagnostics/i18n_coverage.py`
- **THEN** 脚本列出该缺失条目与所属语言，且退出码非 0

#### Scenario: 简化界面俄语翻译缺失时失败
- **GIVEN** `values-ru/strings_simple.xml` 缺少基准 `strings_simple.xml` 中的一条可翻译字符串
- **WHEN** 运行 `uv run tools/diagnostics/i18n_coverage.py`
- **THEN** 脚本列出该缺失条目，且退出码非 0

## ADDED Requirements

### Requirement: 简化界面字符串资源
简化界面的全部可见文案 MUST 存放在 `app/src/simple/res/values/strings_simple.xml`（英文基准），并 MUST 仅提供俄语翻译 `app/src/simple/res/values-ru/strings_simple.xml`。俄语翻译 MUST 覆盖基准中全部可翻译条目，且 MUST 遵守「翻译保持格式契约」requirement。其他语言目录 MUST NOT 包含 `strings_simple.xml`，这些语言下简化界面以英文基准显示。

#### Scenario: 俄语翻译完整
- **WHEN** 比对 `values-ru/strings_simple.xml` 与基准 `strings_simple.xml` 的可翻译条目名称集合
- **THEN** 两者完全一致，且占位符一致

#### Scenario: 其他语言回退英文
- **WHEN** 检查 `app/src/simple/res` 下除 `values` 与 `values-ru` 外的语言目录
- **THEN** 不存在 `strings_simple.xml`
