# Movement detection state diagram

![Movement detection state diagram](movement-state-diagram.png)

The movement controller has two persisted states. Location updates, activity
recognition, Wi-Fi changes, and the burst-expiry alarm are the inputs. The
foreground service does not change the state machine; it owns delivery of
location callbacks while the coordinator applies these transitions.

```mermaid
stateDiagram-v2
    [*] --> STATIONARY

    STATIONARY --> MOVING: moving activity confidence >= 70%
    STATIONARY --> MOVING: reliable fix distance >= threshold
    STATIONARY --> STATIONARY: Wi-Fi lost / balanced verification
    STATIONARY --> STATIONARY: fix rejected (accuracy worse than 50 m)

    MOVING --> MOVING: moving activity / refresh evidence time
    MOVING --> MOVING: moving fix / refresh evidence time
    MOVING --> MOVING: Wi-Fi lost / refresh evidence time

    MOVING --> STATIONARY: burst alarm and no evidence
    MOVING --> STATIONARY: service start and moving state > 15 min
    MOVING --> STATIONARY: runtime high-accuracy limit
    STATIONARY --> MOVING: location distance threshold during activity cooldown

    STATIONARY --> STATIONARY: still activity
    STATIONARY --> STATIONARY: fix below movement threshold

    note right of MOVING
      High-accuracy location request
      Periodic fallback is cancelled
    end note

    note right of STATIONARY
      Balanced-power location request
      Periodic fallback may be scheduled

      Inside a known zone: tracking is paused entirely.
      Location requests, activity recognition, and publishing
      are stopped. Only geofence monitoring and the PubNub
      subscription remain active. Geofence EXIT resumes normal
      tracking. Enter events are debounced to suppress flapping.
    end note
```

## Transition details

### `STATIONARY` to `MOVING`

The coordinator switches to `MOVING` when it receives one of these signals:

- Activity recognition reports walking, running, cycling, on foot, or in a
  vehicle with at least 70% confidence.
- Consecutive location fixes with accuracy of 50 m or better accumulate at
  least the configured movement distance.

Fixes with worse accuracy are discarded before they reach the state machine
and can never switch the device into high-accuracy mode. Wi-Fi loss triggers
only a balanced one-shot verification fix; it no longer switches state
directly. The stationary scan defaults to a 2-minute interval with batched
delivery, similar to Home Assistant Companion's background cadence.

The high-accuracy request is then registered and the burst-expiry alarm is
scheduled.

### `MOVING` to `STATIONARY`

The coordinator returns to `STATIONARY` when the burst-expiry alarm fires and
no recent movement evidence exists. A moving state that has survived a
service restart for more than 15 minutes is also reset to stationary to avoid
leaving high-accuracy tracking active indefinitely.

During a running session, the same limit applies even when activity recognition
continually reports movement. Activity-only movement evidence is ignored during
the cooldown after that reset; a location fix crossing the configured distance
threshold can resume moving mode.

### Registration rule

Location and activity registrations are idempotent. A callback must process
its fixes without re-registering either provider. Registration is only changed
when the movement state or request configuration changes, or when registration
health recovery is required.

If movement-state logic changes, update this diagram and its transition details
in the same change.
