# Home Assistant Consumer Contract

The separate PubNub consumer receives messages from the configured channel. Location messages are identified by `type: "location"`.

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
