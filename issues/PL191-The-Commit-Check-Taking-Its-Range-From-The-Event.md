# PL191 — The Commit Check Taking Its Range From The Event

```json
{
  "$schema": "issue-1.json",
  "id": "PL191",
  "title": "The Commit Check Taking Its Range From The Event",
  "state": "Implementing",
  "size": "S",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "The executable's check-commits takes --event as the build tools' does: the range and whether the commits come from outside, decided from the workflow's event alone, with the same lines and exit codes"
}
```

**Why this size.** S: one command of the app takes one more form, with the event's reading and the range's decision
and their unit tests beside the build tools' answers; no contract of the API.

**What must be true.** A repository that moves its commit check from the build tools to the planner keeps every
form of the command, and neither passes what the build tools refused nor refuses what they passed.

## Plan

- Seen on the build box with build tools 0.5.3-SNAPSHOT: `CommitCommandTest.thePlannerAnswersAsTheBuildToolsDo`
  fails for every call the command refuses as a usage, since the build tools now answer
  `usage: check-commits RANGE [--outside] | check-commits --event` and the planner the first form alone. The build
  tools' `check-commits --event` reads `GITHUB_EVENT_PATH` and the runner's variables and decides the range: a push
  from its start to the commit that ran, a tag its own commit, a start the checkout lacks refused on `main`, else
  from `origin/main`; a pull request from its base to its head, from outside unless its head lives in the repository
  and it is opened by an owner or member, by GitHub's Actions bot, or on a server other than github.com; any other
  event refused.
- Built in `WorkCommands` beside the range form, with the event's reading and the decision as plain records under
  the app, their messages and exit codes those of the build tools.
- Red first: the parity test, which already fails, and the parity test over `--event` with an event file of each
  kind, beside unit tests of the decision. Harm: commits from outside taken as from inside, so a commit without its
  author's sign-off passes; a range wider or narrower than the event's, so a commit is read twice or not at all.

## Acceptance

- The parity test green over the range form and over `--event` for a push, a tag, a pull request from inside and
  from outside, an unknown event and a missing event file; every new test seen red first.
