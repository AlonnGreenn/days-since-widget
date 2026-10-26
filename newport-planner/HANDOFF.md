# Planner — handoff for the next Claude chat

An Android app that implements **Step 1 ("Manage your time in five minutes a day") of Cal Newport's
*How to Become a Straight-A Student***: capture to-dos during the day, then each morning give every
item either a time slot today or a later day. The original request is in `docs/ORIGINAL_REQUEST.md`.

## Where things are

- Repo: `AlonnGreenn/days-since-widget` (meant to be renamed "vibe-coded-projects"; one folder per app).
  This app lives in `newport-planner/`. The older app at the repo root is the Days Since widget.
- Build: `.github/workflows/planner.yml` builds on every push touching `newport-planner/**`.
  - APK: `https://github.com/AlonnGreenn/days-since-widget/releases/download/planner-latest/planner.apk`
  - On failure, the log is published at release tag `planner-buildlog` (`build.log`, `errors.txt`), readable without auth.
- Signing key `app/planner.jks` is committed on purpose (password in `app/build.gradle.kts`) so builds install as updates.
- Local sandbox can't reach Google Maven: only the pure Kotlin in `domain/` can be compiled/tested locally
  (`kotlinc` + JUnit jar). Everything else is verified by CI.

## Architecture

- `domain/` — pure Kotlin, unit-tested (`app/src/test`). Model, 05:30 day math, buckets, overlap rules,
  merging, recurrence, streak, `.ics` reader/writer.
- `data/` — `PlannerRepository` (singleton, `StateFlow<AppState>`, atomic JSON file `state.json`, async coalesced writes),
  `StateCodec` (org.json).
- `system/` — notifications (morning cue + current/next block), Xiaomi badge reflection, exact alarms (next 05:30 and next block boundary),
  boot/time-change receivers. `SystemSync` re-derives all of it from state (debounced for UI, immediate for receivers).
- `ui/` — Jetpack Compose. `HomeScreen` (3 reorderable sections), `ScheduleScreen` (morning ritual),
  `PlacementOverlay` (the drag-and-drop target screen used everywhere), `SettingsScreen`.

## Data model in one paragraph

A `Task` has `parts` (1 normally, several after merging blocks), optional `date` (epoch day) and optional `startMin`
(minutes after 05:30). No date = to-do list. Date only = planned for that day. Date + start = a block. Days run 05:30→05:30.
`Series` copies repeating items into real tasks 60 days ahead (`generatedThrough`), so single occurrences can be edited/deleted.
Imported calendars are `CalendarSource`s; their events are tasks with `calendarId`.

## Decisions made with the user (don't re-ask)

- Completion is marked only during the next morning's review (✓ on "not finished earlier"); no complete button in Today.
- 15-minute drag precision (double-arrow handles), 30-minute single-arrow handles; exact minutes in the edit dialog.
- No overlapping blocks. Dropping a block onto another block merges them into a group (parts shown as separate rows; "Split" in edit).
- No visual distinction between fixed events and tasks (KISS). Optional "deadline" flag shows red on the calendar + a deadlines list.
- English only, left-to-right, weeks Sunday→Saturday, 24h times. Calendar = next 30 days from today, extendable.
- Morning cue: ongoing notification + badge count until the 3 buckets (unfinished earlier, today without hour, to-do list) are empty.
  Items added after emptying don't bring it back until the next 05:30. No in-app notification banners.
- Lock screen: one silent notification with today's current/next block; replaced when that block ends; swiping it away hides that block only.
- Import/export `.ics` only (no sync). Re-importing a calendar with the same name asks: replace upcoming events or add separately.
- Copy to-do list as markdown (to-do list only). Optional "why not done" note: a button, never prompted; exportable as markdown.
- Repeat-postponement: gentle "Pushed N times" label, threshold configurable (default 3).
- Streak = mornings where the list was emptied; 7 days in a row earns a skip (max 2). Minimal animation (a tick).
- Backlog bankruptcy: "Move all…" unfinished items to a date or back to the to-do list.
- User is on a Xiaomi phone (HyperOS/MIUI). Launcher badges are best effort.
- User prefers few questions per round, no repeated questions, honest criticism over flattery.

## Known gaps / ideas not built (need user approval first)

- Only `.ics` is supported for import (Google/Outlook/university exports all offer it).
- Home-screen widget / quick-settings tile for capture were not approved yet.
- Drag-to-reorder inside the to-do list was replaced by drag-to-place (order matters little since the list is emptied daily).

## Status log

- v0.1: first complete build (all features above). Not yet tested on the user's phone.
