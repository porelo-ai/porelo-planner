# PL25 — Findings In The Screen's Older Code

```json
{
  "$schema": "issue-1.json",
  "id": "PL25",
  "title": "Findings In The Screen's Older Code",
  "state": "Review",
  "size": "M",
  "priority": "Medium",
  "rank": 14,
  "blockedBy": [],
  "covers": "Every agent's or repository's text the screen shows is stripped of control and bidirectional characters; a held-back board is not live, each watch tells its own reconnect, and a disposed board opens no watch; a discussion whose Start the person cancelled shows no error"
}
```

**Size:** M - four findings in the screen package alone, each with its own unit or widget test, the text stripping
spread over three widget files and the board, and a new, optional type in the package's public interface through
which a watch tells its own reconnect.

**What must be true.** Every text the screen shows that an agent or a repository wrote is shown without control
characters and bidirectional overrides, as refusal words already are. That covers the workflow's faults, a reader's
findings, a task's raw answer, a proposal's briefs and the names a Start answers. A board never stands as live on a
board it has only held back: one that arrives during a reload or a save waits as stale until it is accepted, and
catching up ends with it. Each watch of a source tells its own reconnect, so two boards on one source never hold each
other in catching up. A board disposed or rebound inside its own notification opens no watch afterwards. A discussion
whose Start the person cancelled shows no error.

## Plan

As built, all in `screen/lib/src`. Moves staged on the board that survive a rebind of the project to another
repository are left out: they go with [PL38](README.md#pl38), which saves each move at once, so nothing is staged.

1. **No watch after dispose or rebind inside a notification** (`planning_board.dart`). `_listen` opens the watch first
   and only then tells a source that already reconnects the project, so a listener that disposes the board or calls
   `changeProject` on that first notification cancels the watch that stands. `changeProject` notifies only a board
   that is not disposed. Unit tests in `planning_live_state_test.dart`: a board closed on its first notification
   leaves no open watch; a board rebound on it keeps exactly one, the newer, open.
2. **A held-back board is not live** (`planning_board.dart`). A board streamed while `saving` (a save or a reload) is
   held in `_deferredSnapshot` with what its acceptance tells of the watch: its faults, the end of catching up and
   `live`. Those apply only when the board is shown, by `_finishOperation`, or by `_acceptNewest` when a read newer
   than it is shown. Unit test in `planning_live_state_test.dart`: a board arriving after a reconnect during a reload
   leaves the board stale and catching up, with no move staged, until the reload ends.
3. **Each watch its own reconnect** (`client.dart`, `http/planner_source.dart`, `planning_board.dart`). The new
   `PlanningWatch` is a `StreamView` of the boards with a `ValueListenable<bool> reconnecting` of that watch alone.
   `HttpPlanningSource.watchStories` answers one; `PlanningSource` and `PlanningReconnect` keep their signatures, so a
   window's own source still works. The board follows a `PlanningWatch`'s own `reconnecting` and falls back to
   `PlanningReconnect.isReconnecting(project)` for any other stream. Unit tests: two boards on one source, the first
   one's watch reconnecting, the second turns live on its own board (`planning_live_state_test.dart`); the HTTP watch
   tells each of its own reconnects (`planner_source_test.dart`).
4. **Texts shown stripped** (`planning_board.dart`, `ui/planning_view.dart`,
   `ui/story_work_dialog.dart`, `ui/story_work_attention.dart`). `planningShown` in `models.dart` is the one stripping
   function: `_shown` is gone, and `planningFailureWords` strips with `planningShown` on one line and keeps its cap of
   4096 characters. Stripped where shown, never where parsed or sent back: on the board the phase and state names and
   their fold tooltips, the story and issue ids, titles, states, sizes, priorities, blockers, a card's tooltip and the
   run's agent, model, mode and time; in the Start dialog the story, the states, the helper names, roles, agents,
   models and briefs, the work orders and task names, the agent choices and the names a Start answers; in an attention
   row and its story dialog the story's id, title and state and the issue's id and repository. Widget test
   `shown_text_test.dart`: the board, the Start dialog after a Start, and an attention row with its story dialog,
   pumped from a source whose every text carries U+202E and ESC, show no `Text`, `SelectableText` or tooltip holding a
   control or bidirectional character.
5. **A cancelled discussion is no error** (`planning_board.dart`). `discussIdea` takes a `PlanningRefusal` named
   `NotStarted`, the person's cancelled Start, quietly: no `actionProblem`, and `DiscussIdea` stays offered. Unit test
   in `planning_discuss_idea_test.dart`.

Within the package only `PlanningBoards` builds boards, one per provider and project; two boards on one source and
project stand when two providers hand the same source, or when the window builds a `PlanningBoard` of its own, which
the package exports.

## Acceptance

- Each test above seen red, then green: the dispose and rebind tests with the first notification told before the watch
  is opened; the held-back test with the watch's state set while the board is held back; the two-board test with the
  board following the source's project-wide reconnect; the HTTP test with a `PlanningWatch` whose `reconnecting` is
  not the watch's; the text test on the code before the change, and again with a card title, a brief and the Started
  line, and an attention row's story title left unstripped; the discussion test without the `NotStarted` case.
- The screen's whole suite, as the module's Maven build runs it, green.
- A text with U+202E or a control character in a brief, a Start's task name or a card title shows without it.
- A discussion whose Start the person cancelled leaves no error on the board.
