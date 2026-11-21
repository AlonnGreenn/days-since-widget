# Planner — handoff for the next Claude chat

An Android app that implements **Step 1 ("Manage your time in five minutes a day") of Cal Newport's
*How to Become a Straight-A Student***: capture to-dos during the day, then each morning give every
item either a time slot today or a later day. The original request is in `docs/ORIGINAL_REQUEST.md`.

## Where things are

- Repo: `AlonnGreenn/days-since-widget` (meant to be renamed "vibe-coded-projects"; one folder per app).
  This app lives in `newport-planner/`. The older app at the repo root is the Days Since widget.
- Build: `.github/workflows/planner.yml` builds on every push touching `newport-planner/**`.
  - CI publishes an **unsigned** `planner-unsigned.apk` at release `planner-latest`. Sign + install from the Inspiron:
    `newport-planner/tools/install.sh <adb-device>` (key only in `~/.config/planner-signing/` there; never committed or uploaded,
    since the repo is public and the app has all-files access).
  - On failure, the log is published at release tag `planner-buildlog` (`build.log`, `errors.txt`), readable without auth.
- applicationId `com.vibecoded.newportplanner` since v0.2. v0.1 (`com.vibecoded.planner`) was signed with a throwaway CI key
  (`planner.jks` was gitignored, so CI fell back to a fresh debug key), so it could not be updated in place; its data was
  migrated via its own .ics export (see status log).
- Local sandbox can't reach Google Maven: only the pure Kotlin in `domain/` can be compiled/tested locally
  (`kotlinc` + JUnit jar). Everything else is verified by CI.

## Architecture

- `domain/` — pure Kotlin, unit-tested (`app/src/test`). Model, 05:30 day math, buckets, overlap rules,
  merging, recurrence, streak, `.ics` reader/writer.
- `data/` — `PlannerRepository` (singleton, `StateFlow<AppState>`, atomic JSON file `state.json`, async coalesced writes),
  `StateCodec` (org.json).
- `system/` — notifications (morning cue + current/next block), Xiaomi badge reflection, exact alarms (next 05:30 and next block boundary),
  boot/time-change receivers. `SystemSync` re-derives all of it from state (debounced for UI, immediate for receivers).
- `data/VaultMirror` + `domain/VaultExport` — one-way copy into the Obsidian vault (see below).
- `ui/` — Jetpack Compose. `HomeScreen` (3 reorderable sections), `ScheduleScreen` (morning ritual),
  `PlacementOverlay` (the drag-and-drop target screen used everywhere), `SettingsScreen`.

## Data model in one paragraph

A `Task` has `parts` (1 normally, several after merging blocks), optional `date` (epoch day) and optional `startMin`
(minutes after 05:30). No date = to-do list. Date only = planned for that day. Date + start = a block. Days run 05:30→05:30.
`Series` copies repeating items into real tasks 60 days ahead (`generatedThrough`), so single occurrences can be edited/deleted.
Imported calendars are `CalendarSource`s; their events are tasks with `calendarId`.

## Vault copy (v0.2)

With "All files access" (asked once on first launch; Settings → Obsidian vault), every save also writes
`/storage/emulated/0/vault/planner/` (Syncthing carries it to the other devices). One-way, app → vault; files are
rewritten only when their content changes, so editing them in Obsidian does nothing.
- `state.json`: byte-identical copy of the app's data file = the backup. A fresh install restores it automatically
  (at start if access is already granted, else on the first resume after granting) — only when the app holds no data.
  An empty state never overwrites a non-empty backup. Day-by-day history comes from the vault's daily git commit.
- `Planner.md`: to-do list, unfinished earlier items, today, deadlines, next 30 days (links to day notes).
- `days/YYYY-MM-DD.md`: one per day with items; frontmatter `date, weekday, items, done, morning_review`;
  `- [x]` = marked done. Days from 30 back to 60 ahead are kept current; older ones are frozen history.
- `calendar.ics`: every dated item (fixed DTSTAMP so it doesn't churn).

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

- v0.1: first complete build (all features above). Used daily since 2026-10-26; "works great".
- v0.2 (2026-11-21): vault copy + backup/restore, new applicationId + Inspiron-only signing key.
  Migration from v0.1: its .ics export → state.json (ids, dates, times, merged parts, deadlines, the 2 one-event calendars kept;
  past items marked done; streak 20 / 1 skip rebuilt approximately; postpone counts lost; no reasons existed).
  Old app disabled with `pm disable-user` (data kept; `pm enable com.vibecoded.planner` brings it back).
