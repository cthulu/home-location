# Home Location: Research and Architecture

## Goal

Create a separate Android application that publishes the device's current latitude and longitude to a PubNub channel. A separate service consumes the message and calls Home Assistant twice:

```http
POST /api/services/input_number/set_value
```

The two targets are `input_number.phone_latitude` and `input_number.phone_longitude`.

## PubNub payload

The location app publishes one message containing both coordinates:

```json
{
  "type": "location",
  "device_name": "Pixel 8",
  "latitude": 52.123456,
  "longitude": 5.123456,
  "accuracy": 18,
  "timestamp": 1760000000000
}
```

`device_name` is the configurable Android device name included in every location message.

The `type` field allows the existing or shared channel to carry both location and Keep messages. A separate channel is configurable and recommended by default. The Home Assistant API token belongs in the separate consumer service, not in this mobile app or the PubNub payload.

## Home Assistant integration

The subscriber should validate coordinates and issue two service calls:

```json
{"entity_id":"input_number.phone_latitude","value":52.123456}
```

```json
{"entity_id":"input_number.phone_longitude","value":5.123456}
```

The supplied `template.device_tracker` YAML can then expose those helpers as the Android phone tracker.

## Findings from Home Assistant Companion for Android

The Companion app uses Google's Fused Location Provider, PendingIntent-based background delivery, accuracy filtering, timestamp validation, duplicate suppression, and optional foreground services. Its location classes are tightly coupled to Hilt, the Companion sensor framework, server repositories, geofencing, and location history. There is no small standalone module suitable for direct reuse.

The practical approach is to reproduce the relevant strategy locally:

- `FusedLocationProviderClient`
- balanced or high-accuracy `LocationRequest`
- explicit `PendingIntent` to a receiver
- accuracy, age, and duplicate filtering
- boot/package-update re-registration
- foreground service only when sustained precise updates are required

The Home Assistant Android repository is Apache-2.0 licensed. If implementation details are adapted, preserve attribution and the applicable license text.

## Precision and battery

Precise location is based on the same Google Play services fused-location technology used by Google Maps, but Google Maps has proprietary scheduling and optimization. `PRIORITY_HIGH_ACCURACY` can use GPS, Wi-Fi, cellular signals, and sensors and can drain considerably more battery. It should not be used continuously in the background without a foreground service.

The implemented design combines:

1. Low-power activity recognition to identify walking, cycling, or driving.
2. Wi-Fi network loss as an opportunistic movement trigger.
3. A short precise-location burst when movement is suspected.
4. A stationary timeout that stops precise requests.
5. A configurable stationary/background scan interval.
6. An optional periodic fallback so the location does not become indefinitely stale.

The moving request uses high accuracy at a 30-second interval with a 60-second maximum delay when precise mode and fine permission are available. Stationary/background requests use a configurable interval from 30–600 seconds, defaulting to 60 seconds, with batching up to three times the interval. When stationary publishing is enabled, a separate alarm requests a fresh location and publishes it at the configured fallback interval; this does not depend on the fused provider delivering a continuous stream of fixes. Android background execution, vendor battery management, and permission state can prevent strict delivery guarantees.

The user can disable background location collection. When disabled, location and activity detection remain active while an app activity is visible and are removed when the app enters the background. When enabled, the PendingIntent-based registrations continue in the background.

The coordinator pauses location detection, activity recognition, alarms, and new publishing while the active network is a roaming cellular network. Registrations resume when roaming ends. Wi-Fi and non-cellular networks are not considered roaming.

## Wi-Fi disconnect limitations

Wi-Fi loss is useful but cannot be the sole movement detector:

- Wi-Fi can disconnect temporarily without physical movement.
- A network callback is not guaranteed to survive process termination.
- Legacy connectivity broadcasts are restricted on modern Android.
- A device can move while remaining connected to Wi-Fi.

Use `ConnectivityManager.NetworkCallback` opportunistically and combine it with activity recognition and fused-location fallback requests.

## Permissions and platform constraints

The app will need:

- `ACCESS_COARSE_LOCATION`
- `ACCESS_FINE_LOCATION` for precise mode
- `ACCESS_BACKGROUND_LOCATION` for ongoing background tracking
- `ACTIVITY_RECOGNITION` for movement detection
- A foreground service is not part of the current v1 implementation; it remains a possible future enhancement for sustained precise tracking.

On Android 11 and later, foreground location permission should be granted before requesting background location. Android 12 and later let users downgrade an app to approximate location, so the app must detect and explain insufficient precision.

## Proposed components

- `LocationSettings`: separate persisted settings and credentials.
- `LocationPermissionManager`: permission and device-location checks.
- `MovementMonitor`: activity-recognition and network callbacks.
- `LocationUpdateManager`: Fused Location Provider registration/removal.
- `LocationReceiver`: receives batched PendingIntent updates.
- `LocationFilter`: validates, selects, and deduplicates fixes.
- `LocationPublisher`: publishes the location payload to PubNub.
- Foreground activity lifecycle hooks for foreground-only collection when background collection is disabled.

On Android 12 and later, the explicit `PendingIntent` used for fused-location and activity-recognition delivery is mutable because Google Play services adds the result data to the intent. Alarm intents remain immutable.

## Testing requirements

Unit-test coordinate validation, accuracy filtering, stale-fix rejection, duplicate suppression, payload serialization, PubNub error handling, configurable request bounds, and persisted settings. Device-test permission flows, reboot recovery, foreground-only mode, background interval cadence, roaming transitions, Wi-Fi transitions, activity transitions, battery optimization, Android 8–current behavior, and vendor-specific restrictions.
