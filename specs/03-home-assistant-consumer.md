# Home Assistant Consumer Contract

The separate PubNub consumer receives messages from the configured channel. Location messages are identified by `type: "location"`.

## Location message format

The mobile app sends one JSON object as the PubNub message body using the REST publish endpoint. The message contains both coordinates:

```json
{
  "type": "location",
  "latitude": 52.123456,
  "longitude": 5.123457,
  "accuracy": 5.0,
  "timestamp": 1792138781414
}
```

Field contract:

- `type` is always the string `location`.
- `latitude` and `longitude` are JSON numbers rounded to six decimal places.
- `accuracy` is the reported horizontal accuracy in metres, rounded to one decimal place. It is JSON `null` when the Android fix has no finite accuracy.
- `timestamp` is the Android location-fix timestamp in Unix epoch milliseconds, not the time the network request completed.

The payload is published with `Content-Type: application/json` to:

```text
POST https://ps.pndsn.com/publish/{publish-key}/{subscribe-key}/0/{channel}/0
```

An optional PubNub API key is sent in the `x-api-key` header. PubNub response metadata is not part of the location message. The Home Assistant token must never be included in this payload.

Validation rules:

- Latitude must be between `-90` and `90`.
- Longitude must be between `-180` and `180`.
- Timestamp must be recent enough for the deployment's freshness policy.
- Accuracy should be retained for diagnostics and may be used to reject poor fixes.

For each accepted location message, call:

```http
POST /api/services/input_number/set_value
Authorization: Bearer <HOME_ASSISTANT_TOKEN>
Content-Type: application/json
```

Once for each helper:

```json
{"entity_id":"input_number.phone_latitude","value":52.123456}
```

```json
{"entity_id":"input_number.phone_longitude","value":5.123456}
```

The consumer should make the two calls idempotently, handle retries, and avoid accepting malformed or old messages.
