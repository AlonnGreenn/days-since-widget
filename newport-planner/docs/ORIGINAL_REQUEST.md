# Original request (user's words, lightly cleaned up)

We are going to make an Android app to help me apply Step 1 in the book (Cal Newport, *How to Become a
Straight-A Student*) on scheduling. There are infinite scheduling solutions out there but none tailored to these needs.

It includes 3 bits:
- a basic to-do list
- a calendar split into 2 bits: what you'll do today down to the half hour, and on which days (only specified
  to the day) you'll do other things (still good to be able to date down to the minute any time)

The practice:
- write new tasks into the to-do list as they come along during the day
- each morning, schedule all items on the to-do list either into today by the hour, or push them to a later day
  without an hour — that morning you'll be prompted to schedule them by the hour along with the to-do list
  (adjusting during the day sometimes). Items scheduled by the hour yesterday but missed are prompted as well.

What I want in practice:
- The app opens with 3 blocks separated vertically: to-do list, today view, 30-day calendar (expandable to whenever).
  Each block is draggable to reorder; it's fine if they don't all fit on screen.
- The to-do list is made of draggable blocks, with + to add and a remove button.
- To-do blocks can be dragged straight into today's time slices. Today looks like a long empty space divided by full
  lines on the hour and dashed lines on the half hour, with 24h times (20:00) on the left. A dropped task takes 30 minutes
  by default; once placed it has single arrows (resize in 30-minute steps) and double arrows (15-minute steps) top and
  bottom, a pencil to edit/see all the text. Tapping an empty half hour creates a block.
- The calendar shows the next 30 days, a cell per day in Sunday→Saturday columns, with a rough indicator of how many
  tasks each day has. Tapping a day lists its tasks (with times if they have them). The same data underlies both views,
  so something scheduled by the hour far ahead (e.g. an imported class) appears as a block in Today that morning.
  To-do blocks can be dragged onto days (no time; scheduled by the hour that morning).

Retention (cue, craving, action, reward; Duolingo-style):
- The day starts at 05:30. The app cues me to "schedule": empty (1) unfinished tasks from yesterday or earlier,
  (2) today's calendar tasks without an hour, (3) the to-do list — into today by hour or a later date, all from one
  "to schedule" view by dragging.
- The icon badge (sparkle + count) stays until those three are empty; new items added later don't bring it back
  until the next 05:30.
- Completion is marked only the next morning; minimise effort outside the 5–10 minute morning session.
- The current/next task of today shows as a notification on the lock screen.

Import/export: popular calendar formats (most importantly my class schedule), a copy button that copies the to-do list
as a markdown list for Obsidian, and a way to download the app's source code plus this request to give to a future Claude.

Code with best practices. Ask about unclear points, suggest features that fit Newport's philosophy and retention
ideas (with approval first), and criticise where I drift from Newport's method.
