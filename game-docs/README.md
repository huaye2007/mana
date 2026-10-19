# game-docs

**[English](README.md)** | [简体中文](README.zh-CN.md)

Checks on the repository's own documentation. Test sources only — this module ships nothing, depends on no framework module, and exists so that the rules in [AGENTS.md](../AGENTS.md) are enforced by the build rather than by attention.

```bash
mvn -pl game-docs test
```

It also runs on every `mvn clean verify`, and on CI in both the `verify` and the `quality` job, so a broken link cannot reach main through a change that nobody thought was a documentation change.

## What gates the build

| Check | Rule |
| --- | --- |
| [DocumentLinkTest](src/test/java/cn/managame/docs/DocumentLinkTest.java) | Every local link resolves to a file that exists, and every `#fragment` naming a Markdown file matches a heading slug or an explicit `<a id>` there — including Chinese headings, whose ideographs survive the slug while the full-width punctuation between them does not |
| [DocumentPairTest](src/test/java/cn/managame/docs/DocumentPairTest.java) | Every document has its twin in the other language, and both halves of a specification carry the same clause IDs |
| [DocumentEncodingTest](src/test/java/cn/managame/docs/DocumentEncodingTest.java) | No line has been encoded twice. A tool that reads UTF-8 as Windows-1252 and writes UTF-8 turns 中文 into ä¸­æ–‡, and the result is still valid UTF-8, so nothing downstream complains |
| [SpecificationIndexTest](src/test/java/cn/managame/docs/SpecificationIndexTest.java) | Every `docs/ogbs/OGBS-*.md` is linked from the index of its own language |
| [DocumentCorpusTest](src/test/java/cn/managame/docs/DocumentCorpusTest.java) | The corpus the other checks read is actually there |

That last one is not bureaucracy. The first time these checks ran, the repository root resolved to a module and three of them reported success over an empty file list — a check that cannot fail is worse than no check, because it is believed. Each gate above has also been shown to fail on the defect it is for, by planting one and watching it break.

## What only reports

[IdentifierReportTest](src/test/java/cn/managame/docs/IdentifierReportTest.java) reads every backticked identifier the specifications name against the source tree and prints the ones nothing declares. It fails the build for none of them, because the scan cannot tell a stale API name from a JDK or Netty type the prose legitimately mentions, and gating that would be paid for by adding names to an ignore list rather than by fixing anything. Its one assertion is on the scanner rather than its findings — a report that silently stopped scanning would otherwise look like a clean one.

It currently reads 917 names and reports none unresolved.

## Why this module exists

Two spec layers per component in two languages is the repository's most distinctive rule and was, until this module, enforced entirely by hand. It drifted: an audit found six real deviations, two of them sentences the code had stopped supporting, two of them a broken anchor and a stale source path introduced one commit earlier, and three counts in a README that were simply wrong. Every one would have been caught here.

Build `mvn -pl game-docs test`. The module has no main sources, so Checkstyle has nothing to scan and the quality profile passes it over.
