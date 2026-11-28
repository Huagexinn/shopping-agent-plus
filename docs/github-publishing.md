# GitHub 仓库整理与发布步骤

本文记录本仓库的整理方式与发布检查步骤。修改完成后应先核对实际暂存内容，再由维护者确认上传；保存本文不会自动 commit 或 push。

## 内容组织

| 内容 | 唯一存放位置 | 展示方式 |
|---|---|---|
| 三项自主改造 | README.md | 原有问题 → 解决方法 → 实测效果 → 代码入口 |
| 来源与归属 | docs/attribution.md | 上游链接、原作者、继承能力和个人增量 |
| 架构 | docs/architecture.md | Mermaid 整体图、恢复/确认时序、评测链路 |
| 数据库迁移 | project-backend/src/main/resources/db/migration/ | 保留 V1–V7，明确 V8–V11 为改造增量 |
| 运行与测试命令 | docs/running.md | 工程验收与完整商城分开说明 |
| 实验结论 | docs/experiments.md | 日期、套件数量、指标范围和证据链接 |
| 原始归档报告 | project-backend/verification/ | 保留历史结果，不改写数字或源码摘要 |
| 详细旧记录 | docs/history/implementation-notes.md | 标注阶段性和过时配置，避免干扰首页 |

不将整个商城、首次工具调用、首次用户确认等已有能力写成本人从零实现。仓库简介可写：“基于 shopping-agent-ai 的工程增强：任务追踪与恢复、持久化订单确认闭环、可复现业务完整性评测。”

## 本地检查

在仓库根目录查看候选内容：

```powershell
git status --short
git diff --stat
git diff --check
git ls-files --others --exclude-standard
git check-ignore milvus/volumes/probe.txt project-backend/target/probe.txt project-frontend/.env
```

应包含代码、V8–V11、测试、脚本、示例配置与文档；不应包含 API Key、业务数据库备份、聊天日志、`.idea`、`target`、`node_modules`、`dist` 或 `milvus/volumes`。根 `.gitignore` 已补充本地卷数据和私有验证目录规则。

`.gitignore` 不会自动取消已有跟踪。对已经跟踪的 `.DS_Store`，可用 `git rm --cached -- .DS_Store` 仅取消索引跟踪、保留本地文件。也不要把忽略规则误认为历史中的敏感内容已经删除。

公开前复核源代码、历史提交、归档报告和截图。报告含本机目录与工具路径，属于来源信息；如需隐藏个人路径，制作明确标注的公开副本并保留原始报告，不要悄悄篡改证据。有限的文本扫描不能替代完整凭据审查。

## 建议的提交组织

工作树已经包含三项改造且存在共享文件，不建议为了好看的历史强拆出无法编译的提交。可以先形成一个可运行的功能整合提交，再提交文档；若逐项拆分，需人工按代码块暂存并分别验证。

建议提交说明：

1. `feat: add task recovery and persistent order confirmation`：任务记录、业务幂等、受控恢复、确认闭环、V8–V11、配套前后端与回归。
2. `test: add reproducible agent business evaluation`：场景、判定器、负对照、脚本与归档报告，以及评测发现的回复判定修复；有共享改动时与第一项合并成一个可运行提交。
3. `docs: document fork contributions and experiment evidence`：README、来源、架构、运行、迁移、实验索引及公开仓库忽略规则。

不要伪造开发日期或将当前本地 HEAD 当成上游原始 Fork 点。上游未见独立 LICENSE 文件的情况已记录在来源说明，本次不擅自给整仓添加新许可证。

## 确认后再执行的发布步骤

先确认来源/贡献表述、实验范围、示例配置和候选提交内容，再处理 Git 提交与上传。当前 origin 为 `https://github.com/Huagexinn/shopping-agent-plus.git`；上游为 `https://github.com/xinconner/shopping-agent-ai.git`。

当前仓库默认分支为 `github-clean`。后续独立改动可创建 `codex/document-fork-improvements` 等工作分支；检查 `git remote -v`，需要时添加名为 upstream 的原项目地址。添加 upstream 并不意味着要向原作者推送；成果应推送自己的 origin 分支。推送前核对远程提交，普通推送被拒绝时应先检查远程变化。

上传前检查 `git diff --cached --name-status`、`git diff --cached --check` 和实际暂存差异。确认后用 `git push -u origin <已确认的分支名>` 推送；不要使用 force push 或覆盖原作者的历史。

发布后的首页应能依次回答：项目基于谁、本人改了什么、为什么这样设计、怎样运行、结果如何验证、哪些能力尚未测量。
