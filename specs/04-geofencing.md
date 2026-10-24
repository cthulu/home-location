# Geofencing Spec

## Goal

Reduce battery consumption by detecting when the device enters a known zone
(home, work, etc.) and switching to a low-frequency tracking mode. Zone
definitions are pushed from Home Assistant via PubNub.

**Phase 1 (this spec):** low-frequency mode inside zones.

**Phase 2 (future):** pause mode — stop all tracking inside zones entirely.
Note this for later implementation.

## Architecture Overview

```
Home Assistant ──zones──► pubnub-listener ──zones──► Android app
     ▲                                 │                    │
     │                                 │                    │
     └──── device_tracker ◄────────────┘                    │
                                                            │
          Android app ──request_zones──► pubnub-listener ◄──┘
```

The app currently only publishes to PubNub via REST. Geofencing requires a
subscription channel so Home Assistant can push zone definitions to the app.

Three new components in the app:

- `ZonesSubscriber` — subscribes to a PubNub commands channel via the PubNub
  SDK. Parses zone update messages and forwards them to `ZoneManager`.
- `ZoneManager` — persists the zone list, registers/removes geofences with
  `GeofencingClient`, and reports the current zone state to the coordinator.
- `GeofenceReceiver` — BroadcastReceiver for geofence transition events.
  Applies debouncing and notifies the coordinator.

The `pubnub-listener` gains a handler for `request_zones` messages: it queries
Home Assistant for zone definitions and publishes them to the commands channel.

## Message Contracts

### `request_zones` (app → listener)

Published to the existing PubNub channel. The listener identifies the app's
device by the `device_name` field.

```json
{
  "type": "request_zones",
  "device_name": "Pixel 8"
}
```

### `zones` (listener → app, on commands channel)

```json
{
  "type": "zones",
  "zones": [
    {
      "id": "home",
      "latitude": 52.123456,
      "longitude": 5.456789,
      "radius": 150,
      "device_name": "Pixel 8"
    },
    {
      "id": "work",
      "latitude": 52.789012,
      "longitude": 5.123456,
      "radius": 100,
      "device_name": "Pixel 8"
    }
  ]
}
```

Field contract:

- `type` is always the string `zones`.
- `zones` is an array of zone objects. An empty array clears all zones.
- Each zone has:
  - `id` — string identifier matching the HA zone name.
  - `latitude`, `longitude` — center coordinates.
  - `radius` — in meters.
  - `device_name` — the device this zone applies to. The app ignores zones
    not matching its configured device name.

## Android App Changes

### New Dependencies

Add the PubNub Go SDK equivalent for Android (`pubnub:pubnub-android`) for
subscription. The app keeps its existing OkHttp REST publishing path — the SDK
is used only for the subscription side.

### New Components

#### `ZonesSubscriber`

- Creates a PubNub client instance using the app's subscribe key.
- Subscribes to the commands channel (configurable, default:
  `home-location-commands-{device_name}`).
- On connection, publishes a `request_zones` message to the existing channel
  to bootstrap the zone list.
- On message receipt, parses the `zones` type and forwards the zone list to
  `ZoneManager`.
- Reconnects automatically (handled by PubNub SDK).
- Starts when tracking is enabled and geofencing is enabled.
- Stops (unsubscribes) when tracking is stopped or geofencing is disabled.

#### `ZoneManager`

- Receives zone lists from `ZonesSubscriber`.
- Persists the zone list to SharedPreferences as a JSON string.
- On zone list change, calls `GeofencingClient.requestGeofences()` to register
  all zones as `GEOFENCE_TRANSITION_ENTER` and `GEOFENCE_TRANSITION_EXIT`
  geofences with `GEOFENCE_NON_MOVEMENT_GESTURE` monitoring.
- Each geofence is non-responsive to dwell (uses `LOITERING` loitering
  initial trigger) to reduce false positives.
- Exposes `currentZoneId` — the zone the device is currently inside, or `null`.
- Exposes `isInsideZone` — boolean for the coordinator.

#### `GeofenceReceiver`

- BroadcastReceiver for geofence transition intents from `GeofencingClient`.
- On `ENTER`: applies debounce — if the last zone transition was less than
  the debounce interval ago, the event is ignored. Records the zone entry
  time and notifies the coordinator.
- On `EXIT`: immediately notifies the coordinator (no debounce on exit — we
  want to resume tracking promptly).
