# PL88 — The Board Shows An Issue That Waits On Another Repository

```json
{
  "$schema": "issue-1.json",
  "id": "PL88",
  "title": "The Board Shows An Issue That Waits On Another Repository",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "An issue whose head waits on an issue of another repository of the project stands on the board with that wait, and its work is not offered before the issue it waits on is done, instead of the issue being refused as a fault"
}
```

**What must be true.** An issue whose head waits on an issue of another repository of its project, such as
`foo-1#TP01`, stands on the board with that wait, and no work is offered for it before the issue it waits on is
done; today the board refuses such an issue as a fault and leaves it out.
