# PL25 — Findings In The Screen's Older Code

```json
{
  "$schema": "issue-1.json",
  "id": "PL25",
  "title": "Findings In The Screen's Older Code",
  "state": "Backlog",
  "size": "M",
  "priority": "Medium",
  "rank": 14,
  "blockedBy": [],
  "covers": "Every agent's or repository's text the screen shows is stripped of control and bidirectional characters; a held-back board is not live, each watch tells its own reconnect, and a disposed board opens no watch; a discussion whose Start the person cancelled shows no error"
}
```

**Size:** M - four findings in the screen package alone, each with its own unit or widget test, the text stripping
spread over five widget files, and a change to how a watch tells its reconnect in the package's public interface.
Leaving out the staged moves after a rebind keeps the size: that step was one unit test and a field on a snapshot.

**What must be true.** Every text the screen shows that an agent or a repository wrote is shown without control
characters and bidirectional overrides, as refusal words already are. That covers the workflow's faults, a reader's
findings, a task's raw answer, a proposal's briefs and the names a Start answers. A board never stands as live on a
board it has only held back: one that arrives during a reload or a save waits as stale until it is accepted, and
catching up ends with it. Each watch of a source tells its own reconnect, so two boards on one source never hold each
other in catching up. A board disposed or rebound inside its own notification opens no watch afterwards. A discussion whose Start the
person cancelled shows no error.

## Plan

Read in today's code, all in `screen/lib/src`; each step is one commit with its test, red first.

A further finding is left out: moves staged on the board survive a rebind of the project to another repository, and
the next Save writes them there. It goes with [PL38](README.md#pl38), which saves each move at once, so nothing is
staged, and holds its test.

1. **Texts shown stripped** (`ui/story_work_dialog.dart`, `ui/planning_view.dart`, `ui/story_work_attention.dart`,
   `models.dart`, `planning_board.dart`). Already stripped through `planningShown`: the workflow's faults
   (`PlanningBoard._listen` and `reload`), a reader's findings (`planning_issue_view.dart`,
   `story_work_attention.dart`), a task's raw answer and its reason (`_endedReason`), and refusal words
   (`planningFailureWords`). Not stripped: in the Start dialog the proposal's helper names, roles, agents, models
   and briefs (`_sub`: `Text(sub.brief)`), the work orders and task names (`repo.workOrder`, `repo.task`), the
   coordinator and the story's title, and the names a Start answers (`'Started ${started.task} in
   ${started.container}'`); on the board the story and issue titles of the cards and their tooltip; in the
   attention rows the story's title and description. Stripping happens where a text is shown, never where it is
   parsed: `story_work_order.dart` sends a proposal's names and briefs back and the planner refuses any that changed.
   `_shown` in `planning_board.dart` and `planningShown` in `models.dart` strip different sets (`_shown` keeps
   U+2028 and U+2029); one function stays. Red first, widget test: the board, the Start dialog and an attention row
   pumped from a source whose every text carries U+202E and ESC show no `Text` or `SelectableText` holding a character
   of that class. Harm: a text that reorders what the person reads, such as a brief that reads as harmless and is not.
2. **A held-back board is not live** (`planning_board.dart`). In `_listen`, while `saving` (a save or a reload) a
   reply goes to `_deferredSnapshot`, yet `catchingUp` is cleared and `live` set as if it was accepted, and `problem`
   takes its faults. The deferred board keeps its reconnect state with it, and `_finishOperation` sets `live` and
   ends catching up only when it accepts it. Red first, unit in `planning_live_state_test.dart`: a board arriving
   after a reconnect during a reload leaves the board stale and catching up until the reload ends. Harm: a move or a
   Start offered on a board that is not the one shown.
3. **Each watch its own reconnect** (`client.dart`, `http/planner_source.dart`, `planning_board.dart`).
   `HttpPlanningSource.isReconnecting(project)` is true while any `_Watch` of the project reconnects, and the board
   asks it by project name, so a board whose own watch delivered stays catching up while another watch of the same
   project reconnects. `watchStories` answers a stream that also tells its own reconnect (a subtype of the stream, so
   `PlanningSource` keeps its signature), and the board follows that; `PlanningReconnect` stays for whatever reads it
   by project. Red first, unit: two boards on one source, one watch reconnecting, the other board turns live on its
   board. Harm: a board held stale, its moves and Starts refused, with nothing wrong on its own watch.
4. **No watch after dispose or rebind inside a notification** (`planning_board.dart`). On a source that already
   reconnects the project, `_listen` calls `changed()`, and so `notifyListeners`, before it opens the watch; a
   listener that disposes the board, or calls `changeProject`, runs first, and `_listen` then opens a watch on a
   disposed board, or overwrites the newer generation's `_watching` so that watch is never cancelled. The watch is
   opened before the first notification, and `_listen` returns without opening one when the generation moved. Red
   first, unit: a listener disposing the board on its first notification leaves the source with no open watch.
   Harm: a stream connection and its reconnect loop left running for nothing.
5. **A cancelled discussion is no error** (`planning_board.dart`). When the person cancels the Start of a
   discussion, the window refuses with `PlanningRefusal` named `NotStarted`, and `discussIdea`'s catch reports it
   through `reportActionProblem` as an error banner. It takes `NotStarted` quietly, as the person's own choice: no
   `actionProblem`, `DiscussIdea` stays offered. Red first, unit: a `start` that throws `NotStarted` leaves
   `actionProblem` null. Harm: an error shown for the person's own choice, read as a fault of the planner.

Order: 4, 2, 3, 1, 5.

## Acceptance

- Each red-first test above seen red on today's code, then green; the screen's whole suite, as the module's Maven
  build runs it, green.
- A text with U+202E or a control character in a brief, a Start's task name or a card title shows without it.
- A discussion whose Start the person cancelled leaves no error on the board.

## To be checked

- Whether the window implements `PlanningSource` or `PlanningReconnect` itself, or builds a `PlanningBoard` of its
  own, since `porelo_planning.dart` exports all three; the window's agent is asked before step 3 changes them.
- Where, outside `PlanningBoards`, two boards share one source and project, so step 3's test stands for a real case.
