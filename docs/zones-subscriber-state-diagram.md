# ZonesSubscriber State Diagram

```mermaid
stateDiagram-v2
    [*] --> STOPPED

    STOPPED --> CONNECTING: start() called, subscribe key configured
    CONNECTING --> STREAMING: HTTP stream connected
    CONNECTING --> STOPPED: start() cancelled before connect
    CONNECTING --> CONNECTING: connection error, retry delay

    STREAMING --> REQUESTING_ZONES: stream connected, request zones
    STREAMING --> STREAMING: message received, parse zones
    STREAMING --> STREAMING: message received, not zones type
    STREAMING --> CONNECTING: stream timeout (120s) or closed
    STREAMING --> CONNECTING: network error during read
    STREAMING --> STOPPED: stop() called

    REQUESTING_ZONES --> STREAMING: request_zones published
    REQUESTING_ZONES --> STREAMING: request_zones failed (transient)

    CONNECTING --> STOPPED: stop() called during retry delay

    note right of CONNECTING
      Retry delay: 10-30s with exponential backoff
      Max backoff: 5 minutes
      Resets on successful connection
    end note

    note right of STREAMING
      Reads lines from HTTP stream until closed.
      Each line is a PubNub message envelope.
      Timetoken extracted for reconnection.
    end note

    note right of REQUESTING_ZONES
      Publishes request_zones to main channel.
      Listener responds with zones message on commands channel.
      Falls through to STREAMING regardless of success.
    end note
```

## State Transitions

### `STOPPED` → `CONNECTING`
- Triggered by `LocationCoordinator.start()` when geofencing is enabled
- Opens HTTP GET to `/subscribe/{key}/{channel}/{timetoken}/0`

### `CONNECTING` → `STREAMING`
- HTTP connection established successfully (200 OK)
- Begins reading lines from response body

### `CONNECTING` → `CONNECTING`
- Connection failed (DNS error, refused, timeout, 4xx/5xx response)
- Waits with exponential backoff (10s → 20s → 40s → ... → 5min max)
- Resets backoff counter on successful connection

### `STREAMING` → `REQUESTING_ZONES`
- On first successful connection, publishes `request_zones` to main channel
- On reconnection after a gap > 30s, re-requests zones to recover missed updates

### `STREAMING` → `STREAMING` (message)
- Line received from stream, parsed as `{"t":[{...}]}...`
- If `message.type == "zones"` and device name matches: forward to `ZoneManager`
- Extract timetoken for reconnection
- Ignore non-zones messages silently

### `STREAMING` → `CONNECTING`
- Stream closed normally (read timeout after 120s of inactivity)
- Network error during read (socket closed, interrupted)
- Parse error on a line (skip the line, continue reading)

### `STREAMING` → `STOPPED`
- `stop()` called from `LocationCoordinator.stop()`
- Cancels the coroutine, closes the HTTP response

### `REQUESTING_ZONES` → `STREAMING`
- `request_zones` published successfully, or failed (transient error)
- In both cases, continue listening for zones messages

## Error Handling

| Error | Handling |
|-------|----------|
| DNS resolution failure | Retry with backoff |
| Connection refused | Retry with backoff |
| HTTP 401/403 (auth error) | Log warning, retry with extended delay (2min) |
| HTTP 404 (channel not found) | Log warning, retry with extended delay |
| HTTP 5xx (server error) | Retry with backoff |
| Read timeout (120s) | Normal reconnection, no backoff increase |
| Socket closed during read | Retry with backoff |
| JSON parse error on line | Skip line, continue reading |
| `request_zones` publish fails | Log warning, continue to streaming state |
| Coroutine cancelled (`stop()`) | Clean shutdown, release HTTP resources |
