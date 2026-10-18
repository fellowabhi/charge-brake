# Charge Brake

Android app that holds your battery level on a slow charger, without root.
It reads live battery power and, when the phone is gaining charge, adds just enough
low-priority CPU load ("brake") to keep net power around 0. No ads, no analytics, no internet permission.

**v0.4**
- **Floating bubble** over other apps ("Display over other apps" permission, asked on first use). Toggle in the app or from the notification. Outer ring = net power (blue ← green → amber scale, ticks at the ±100 mW holding band), inner ring = brake. Tap = small / expanded pill (remembered); the pill shows net, state, the target (Hold 60% · 2,916 mAh, or 54% → 56% · 97 mAh to go while charging up / draining), brake, temperature, hold quality and a 30 s sparkline. Drag to move, snaps to the nearest side (remembered); long-press or tap the pill text to open the app. Soft green halo + ✓ when the hold is healthy. ~1 Hz values, ~400 ms easing, then idle; paused while the screen is off. Runs from the brake service (shown while Brake is on).
- **Eco view** (default): ~3 Hz updates, no pulse/glow loops, normal screen timeout. Tap LIVE/ECO to switch to Live (every-frame updates, screen on while plugged).
- **Charge graph**: smooth line (fuel gauge interpolated with the live current), no jumps when the mAh source switches (old curve is shifted), auto-zoom on the visible data (a far target is pinned at the edge with ↑/↓), coloured by the measured net (green ±100 mW, amber gaining, blue draining).
- **Linked time span**: tap either graph: 1 / 2 / 5 / 15 min / 1 h for both (remembered). Power history now 1 h.
- **Details → App's own power (estimate)**: net + brake with the main screen in front (Eco / Live) vs. in the background (screen on), 10 min averages.
- CSV: `ui_mode`, `graph_mAh` columns.

**v0.3**
- Tiles never clip (2×2 grid, auto-size values, scrolls instead of squashing); checked at cover, inner (landscape/portrait) and split-screen sizes.
- Power graph ≈ 50 % of the screen height; new **Charge graph** (mAh from the fuel gauge or counted) with the hold/target line: climbs to the target when charging up, flat on it when holding.
- Net-zero band only while holding (dimmed + labelled while charging up / draining).
- Banner: Start / Resume / Ignore button in the banner. States: Charging up to X% (Y mAh to go) · Holding X% · Draining to X% (~Y mAh to go) · Paused · Off: fast charging.
- Target controls on the main screen (Hold current / Target − 60% +).
- **Drain speed** when the target is below the level: Gentle −150 mW, Normal −400 mW (default), Fast = max brake (temperature cutoff still applies).
- **Hold current is locked** (% and mAh) when you enable the brake or tap Hold current; a dip below it charges back up to it. Survives service restarts.
- **Hold quality** tile (while holding): 5 min mean net → time to drift 1 % (e.g. "~2 h 10 m per 1%"), ∞ · steady when within noise; green ≥ 1 h (excellent ≥ 1 h 30 m), amber 10 min–1 h, red < 10 min with the reason. Logged in the CSV.
- Big number smoothing 1.5 s (controller filtering unchanged). Phone use (est.) line: on battery = −net; plugged = charger estimate − net − brake (charger estimate = 3 min average surplus + use last measured on battery).

**v0.2**
- **UI**: cover screen = single column; inner (unfolded) screen = graph | values panel | details row. Switches live on fold/unfold.
  Eased numbers, scrolling chart, state glows (braking orange, holding green, in use blue, charging amber, off red/grey), LIVE dot.
  Screen stays on while the app is visible and plugged in.
- **Notification**: state-shaped status icon, live values, brake bar, Pause / Stop / Open (updates ~1.5 s).
- **Hold**: mW controller with an mAh outer loop. mAh = fuel gauge (CHARGE_COUNTER) if valid, else coulomb counting
  corrected at % steps. Falls back to % mode if power readings look bad. Capacity default = PowerProfile rated value, else 4860 mAh.
- **Safety**: off above the fast-charger cutoff (6 W, 10 s average; retry every 2 min, or Ignore), paused above 45 °C, off when unplugged.
- **Log**: CSV with `#` header lines (version, settings), settings/change events, mAh + source. Read with `pandas.read_csv(f, comment='#')`.
- **Mockups**: `mockups/index.html` (`?screen=cover|inner|notif`).

## Build
```
. ~/android-env.sh
./gradlew testReleaseUnitTest   # controller simulation + signal tests (see docs/simulation.txt)
./gradlew assembleRelease       # app/build/outputs/apk/release/app-release.apk
```
Native Kotlin, plain framework Views, no dependencies. minSdk 26.
