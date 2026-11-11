# PL56 — The Board Builds Only What Is Seen

```json
{
  "$schema": "issue-1.json",
  "id": "PL56",
  "title": "The Board Builds Only What Is Seen",
  "state": "Accepted",
  "size": "L",
  "priority": "Medium",
  "rank": 29,
  "blockedBy": [],
  "covers": "The board builds only the cards in sight, so unfolding a column of hundreds of stories and every keystroke in the search answer within a frame; its speed is measured headless by the agent in profile mode, not by hand"
}
```

**Size:** L - a layout change of the board, decided first, and a measuring app with its headless run.

**What must be true.** On a board of several hundred stories, unfolding a column and typing in the search answer
without a felt delay, and every change to the board's screen can be measured for speed without a person at the
desktop.

## Why

The board scrolls as one page with all its columns in it, so a column is laid out whole: unfolding one of 125 cards
builds all 125, about 70 ms in profile mode, and a keystroke in the search still takes 21 to 28 ms, a third of it
garbage collection. Only cards in sight can be built lazily once each column scrolls by itself. It is built after the
board's screen is refined further, so the layout it changes is the one that stays.

## Plan

- Each column scrolls by itself and builds its cards lazily (`ListView.builder`), the board scrolling sideways
  only; the folded columns and the sideways bar stay as they are.
- An example app in the screen package shows a board of a few hundred stories from the mock source; an
  `integration_test` folds a column and types in the search inside `traceAction`, and a driver writes the timeline
  summary. It runs headless: `xvfb-run -a -s '-screen 0 1280x800x24' dbus-run-session -- flutter drive --profile -d
  linux`, with DISPLAY and WAYLAND_DISPLAY unset, the board's font bundled and a private temporary directory. Under
  Xvfb the raster times are software rendering, so only build times and runs against each other count.
- Red first: the summary's worst build time of an unfold over a frame's 16 ms on today's board.

## Acceptance

- The headless summary shows an unfold and a keystroke each under 16 ms of build time; the run is one command an
  agent starts.
