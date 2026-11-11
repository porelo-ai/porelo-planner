# PL36 — A Description Shown In Its Own Format

```json
{
  "$schema": "issue-1.json",
  "id": "PL36",
  "title": "A Description Shown In Its Own Format",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 23,
  "blockedBy": [],
  "covers": "Each text a store gives - a story's or an issue's description - comes with the format it is written in, set by the store's adapter (the Git store says Markdown), and the screen renders Markdown and shows any other format as plain text"
}
```

**What must be true.** A story's or an issue's description reads as its author wrote it: headings, emphasis, lists
and links rendered, not shown as raw Markdown signs. The store's interface names the format of each text it gives -
Markdown from the Git store, another adapter's own format such as Jira's - and the screen renders the formats it
knows and shows any other as plain text, never worse than today.

## Why

The description is the store's own text in the store's own format. The Git store gives Markdown; another adapter
would give its own. Only the adapter knows which, so the contract carries it, and the screen decides how to show it.

## To be checked

- Which Flutter package renders Markdown and is maintained, and whether its links go through the window's
  `openLink`.
- The contract change with the window: a field beside each text in the description, agreed in the channel.
