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
- Precise versus balanced mode.
- Minimum acceptable accuracy.
- Minimum publish interval.
- Minimum movement distance.
- Maximum location age.

Do not store a Home Assistant access token in this app.

## Phase 3: movement-aware location

- Register activity recognition while tracking is enabled.
- Register a `ConnectivityManager.NetworkCallback` for Wi-Fi changes where appropriate.
- On movement or Wi-Fi loss, request a precise fix or start the short foreground-service burst.
- Stop high-accuracy updates after a stationary timeout.
- Retain a balanced fallback request with a practical 1–5 minute target, subject to Android throttling.

## Phase 4: publication

- Select the newest valid fix with acceptable accuracy.
- Reject invalid, future, stale, or materially duplicated fixes.
- Publish both coordinates in one JSON message.
- Retry transient network failures with bounded backoff.
- Never log coordinates, credentials, or access tokens.

## Phase 5: validation

- Add unit tests and a mock PubNub endpoint.
- Verify PubNub subscriber compatibility with both message types.
- Verify the subscriber's two Home Assistant service calls.
- Test permissions, reboot, app update, Wi-Fi transitions, movement, stationary periods, and battery behavior on physical devices.
