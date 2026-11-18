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
    STATIONARY --> MOVING: coarse fix beyond threshold plus its accuracy
    STATIONARY --> STATIONARY: coarse fix within threshold plus its accuracy

    MOVING --> MOVING: moving activity / refresh evidence time
    MOVING --> MOVING: moving fix / refresh evidence time
    MOVING --> MOVING: Wi-Fi lost / refresh evidence time

    MOVING --> STATIONARY: burst alarm and no evidence
    MOVING --> STATIONARY: service start and moving state > 15 min
    MOVING --> STATIONARY: runtime 15-minute moving limit
    STATIONARY --> MOVING: location distance threshold during activity cooldown

    STATIONARY --> STATIONARY: still activity
    STATIONARY --> STATIONARY: fix below movement threshold

    note right of MOVING
      Balanced-power location request continues
      GPS one-shot fix on entry, then every 5 min
      (only with "High accuracy while moving")
      Without GPS: coarse fixes up to 200 m are published
      Periodic fallback is cancelled
    end note

    note right of STATIONARY
      Balanced-power location request
      Periodic fallback may be scheduled

      Inside a known zone: tracking is paused entirely.
      Location requests, activity recognition, and publishing
      are stopped. Geofence monitoring, the PubNub subscription
      and the heartbeat remain active — the heartbeat re-registers
      the never-expiring geofences so zone exit survives process
      death and geofence loss. Geofence EXIT resumes normal
      tracking. Enter events are debounced to suppress flapping,
      and geofences also use a dwell delay and an initial ENTER
      trigger so a device booted inside a zone pauses immediately.
      An ENTER is confirmed against a location fix (see
      "Zone entry confirmation") before tracking pauses.
      While a zone is current, an ENTER for a *different* zone can confirm a
      zone switch from the event's triggering fix (see "Zone switch"), so a
      stale pause resolves itself without waiting for an EXIT.
      The heartbeat also checks one fix against the current zone and exits
      it when the fix is clearly outside (see "Current zone check").
    end note
