# Home Location

Separate Android application for publishing movement-aware device location to PubNub. A separate subscriber forwards accepted coordinates to Home Assistant's `input_number` helpers.

The research, architecture, payload contract, permissions, and implementation plan are in [`specs/`](specs/).

## Foundation

This project includes the Android/Kotlin/Gradle foundation copied from Quick Add, with the Keep and Calendar feature code removed. The application package is `nl.freshlytyped.homelocation`.

Build with:

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Location functionality is intentionally not implemented yet; the specifications define the next implementation phases.
