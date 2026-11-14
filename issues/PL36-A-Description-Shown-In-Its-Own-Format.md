# PL36 — A Description Shown In Its Own Format

```json
{
  "$schema": "issue-1.json",
  "id": "PL36",
  "title": "A Description Shown In Its Own Format",
  "state": "Implementing",
  "size": "L",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "Each text a store gives - a story's or an issue's description - comes with the format it is written in, set by the store's adapter (the Git store says Markdown), and the screen renders Markdown and shows any other format as plain text"
}
```

**Size:** L - one more member in the store's interface, the Git store and the mock mode, a contract change the window
reads, and a Markdown rendering in the screen that keeps links and images under the screen's own rules.

**Estimate:** 113 min, the measured median of L; the steps sum to 133 by the medians (S 33 + S 33 + M 67), and the
rendering of the screen's own adds about an hour to step 3.

**What must be true.** A story's or an issue's description reads as its author wrote it: headings, emphasis, lists
and links rendered, not shown as raw Markdown signs. The store's interface names the format of each text it gives -
Markdown from the Git store, another adapter's own format such as Jira's - and the screen renders the formats it
knows and shows any other as plain text, never worse than today.

## Why

The description is the store's own text in the store's own format. The Git store gives Markdown; another adapter
would give its own. Only the adapter knows which, so the contract carries it, and the screen decides how to show it.

## Plan

What stands today, read in the code:

- `store/api`: `Story.description` and `Issue.text` are plain `String`s ("its body, the text below the head");
  `PlanningStore` says nothing of their format. `GitStore` builds `Story` from `story.body()`; `StoreReader` builds
  `Issue` with the whole file, title line and JSON head included (its Javadoc says "below the head"), and with an
  empty text for a closed issue whose file is gone. Both are Markdown. `MockStore` serves
  `app/src/main/resources/mock/board.json`.
- Contract `planner.yaml`: `StoryDetail.description` and `IssueDetail.text` are bare strings; `BoardController`
  fills them in `storyDetail` and `issueDetail` from `Answers.StoryDetail` and `Answers.IssueDetail`;
  `ApiMatchesDescriptionTest` holds the records to the schemas. The board and the stream carry no descriptions.
- Screen: `PlanningStoryView` shows the description as `SelectableText(planningValue(...))`, raw Markdown.
  `PlanningIssueView` shows no issue text at all, only the What must be true paragraph as raw text; `PlanIssueDetail`
  reads `text` but nothing shows it. `pubspec.yaml` has no Markdown package. Links go through
  `PlanningHost.openLink`, and `PlanningWebLink` opens only `https` addresses with a host.

Steps:

1. **Store (S).** `store/api` gains `TextFormat`, an open name (`MARKDOWN`, `PLAIN`, an adapter may give its own),
   and `PlanningStore.textFormat()`, one format for every text the store gives, since one adapter writes one
   format; the records stay unchanged. `GitStore` and `MockStore` answer `MARKDOWN`; the default is `PLAIN`.
   Unit test in `store/git`: `GitStore` answers `MARKDOWN` - harm: the screen shows a Git store's text as raw signs.
2. **Contract and app (S).** `StoryDetail` gains `descriptionFormat`, `IssueDetail` `textFormat`: a required string
   matching `^[A-Z][A-Z0-9_]{0,31}$`, not an enum, so a new adapter's format breaks no window. `Answers` and
   `BoardController` fill them from `textFormat()`. The window agreed to the two members, their names and the open
   set; it reads them only through the screen package and moves its pin when the head is posted. Red first:
   `ApiMatchesDescriptionTest` fails once the schema has the member and the record not. `BoardReadsTest` asserts the
   member on both answers - harm: a contract the window reads that the planner does not serve.
3. **Screen (M).** `PlanStory` and `PlanIssueDetail` read the format, absent or unknown read as plain. One widget,
   `PlanningText`, renders `MARKDOWN` (headings, paragraphs, emphasis, lists, block quotes, code, links) and shows any
   other format as today's `SelectableText`. The screen takes only the Dart team's `markdown` parser and renders its
   nodes itself; no Flutter Markdown widget package. `PlanningStoryView` shows the description through it, and
   `PlanningIssueView` gains a section with the issue's text through it, beside What must be true; the Git store's text
   keeps its head, which Markdown shows as a code block, and `Issue`'s Javadoc is corrected to say so. Rules the widget
   keeps: a link opens only through `openLink` and only for `https` with a host, as `PlanningWebLink` does; an image is
   never fetched, shown as its alternative text; raw HTML is shown as text; `planningShown` still strips control and
   direction characters from every rendered piece. The fake data in `lib/src/fake/planning_data.dart` and the mock board
   get the format. Widget tests (unit level, `flutter test`), red first: a link `[x](http://example.org)` and
   `[x](file:///etc/passwd)` call no `openLink` - harm: the screen opens what the window would not; an image
   `![a](https://example.org/p.png)` starts no network fetch - harm: a description that tells a server who reads it; an
   unknown format shows the text unchanged - harm: a text of another adapter lost or garbled.

No test needs a VM; every level is unit or widget level, plus the app's existing HTTP tests.

## Acceptance

- `GET /projects/{project}/stories/{story}` answers `descriptionFormat: MARKDOWN` and `GET
  /projects/{project}/issues/{issue}` answers `textFormat: MARKDOWN` from the Git store and the mock mode;
  `ApiMatchesDescriptionTest` holds the schema and the records together.
- On the screen, a story's description and an issue's text show headings, emphasis, lists and links rendered; an
  unknown or absent format shows the text as today.
- The link, image and unknown-format tests above have been seen red, and the Build workflow's Java and Flutter jobs
  pass locally.

## To be checked

- The window keeps caret constraints in `pubspec.yaml`, commits `pubspec.lock` and pins the screen by commit; the
  `markdown` parser reaches it through the screen.
- Whether the `markdown` parser builds with the Flutter version `pubspec.yaml` pins and resolves offline in the screen's
  Maven build as the other dependencies do.