```

## Transition details

### `STATIONARY` to `MOVING`

The coordinator switches to `MOVING` when it receives one of these signals:

- Activity recognition reports walking, running, cycling, on foot, or in a
  vehicle with at least 70% confidence.
- Consecutive location fixes with accuracy of 50 m or better accumulate at
  least the configured movement distance.
- A coarse fix (accuracy worse than 50 m) lies at least the configured
  movement distance plus its own accuracy away from the last reliable fix.

Coarse fixes never accumulate distance and never replace the last reliable
fix. Requiring the whole uncertainty circle to clear the threshold keeps
coarse jitter around a stationary device from switching into moving mode,
while still detecting travel when the balanced request only
yields network fixes. Wi-Fi loss triggers only a balanced one-shot verification
fix; it no longer switches state directly. The continuous scan defaults to a
2-minute interval with batched delivery, similar to Home Assistant Companion's
background cadence.

The burst-expiry alarm is then scheduled.

### GPS while `MOVING`

The continuous location request is balanced power accuracy in both states, so
GPS is never left running. When "High accuracy while moving" is enabled and
fine location is granted, the coordinator takes a GPS one-shot fix (30 s
timeout) on entering `MOVING` and then at most once per GPS fix interval
(5 minutes by default, configurable in settings). The check runs on each
continuous location callback, so it needs no alarm of its own; the effective
interval is the configured one rounded up to the next callback. A GPS fix goes
through the normal fix handling: it updates the last reliable fix and is
published by the filter.

When the switch is off, or only approximate location is granted, GPS is never
used. While `MOVING`, coarse fixes are then published with an accuracy cap of
200 m (or the configured publish cap if larger), and only when the distance
from the last published position exceeds the fix's own accuracy, so coarse
jitter is not reported as movement. In `STATIONARY` the normal 50 m publish
cap applies either way.

### `MOVING` to `STATIONARY`

The coordinator returns to `STATIONARY` when the burst-expiry alarm fires and
no recent movement evidence exists. A moving state that has survived a
service restart for more than 15 minutes is also reset to stationary to avoid
leaving moving mode (and its GPS fixes) active indefinitely.

During a running session, the same limit applies even when activity recognition
continually reports movement. Activity-only movement evidence is ignored during
the cooldown after that reset; a location fix crossing the configured distance
threshold can resume moving mode. The fixes in the batch that triggers the reset
are still evaluated and published, so a device that is really travelling
resumes moving mode immediately instead of going dark for the cooldown.

### Zone entry confirmation

A geofence ENTER (or DWELL) does not pause tracking by itself; it starts a
pending entry that must be confirmed by a location fix. This keeps a coarse
fix from briefly pausing tracking for a zone the device is not really in.

A null triggering fix (the engine decided by other means), or one that is
merely stale, outside the radius, or too coarse to trust, still arms the entry.
A triggering fix coarser than the zone-aware cap is not silently discarded:
the geofence engine decided the device is inside the zone, so the entry is kept
pending and the coarse trigger is recorded in the arming diagnostics. The
bounded confirmation window is the backstop: it expires the entry if no usable
fix arrives.

The confirmation is background-capable and tries fixes in this order:

1. The geofence event's **triggering location**, confirmed immediately. This
   is the fix the geofence engine already used to decide the transition, so a
   usable fix pauses tracking without waiting for a background-frozen delay or
   a fresh one-shot.
2. If the triggering fix is not usable, a **one-shot** request after the entry
   hysteresis delay, allowed to answer with a recent cached fix.

A fix confirms the entry when it is not stale and its fix point is inside the
zone radius. Its accuracy is checked against a zone-aware cap — the normal
publish cap widened to the zone radius — so a coarse-but-plausible fix (for
example an indoor Wi-Fi fix) can confirm a large zone without waiting for
perfect GPS. On confirmation the entry fix is published and tracking pauses.

A single rejection does not drop the entry. A fix that is merely not usable
(stale, or outside the radius) leaves the entry pending, and each subsequent
location update re-evaluates it, so the entry is confirmed as soon as a usable
fix arrives. A missing zone clears it immediately. The pending entry is bounded
by a confirmation window (10 minutes): if no usable fix arrives within it, the
entry expires rather than lingering. The window is checked on each update and on
the heartbeat as a backstop. The pending entry also tracks the persisted zone
list: if a new zone list no longer contains the armed zone, the entry is dropped
immediately rather than lingering until the window expires.

### Zone switch

While a zone is current the coordinator is paused with no ongoing location
request, so the only fix it can act on is a geofence event's triggering
location. If the geofence engine then reports an ENTER (or DWELL) for a
*different* zone — which is the normal signal that the device really left the
current zone — the coordinator attempts a **zone switch** instead of (or in
addition to) arming a pending entry:

- The triggering fix must validate against the entered zone's zone-aware
  accuracy cap (same rules as entry confirmation),
- its fix point must sit inside the entered zone, and
- it must lie outside the current zone (guards against a coarse fix that is
  inside both).

When all three hold, the fix is published under the entered zone's zone-aware
cap (so the tracker sees the device leave the old zone and arrive in the new
one), the entered zone becomes current, and the pause persists — the device is
inside a zone either way, and a real EXIT for it resumes tracking as usual.

The switch is attempted once from the triggering fix and, if that fails, from
the pending entry's one-shot fallback. Ordinary location updates are not
delivered while paused, so they cannot confirm a switch. A stale pause (the
device is really elsewhere but the current zone was never exited) resolves
itself with the first usable fix that proves the device is in the other zone.
Every rejected switch attempt is recorded in the diagnostics. A null triggering
fix can confirm a switch only through the one-shot fallback, and an ENTER for
the zone that is already current changes nothing.

### Current zone check

A switch only helps when the device reaches another zone. Leaving a zone for a
place that is not a zone produces no ENTER, so a lost EXIT would otherwise keep
tracking paused indefinitely. On every heartbeat while a zone is current, the
coordinator requests one balanced fix (a cached fix up to 2 minutes old is
allowed) and treats the zone as exited when the fix is outside the radius by
more than its own accuracy (`distance - accuracy > radius`). The exit then
follows the normal zone exit path, including the departure publish. A fix
inside, or too uncertain to tell, keeps the pause. A current zone that no
longer exists in the zone list is exited as well.

A device paused in a zone has no location or activity requests that would
wake it, so the heartbeat is a wakeup alarm, and its broadcast stays open
(bounded at 45 seconds) until the check's fix arrives or fails. Otherwise the
check would wait in Doze for an unrelated wakeup, or the device would sleep
before the fix was delivered.

When a zone list update removes the current zone, tracking resumes immediately
instead of waiting for the next heartbeat.

### Zone exit

A geofence EXIT resumes tracking immediately (it is not debounced, so a
boundary-induced exit recovers promptly). The exit's triggering location — the
fix the geofence engine used to decide the device left the zone — is published
as a best-effort departure point, so Home Assistant sees the device move out of
the zone right away rather than waiting for the first post-exit fix. The
zone-aware accuracy cap applies, so a coarse fix still registers for a large
zone. A stale exit (the device is not currently in the zone) is ignored, and an
exit while a matching entry is still pending cancels that entry.

### Registration rule

Location and activity registrations are idempotent. A callback must process
its fixes without re-registering either provider. Registration is only changed
when the movement state or request configuration changes, or when registration
health recovery is required.

If movement-state logic changes, update this diagram and its transition details
in the same change.
