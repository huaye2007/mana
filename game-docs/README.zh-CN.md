# game-docs

[English](README.md) | **[简体中文](README.zh-CN.md)**

对仓库自身文档的检查。只有测试源码——本模块不发布任何东西，不依赖任何框架模块，它存在的意义是让 [AGENTS.md](../AGENTS.zh-CN.md) 中的规则由构建来保证，而不是靠注意力。

```bash
mvn -pl game-docs test
```

它也在每次 `mvn clean verify` 中运行，并在 CI 的 `verify` 与 `quality` 两个作业里运行，因此一条断链无法借着"没人认为这是文档改动"的改动进入 main。

## 哪些检查卡构建

| 检查 | 规则 |
| --- | --- |
| [DocumentLinkTest](src/test/java/cn/managame/docs/DocumentLinkTest.java) | 每条本地链接都指向存在的文件；指向 Markdown 文件的每个 `#fragment` 都对应那里的一个标题 slug 或显式 `<a id>`——包括中文标题，其表意文字在 slug 中保留，而其间的全角标点不保留 |
| [DocumentPairTest](src/test/java/cn/managame/docs/DocumentPairTest.java) | 每个文档都有另一语言的对应文件，且规范的两半携带相同的条款 ID |
| [DocumentEncodingTest](src/test/java/cn/managame/docs/DocumentEncodingTest.java) | 没有任何一行被编码了两次。把 UTF-8 当作 Windows-1252 读再写成 UTF-8 的工具会把 中文 变成 ä¸­æ–‡，而结果仍是合法 UTF-8，因此下游不会有任何抱怨 |
| [SpecificationIndexTest](src/test/java/cn/managame/docs/SpecificationIndexTest.java) | 每个 `docs/ogbs/OGBS-*.md` 都被其所属语言的索引链接 |
| [DocumentCorpusTest](src/test/java/cn/managame/docs/DocumentCorpusTest.java) | 其他检查所读的文档集合确实存在 |

最后一条不是形式主义。这些检查第一次运行时，仓库根被解析成了一个模块，于是其中三条在空文件列表上报告成功——一个不可能失败的检查比没有检查更糟，因为它会被相信。上面每条卡构建的检查也都被证明过会在它所针对的缺陷上失败：办法是人为植入一个，然后看它报错。

## 哪些只打印报告

[IdentifierReportTest](src/test/java/cn/managame/docs/IdentifierReportTest.java) 把规范中每个反引号标识符对照源码树检查，并打印出源码未声明的那些。它不为其中任何一个使构建失败，因为这个扫描无法区分"过时的 API 名字"与"散文中合理提到的 JDK 或 Netty 类型"，而把它变成门槛的代价会以"往忽略清单里加名字"而不是"修好什么"的方式付出。它唯一的断言针对扫描器本身而不是扫描结果——否则一个悄悄停止扫描的报告看起来会和一份干净的报告一样。

它当前读取 917 个名字，报告 0 个未解析。

## 本模块为何存在

"每个组件两层规范、两种语言"是本仓库最有特色的规则，而在本模块之前完全靠人工保证。它确实漂移过：一次审计发现六处真实偏差，其中两处是代码早已不再支持的句子，两处是上一个提交刚引入的断锚点与过时源码路径，还有三处是 README 里干脆就是错的计数。每一处都会被这里拦住。

构建使用 `mvn -pl game-docs test`。本模块没有 main 源码，因此 Checkstyle 无内容可扫，quality profile 会直接跳过它。
