# PL153 — Start Work Shows A Single Choice As Text

```json
{
  "$schema": "issue-1.json",
  "id": "PL153",
  "title": "Start Work Shows A Single Choice As Text",
  "state": "Backlog",
  "size": "XS",
  "priority": "High",
  "rank": 73,
  "blockedBy": [],
  "covers": "The Start work dialog shows the machine and the working state as labeled text where there is only one to choose, and leaves the machine out where only one planner reports the project, instead of drop-down lists of one"
}
```

**Why this size.** XS - one dialog of the screen package and its test; no contract.

**What must be true.** A person who opens Start work sees a choice only where there is one to make, and knows what each
shown value is.

## Plan

- Seen in a live run: the dialog showed two drop-down lists with one entry each, the machine's planning
  (`story-work-machine`) and the working state (`story-work-target`), with no label, and the person asked what they
  are for. `StoryWorkDialog` (`screen/lib/src/ui/story_work_dialog.dart`) always draws both as `DropdownButton`.
- With one machine that reports the project, the machine is not shown; with more, it is a labeled list ("Runs on").
  With one working state the story can start toward, it is labeled text ("Working state: Implementing"); with more, a
  labeled list. The keys stay, so the window's tests find them.
- Red first, in the screen's story work dialog tests: one machine and one working state show no drop-down list and the
  state's name with its label; two of each show both lists. Harm: none beyond reading; the test guards that a second
  machine or state can still be chosen.

## Acceptance

- The test above, seen red against today's dialog; the screen's suite passes.
