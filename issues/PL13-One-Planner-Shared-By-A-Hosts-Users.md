# PL13 — One Planner Shared By A Host's Users

```json
{
  "$schema": "issue-1.json",
  "id": "PL13",
  "title": "One Planner Shared By A Host's Users",
  "state": "Idea",
  "size": "-",
  "priority": "Low",
  "rank": 1,
  "blockedBy": [],
  "covers": "One planner serving every user of a host as a service, each user signed in, seeing only what their own Git rights let them read, with clones shared where the rights allow"
}
```

**What must be true.** Every user of a host reaches one planner running there as a service instead of starting their
own: each call is known to come from its user, a user sees only the repositories their own Git rights let them read,
and a change in the store still names the person who made it. Clones of a project are kept once on the host, not once
per user.

## Open questions

- Whose Git login writes: each user's own, which the service would then have to keep, or the service's own account,
  which no longer shows in the store who moved a story.
