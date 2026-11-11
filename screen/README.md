# Screen

The Porelo window's planning screen, the Flutter package `porelo_planning`: the board, a story, an issue, starting a
story's work and the reading choice, read from the planner through a client the window hands it. The window embeds it
by a Git ref and the path `screen`; it is no part of the planner's native executable, and Maven builds it only under
the root's profile `all`.

A window client can mix in `PlanningTaskReports` to route `taskEvent` through the offer's original source, project
and story. Start returns only `PlanningTaskStarted(task, container)`; the first STARTED report binds the visible
machine name verbatim. Later reports must agree with that binding. The report keeps Porelo's
`PlanningTaskEnded(at, finished, why, answer, unpushed)` unchanged.

`PlanningScreen` retains its `startTask` and `taskChanges` hooks on its boards for follow-up reading work. A window
using `ReadingDecision` without a screen sets those hooks on the board itself. `PlanningTaskChanges.stop` stops
the supplied runtime tasks without reporting task events. A late Start after cancellation goes directly to `stop`;
the planner has voided its offer and refuses reports for it.
