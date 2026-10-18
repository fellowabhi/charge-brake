# Charge Brake

Android app that holds your battery level on a slow charger, without root.
It reads live battery power and, when the phone is gaining charge, adds just enough
low-priority CPU load ("brake") to keep net power around 0. No ads, no analytics, no internet permission.

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
./gradlew testReleaseUnitTest   # controller simulation (see docs/simulation.txt)
./gradlew assembleRelease       # app/build/outputs/apk/release/app-release.apk
```
Native Kotlin, plain framework Views, no dependencies. minSdk 26.
