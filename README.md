# Charge Brake

Android app that holds your battery level on a slow charger, without root.
It reads live battery power and, when the phone is gaining charge, adds just enough
low-priority CPU load ("burn") to keep net power around +100 mW. No ads, no internet permission.

- **Live meter**: mW / mA / V / °C / % at 4 Hz, plus the measured sensor update rate.
- **Chart**: net power (raw + smoothed), target, burn %, battery % (tap: 5 min / 1 min).
- **Modes**: `mW` (primary), `%` (backup: burn while above the hold %), `Auto` (mW, falls back to % if readings look bad).
- **Hold**: current % when started (default) or a target %. Below it, the phone charges normally.
- **Safety**: off above the fast-charger cutoff (default 2 W), paused above the temp cutoff (default 38 °C), off when unplugged.
- **Debug**: raw values, controller state, Share log (CSV).

## Build
```
. ~/android-env.sh
./gradlew testReleaseUnitTest   # controller simulation (see docs/simulation.txt)
./gradlew assembleRelease       # app/build/outputs/apk/release/app-release.apk
```
Native Kotlin, plain framework Views, no dependencies. minSdk 26.
