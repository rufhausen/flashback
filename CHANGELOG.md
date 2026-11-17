# Changelog

Notes under **Unreleased** become the next GitHub release and the Karoo update notes.

## [Unreleased]

- **Flashback Status data field** for ride pages: ACTIVE / FLASHING / NOT ACTIVE / OFF, with the mode the light itself reports (✓ when it matches what Flashback sent, ✗ when it doesn't).
- **Not-working alert**: in-ride alert and beep if Flashback can't control the light for 20 s (light service unreachable, light not paired or disconnected, command failed), plus an "active" notice when it recovers.
- **Separate fast-approach flash mode**: e.g. Slow Flash for approaching cars, Fast Flash once one is closing fast. Stays escalated until the road clears.

## [0.1.0] - 2026-11-16

- Flash selected ANT+ rear light(s) while the radar reports a vehicle; restore the previous mode (or off) after a configurable hold.
- Works on Karoo OS 1.663+ and the 12 Nov 2026 firmware (SensorService fixes from szaffarano/karoofirefly).
- "Toggle Radar Flash" bonus action.