- Declared in the manifest with `exported=false`.

### `LocationCoordinator` Changes

When `ZoneManager.isInsideZone` is true:

- `ensureRegistered()` switches the location request to a low-frequency mode
  instead of the normal stationary interval.
- Activity recognition remains active (to detect when the user leaves on foot).
- The geofence EXIT event is the primary way to resume normal tracking.
- The existing movement detection still works: if the user walks out of the
  zone boundary, either the geofence EXIT fires or accumulated distance
  triggers MOVING mode.

Low-frequency mode:

- Uses `PRIORITY_BALANCED_POWER_ACCURACY`.
- Interval: configurable, default 5 minutes.
- Max delay: same as interval (batching).
- Periodic fallback is not used in low-frequency mode (the low-frequency
  request itself serves as the cadence).

The coordinator gains a new method `onZoneEntered(zoneId)` and
`onZoneExited(zoneId)` called from `GeofenceReceiver`.

### `LocationSettings` Changes

New settings:

| Key | Type | Default | Description |
|---|---|---|---|
| `geofencing_enabled` | Boolean | `true` | Enable geofencing-based zone detection |
| `command_channel` | String | `home-location-commands-{device_name}` | PubNub channel for zone commands |
| `geofence_debounce_ms` | Long | `180_000` (3 min) | Minimum time between zone transitions |
| `low_frequency_interval_ms` | Long | `300_000` (5 min) | Location request interval inside a zone |
| `persisted_zones` | String (JSON) | `null` | Zone list received from PubNub |
| `current_zone_id` | String | `null` | Zone the device is currently inside |
| `last_zone_transition_ms` | Long | `0` | Timestamp of last zone transition |

The `command_channel` defaults to include the device name to support multiple
devices sharing the same PubNub keys. The listener can publish to a wildcard
channel or the app subscribes to a device-specific channel.

### `LocationRequestFactory` Changes

New method `forLowFrequency(intervalMs)` that builds a balanced-power request
at the configured low-frequency interval.

### `MovementStateController` Changes

No new state is added. The low-frequency mode is a coordinator-level concern:
it changes the location request cadence without affecting the state machine.
The state machine still transitions between STATIONARY and MOVING based on
existing evidence. Being inside a zone suppresses the stationary interval in
favor of the low-frequency interval, but does not change the state.

This keeps the state machine simple and avoids adding a third state that would
complicate transitions. The zone awareness is an orthogonal concern handled by
the coordinator.

### State Diagram Update

The movement state diagram is updated to note the low-frequency mode:

- A new annotation on the STATIONARY state describes the reduced cadence
  when inside a trusted zone.
- Geofence ENTER/EXIT are documented as coordinator-level events that affect
  the request interval, not the movement state.

### Settings UI

Two new settings in the Tracking behavior section:

- **Geofencing** — toggle to enable/disable zone-based tracking adjustment.
- **Low-frequency interval (minutes)** — numeric input, range 1–30, default 5.
- **Geofence debounce (minutes)** — numeric input, range 0–10, default 3.

### Diagnostics

All zone-related events are recorded in the diagnostic log:

- `zone_update received count=N` — when a zone list arrives
- `zone_geofences_registered count=N` — after geofence registration
- `zone_entered id=home debounce_ignored=false` — on ENTER events
- `zone_exited id=home` — on EXIT events
- `zone_low_frequency_active` — when switching to low-frequency mode
- `zone_low_frequency_inactive` — when resuming normal mode

## pubnub-listener Changes

### New Message Type: `request_zones`

Added to `route.go`:

```go
case "request_zones":
    return handleRequestZones(msg, cfg)
```

### `handleRequestZones`

1. Extract `device_name` from the message.
2. Query Home Assistant for zone definitions via:

   ```
   GET /api/states?prefix=zone.
   ```

   This returns all zone entities. Each zone entity has attributes for
   `latitude`, `longitude`, `radius`, and `passive`.

3. Build a `zones` message containing all zones, with the `device_name`
   field set to the requesting device.

4. Publish the `zones` message to the commands channel:
   `home-location-commands-{device_name}`.

The HA endpoint for listing zone states returns entities like:

```json
{
  "entity_id": "zone.home",
  "state": "home",
  "attributes": {
    "latitude": 52.123456,
    "longitude": 5.456789,
    "radius": 150,
    "passive": true,
    "id": "home"
  }
}
```

