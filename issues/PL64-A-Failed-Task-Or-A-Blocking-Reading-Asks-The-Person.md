# PL64 — A Failed Task Or A Blocking Reading Asks The Person

```json
{
  "$schema": "issue-1.json",
  "id": "PL64",
  "title": "A Failed Task Or A Blocking Reading Asks The Person",
  "state": "Idea",
  "size": "-",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "A task that fails keeps its card in its working state with a warning and a Needs-you entry to its result, a blocking reading asks for Reworking through a Needs-you entry, and a task that starts by itself takes the first mode and model without a dialog"
}
```

**What must be true.** A person sees at once where the work stopped and needs them, and gets there in one step; the
work goes on by itself only where the project's work rules say so.

- **A task that fails** leaves its card in the working state it was started for, marked with a warning, and adds a
  Needs-you entry that opens the failed task's result directly. This holds for every working state of the project's
  workflow.
- **A reading that blocks** adds a Needs-you entry that opens the Reworking dialog directly. Reworking does not start
  by itself, and the work rules set no cap on reading rounds; the dialog's reading rounds stay as they are.
- **A task that starts by itself** through a rule's `then:` takes the rule's first mode and first model, with no
  dialog. A move by hand opens the start dialog with the first mode and model preselected, and the person may change
  them.

## To be checked

- How the planner learns that a task failed or that a reading blocked: what Porelo answers about a task's end and
  result, and where the window reads it.
- Where the Needs-you list lives today in the window and the planner's contract, and what an entry must carry to open
  a task's result or the Reworking dialog.
