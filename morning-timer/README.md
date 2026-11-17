# Morning Timer

A tiny floating stopwatch that pops up at a set time (e.g. 08:05, five minutes after your alarm),
counts up until you tap **Done**, and gently chimes every so often so you don't drift off into your phone.

- **Timers** work like alarms: several of them, each with its own name, start time, days and on/off switch.
  No days ticked = fires once, then switches itself off.
- **Beeps** per timer: off, every N minutes (default 8.5), or a custom list like `5, 10, 15, 25`
  (minutes after start). Not alarms — a short chime, nothing to dismiss.
- **Floating window**: drag it; it hops to the nearest of 8 spots (4 corners + thirds of each side)
  and remembers it. Size and opacity in settings.
- **Sound**: 5 synthesised chimes (no audio files), volume slider, test button. Uses notification volume,
  so silent mode / Do Not Disturb mutes it.
- **Start now** button for testing or for days without a timer. Last run's duration is shown on the main screen.
- An ongoing notification shows the running time too and has a **Done** button.

## Install

`https://github.com/AlonnGreenn/days-since-widget/releases/download/timer-latest/morning-timer.apk`

On first open: allow notifications, then tap **Allow floating window** (Android settings → enable
"Display over other apps" for Morning Timer). Turn a timer on.

## Build

`.github/workflows/timer.yml` builds and publishes on every push touching `morning-timer/`.
Signing key `app/timer.jks` is committed on purpose so new builds install as updates.
Plain Java, no libraries.
