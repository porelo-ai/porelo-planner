# PL89 — The Planner Names Its Version To The Window

```json
{
  "$schema": "issue-1.json",
  "id": "PL89",
  "title": "The Planner Names Its Version To The Window",
  "state": "Idea",
  "size": "-",
  "priority": "High",
  "rank": 2,
  "blockedBy": [],
  "covers": "The planner answers its own version and the commit it was built from to the window, so the window's About names which planner it talks to, and a planner left running from an older install is seen at once"
}
```

**What must be true.** The window can read which planner it talks to - its version, such as `0.1.0~snapshot.36`, and
the commit it was built from - and show it in its About, so a person sees at once when a planner from an older
install still runs beside a newer window; today `/health` answers its status alone and the address file holds port,
token and log.
