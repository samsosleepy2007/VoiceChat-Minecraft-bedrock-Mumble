# VC Mumble Item Mic v1.0.0

This addon is adapted from the project's earlier VoiceCraft Item Mic addon for the VC Mumble Server + Endstone architecture.

## Behaviour

- Namespace: `vcmumble:*`
- Hold-to-Talk: holding the Mic routes the speaker; releasing it blocks the speaker in VC Mumble Server.
- Toggle: selecting the Mic toggles the routed microphone state.
- Offhand Mic forces ON.
- Voice Range is stored by the Endstone plugin and sent to Android as the speaker's `voiceRange`.
- Mumble username pairing is stored by Endstone. If no explicit pair exists, Minecraft name is used automatically.

## Addon -> Endstone scoreboard tags

Mic state:

```text
vcmumble.mic.on
vcmumble.mic.off
```

Voice range:

```text
vcmumble.vr.request.<requestId>.<blocks>
vcmumble.vr.sync.<requestId>
vcmumble.vr.ack.<requestId>.<status>.<blocks>
vcmumble.vr.value.<blocks>
vcmumble.vr.max.<blocks>
```

Mumble username:

```text
vcmumble.pair.request.<requestId>.<mumbleName>
vcmumble.pair.unpair.<requestId>
vcmumble.pair.sync.<requestId>
vcmumble.pair.ack.<requestId>.<status>[.<mumbleName>]
vcmumble.pair.value.<mumbleName>
```

The Item Mic UI restricts explicit Mumble usernames to ASCII letters, digits, underscore, hyphen and dot so requests are safe to transport through scoreboard tags. The normal `/vcmumble pair` command remains available for other names.

## Bridge protocol

Endstone 0.3.0 adds an optional backwards-compatible field to `player_state`:

```json
{"micEnabled": true}
```

Older clients ignore it. VC Mumble Server beta.5 reads it and forwards the value into native proximity state.

Native routing checks the speaker's Mic state before normal distance/dimension routing. A player without the Item Mic tags defaults to normal speech routing so the addon remains optional.

## Required versions

- VC Mumble Item Mic: 1.0.0
- Endstone VC Mumble plugin: 0.3.0+
- VC Mumble Server Android: 0.6.0-beta.5+

All Mumble users may remain in Root.
