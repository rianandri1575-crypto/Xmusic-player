# XMusic Startup Crash Hardening

This patch is intentionally conservative. It removes persisted DSP/color restoration from the Activity startup path, keeps the launch theme/color deterministic, and defensively reads legacy SharedPreferences values. EQ persistence is restored only when the EQ screen is opened.

The Media3 playback service remains lazy and is only connected when playback is requested.

This does not claim a device-level root cause without a crash log; it removes the highest-risk application-startup dependencies without disabling the eventual player architecture.