The listener extracts `id`, `latitude`, `longitude`, and `radius` from each
zone's attributes.

### New Configuration

| Variable | Required | Default | Description |
|---|---|---|---|
| `PN_COMMANDS_CHANNEL_PREFIX` | | `home-location-commands-` | Prefix for device-specific command channels |

The commands channel for a device is constructed as:
`{prefix}{device_name}`.

The listener already has the PubNub SDK for subscription; it uses the same
client to publish zone messages.

### Existing Behavior

- `location` messages continue to be handled as before.
- `request_zones` is a new message type that triggers a HA query and a
  response on the commands channel.
- Unknown message types continue to fall back to the Google Assistant handler.

## Zone Request Timing

The app requests zones in these scenarios:

1. **On tracking start** — when `LocationCoordinator.start()` is called and
   geofencing is enabled.
2. **On PubNub reconnection** — if the `ZonesSubscriber` reconnects after a
   disconnection, it re-requests zones to recover any updates missed during
   the gap.
3. **On app cold start** — if zones are already persisted, the app uses the
   cached zones immediately and requests fresh zones in the background.

This ensures the app works with cached zones even when PubNub is temporarily
unavailable, while staying in sync with HA.

## Debounce Behavior

When the device enters a geofence:

1. The current time is compared to `last_zone_transition_ms`.
2. If the difference is less than `geofence_debounce_ms`, the ENTER event is
   ignored and logged as `zone_entered id=... debounce_ignored=true`.
3. If the difference exceeds the debounce interval, the transition is applied
   and `last_zone_transition_ms` is updated.

EXIT events are not debounced — the app resumes normal tracking immediately
when leaving a zone. This prevents the device from being stuck in low-frequency
mode if it quickly moves between zones.

## Battery Impact

| Scenario | Before | After (Phase 1) |
|---|---|---|
| At home (inside zone) | Balanced fix every 2 min | Balanced fix every 5 min |
| Leaving home | Activity recognition triggers high-accuracy | Geofence EXIT → normal stationary interval (2 min) |
| In transit | High-accuracy 30 s | Unchanged |
| Arriving at work | 15-min hard cap → stationary | Geofence ENTER → low-frequency (5 min) |

The geofence monitoring itself uses Android's low-power geofence engine
(cell/Wi-Fi triangulation), which is significantly cheaper than requesting
fused location fixes. The primary saving is the reduced fix cadence inside
known zones.

Phase 2 (pause mode) will eliminate fixes entirely inside zones, with the
geofence EXIT event resuming normal tracking.

## Testing

### Android app

- Unit tests for `ZoneManager`: zone persistence, geofence registration,
  debounce logic.
- Unit tests for `ZonesSubscriber`: message parsing, device name filtering.
- Unit tests for `GeofenceReceiver`: ENTER/EXIT handling, debounce.
- Unit tests for `LocationCoordinator`: low-frequency mode activation,
  geofence-aware `ensureRegistered()`.
- Robolectric tests for geofence intent delivery.

### pubnub-listener

- Unit tests for `handleRequestZones`: HA API call, message construction,
  PubNub publish.
- Unit tests for route dispatch with `request_zones` type.
- Integration test: mock HA endpoint → listener → verify zones message.

## Future: Pause Mode (Phase 2)

When implemented, a `mode` field will be added to the zone message:

```json
{
  "id": "home",
  "latitude": 52.123456,
  "longitude": 5.456789,
  "radius": 150,
  "mode": "pause",
  "device_name": "Pixel 8"
}
```

- `pause` — stop all location requests, activity recognition, and publishing.
  Geofence EXIT resumes normal tracking.
- `low_frequency` — current behavior (reduced cadence).

The coordinator will gain a `paused` flag. When paused, `ensureRegistered()`
removes all registrations except the geofence monitoring and the PubNub
subscription. The geofence EXIT event clears the pause and calls
`ensureRegistered()` to resume.

## Open Questions

1. Should the app request zones periodically (e.g., every 6 hours) to catch
   HA zone changes without waiting for a tracking restart?
2. Should the listener filter zones by some criteria (e.g., only zones with
   `passive: false`)?
3. What is the maximum number of geofences? Android limits to 100 per app,
   which is generous for home/work use.
