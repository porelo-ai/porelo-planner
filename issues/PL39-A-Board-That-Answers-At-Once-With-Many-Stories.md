# PL39 — A Board That Answers At Once With Many Stories

```json
{
  "$schema": "issue-1.json",
  "id": "PL39",
  "title": "A Board That Answers At Once With Many Stories",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 26,
  "blockedBy": [],
  "covers": "Folding a column, searching and every other change answers at once on a board of a few hundred stories, measured in profile mode before anything is optimized"
}
```

**What must be true.** On a board of a few hundred stories and their issues, folding a column, typing in the search
and every other change answers without a felt delay.

## Why

In the operator's test the board felt sluggish: folding Ideas and typing a search both lag. A widget test with 300
stories showed the board's own logic at a few milliseconds per change, while each change rebuilds about 4,000
elements - every card's buttons, texts, icons and drop targets. Building only the cards in sight, with each column
scrolling on its own, brought no felt gain and was taken back. The test ran in debug mode, which is several times
slower than the shipped build.

## To be checked

- The time of a fold and a keystroke measured in profile mode with the Flutter DevTools, and where it goes, before
  any change.
