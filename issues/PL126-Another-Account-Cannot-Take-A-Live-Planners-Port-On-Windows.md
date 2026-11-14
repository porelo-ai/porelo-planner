# PL126 — Another Account Cannot Take A Live Planner's Port On Windows

```json
{
  "$schema": "issue-1.json",
  "id": "PL126",
  "title": "Another Account Cannot Take A Live Planner's Port On Windows",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 2,
  "blockedBy": [],
  "covers": "On Windows the planner's listener binds its loopback port exclusively, so another account's process cannot bind the same port beside a live planner and take the window's calls"
}
```

**What must be true.** On Windows no other account's process can bind the loopback port of a planner that is still
running, so the window's calls reach the account's own planner and no stranger beside it. Whether the JDK's exclusive
bind and the server connector's address reuse already ensure this is to be read and measured on Windows; a planner
that ended is covered already, since the window proves its planner before it sends anything.
