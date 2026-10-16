# Home Location Implementation Plan

## Phase 1: foundation

- Rename the copied Android application to `home-location`.
- Use package `nl.freshlytyped.homelocation`.
- Remove Keep and Calendar UI/domain code.
- Add a minimal launcher activity and settings entry point.
- Keep the existing Gradle, Kotlin, AndroidX, coroutine, OkHttp, and test foundation.

## Phase 2: configuration

Add settings for:

- PubNub publish key, subscribe key, optional API key, and channel.
- Tracking enabled state.
- Background location collection enabled state (default enabled).
- Background/stationary scan interval in seconds (30–600 seconds, default 60 seconds).
- Precise versus balanced mode. Precise mode enables high-accuracy moving requests when fine permission is available; disabling it uses balanced power accuracy.
- Minimum acceptable accuracy.
- Minimum publish interval.
- Minimum movement distance.
- Maximum location age.

Do not store a Home Assistant access token in this app.

## Phase 3: movement-aware location

- Register activity recognition while tracking is enabled and collection is allowed by the foreground/background policy.
- Register a `ConnectivityManager.NetworkCallback` for Wi-Fi changes where appropriate.
- Register a default-network callback and pause all collection and new publishing while cellular roaming is active.
- On movement or Wi-Fi loss, request a precise fix when precise mode and fine permission are enabled; otherwise use balanced power accuracy.
- Stop high-accuracy updates after a stationary timeout.
- Retain a balanced fallback request using the configured 30–600 second background interval, subject to Android throttling.
- When stationary fallback publishing is enabled, schedule an alarm at the configured fallback interval and request a one-shot fix for publication; do not rely solely on continuous location callbacks.
- When background collection is disabled, keep registrations only while the app is foregrounded.

## Phase 4: publication

- Select the newest valid fix with acceptable accuracy.
- Reject invalid, future, stale, or materially duplicated fixes.
- Publish both coordinates in one JSON message.
- Use the documented `location` payload contract: six-decimal coordinates, one-decimal accuracy (or `null`), and the Android fix timestamp in epoch milliseconds.
- Retry transient network failures with bounded backoff.
- Never log coordinates, credentials, or access tokens.

## Phase 5: validation

- Add unit tests and a mock PubNub endpoint.
- Verify PubNub subscriber compatibility with both message types.
- Verify the subscriber's two Home Assistant service calls.
- Test permissions, reboot, app update, Wi-Fi transitions, movement, stationary periods, and battery behavior on physical devices.
