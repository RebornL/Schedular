# Issue tracker：GitHub

本仓库的 issue 与 spec 统一放在 GitHub Issues 中，所有操作使用 `gh` CLI。

## 约定

- **创建 issue**：`gh issue create --title "..." --body "..."`，多行 body 用 heredoc。
- **读取 issue**：`gh issue view <number> --json number,title,body,labels,comments`。
- **列出 issue**：`gh issue list --state open --json number,title,body,labels,comments --jq '[.[] | {number, title, body, labels: [.labels[].name], comments: [.comments[].body]}]'`，按需加 `--label`、`--state` 过滤。
- **把 issue 挂为父 issue 的子 issue**：`gh issue create --parent <parent> ...`，或事后 `gh issue edit <parent> --add-sub-issue <child>`（gh 2.94+）。旧版 gh：`gh api --method POST repos/<owner>/<repo>/issues/<parent>/sub_issues -F sub_issue_id=<child-db-id>`（database id，见下方"阻塞关系"）。不支持子 issue 时，在子 issue body 顶部写 `Part of #<parent>`。
- **阻塞关系**：使用 GitHub 原生 issue dependencies（UI 可见的标准表示）。建边：`gh issue create --blocked-by <n>,<n> ...` 或 `gh issue edit <child> --add-blocked-by <blocker>`（gh 2.94+）。旧版 gh：`gh api --method POST repos/<owner>/<repo>/issues/<child>/dependencies/blocked_by -F issue_id=<blocker-db-id>`，其中 `<blocker-db-id>` 是 blocker 的数字 **database id**（`gh api repos/<owner>/<repo>/issues/<n> --jq .id`，不是 `#编号` 也不是 `node_id`）。依赖不可用时，退回在子 issue body 顶部写 `Blocked by: #<n>, #<n>`。所有 blocker 关闭后，ticket 才算解除阻塞。
- **统计未解除的 blocker 数**：`gh api repos/<owner>/<repo>/issues/<n> --jq .issue_dependencies_summary.blocked_by`（仅统计 open blocker；0 表示可开工）。`gh issue view <n> --json blockedBy` 也会列出已关闭的 blocker，需过滤：`--jq '[.blockedBy.nodes[] | select(.state == "OPEN")] | length'`。
- **评论**：`gh issue comment <number> --body "..."`
- **加/移除标签**：`gh issue edit <number> --add-label "..."` / `--remove-label "..."`
- **关闭**：先用 `gh issue comment <number> --body "..."` 说明原因，再 `gh issue close <number>`。对已关闭的 issue 执行 `gh issue close --comment` 会丢弃评论且仍以 0 退出。

仓库从 `git remote -v` 自动推断；在本仓库目录内运行时 `gh` 会自动识别。

Issue 只登记到用户自己的仓库。不要往 `mattpocock/skills`（这些技能的来源仓库）提 issue，除非用户拥有它。在 fork 中 `gh` 可能默认指向上游，因此每次会话第一次 `gh issue create` 前，先用 `gh repo view --json nameWithOwner` 确认目标；若不是用户的仓库，先向用户确认。

## PR 作为 triage 入口

**将外部 PR 纳入 triage：否。** _（若本仓库把外部 PR 当作功能请求来源，改为 `yes`；`/triage` 会读取此开关。）_

设为 `yes` 时，PR 走与 issue 相同的标签和状态，使用对应的 `gh pr` 命令：

- **读取 PR**：`gh pr view <number>`，再看 `gh pr view <number> --comments`（非 TTY 时 `--comments` 只打印评论、不含正文），diff 用 `gh pr diff <number>`。
- **列出待 triage 的外部 PR**：`gh api --paginate 'repos/{owner}/{repo}/pulls?state=open' --jq '.[] | select(.author_association | IN("OWNER","MEMBER","COLLABORATOR") | not) | {number, title, author: .user.login, author_association, labels: [.labels[].name]}'`。
- **评论/标签/关闭**：`gh pr comment`、`gh pr edit --add-label`/`--remove-label`、`gh pr close`。

GitHub 上 issue 与 PR 共用一套编号，裸 `#42` 可能是其中任一者：先 `gh pr view 42`，查不到再 `gh issue view 42`。

## 当技能说"发布到 issue tracker"

创建 GitHub issue。

## 当技能说"获取相关 ticket"

按上方"读取 issue"操作。

## Wayfinding 操作

供 `/wayfinder` 使用。**地图（map）**是一个 issue，**子 ticket** 是挂在其下的子 issue。

- **地图**：单个打了 `wayfinder:map` 标签的 issue，正文承载 Notes / Decisions-so-far / Fog。`gh issue create --label wayfinder:map`。
- **子 ticket**：以子 issue 形式挂到地图下（见"把 issue 挂为父 issue 的子 issue"）。子 issue 不可用时，加入地图正文的任务列表，并在子 issue body 顶部写 `Part of #<map>`。标签：`wayfinder:<type>`（`research`/`prototype`/`grilling`/`task`）。被认领后，ticket 指派给推进的开发者。
- **阻塞**：见"约定"下的"阻塞关系"。
- **边界查询（frontier）**：列出地图下 open 的子 issue，剔除有未解除 blocker（见"统计未解除的 blocker 数"，或 `Blocked by` 行中存在 open issue）或已有 assignee 的；按地图顺序取第一个。
- **认领**：`gh issue edit <n> --add-assignee @me`，作为会话的第一次写操作。
- **解决**：`gh issue comment <n> --body "<答案>"`，然后 `gh issue close <n>`，最后把上下文指针（要点 + 链接）追加到地图的 Decisions-so-far。
