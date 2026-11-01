# Domain 文档

工程类技能在探索代码库时应如何使用本仓库的 domain 文档。

## 探索前先读

- 仓库根目录的 **`GLOSSARY.md`**，或
- 若根目录存在 **`GLOSSARY-MAP.md`**：它指向每个 context 各自的 `GLOSSARY.md`，读取与当前主题相关的那些。
- **`docs/adr/`**：阅读与即将改动的区域相关的 ADR。多 context 仓库还需查看 `src/<context>/docs/adr/` 下的 context 级决策。

这些文件尚不存在时**静默继续**：不要提示缺失，也不要提前建议创建。`/domain-modeling` 技能（经由 `/grill-with-docs` 与 `/improve-codebase-architecture` 进入）会在术语或决策实际敲定时惰性创建它们。

## 文件结构

本仓库为单 context 布局：

```
/
├── GLOSSARY.md
├── docs/adr/
│   ├── 0001-event-sourced-orders.md
│   └── 0002-postgres-for-write-model.md
└── src/
```

多 context 布局（根目录出现 `GLOSSARY-MAP.md` 时）仅作参考：

```
/
├── GLOSSARY-MAP.md
├── docs/adr/                          ← 系统级决策
└── src/
    ├── ordering/
    │   ├── GLOSSARY.md
    │   └── docs/adr/                  ← context 级决策
    └── billing/
        ├── GLOSSARY.md
        └── docs/adr/
```

## 使用词汇表的语言

输出涉及领域概念时（issue 标题、重构提案、假设、测试名），使用 `GLOSSARY.md` 定义的术语。不要漂移到词汇表明确避免的同义词。

需要的概念还不在词汇表里时，这是一个信号：要么你在发明项目尚未使用的语言（重新考虑），要么存在真实缺口（记下来，交给 `/domain-modeling`）。

## 标记 ADR 冲突

若输出与现有 ADR 矛盾，显式指出而不是静默覆盖：

> _与 ADR-0007（event-sourced orders）矛盾，但值得重新讨论，因为……_
