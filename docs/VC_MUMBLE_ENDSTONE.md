# VC Mumble Endstone protocol v1

VC Mumble is intentionally separate from VoiceCraft.

## Transport

Authenticated UTF-8 NDJSON over TCP. The Endstone plugin listens; the Android app connects.

Client hello does **not** transmit the secret:

```json
{"type":"hello","role":"vc_mumble_server","protocol":1}
```

The server returns an `auth_challenge` nonce. The app replies with HMAC-SHA256 of `vc-mumble-v1:<nonce>` using the shared secret. Only then does the server return `hello_ok`.

The plugin then emits `sync_begin`, zero or more `player_state`, and `sync_end` messages. Incremental `player_state` and `player_leave` messages follow while players move or disconnect.

`player_state` includes `mumbleName` and `voiceRange`; the Android app should use those values for proximity identity/range instead of assuming the Mumble name equals the Minecraft name.
