# Roadmap

## Implemented in source

- [x] App name: VC Mumble Server
- [x] Android project scaffold
- [x] Foreground server service
- [x] One-tap Quick Start UI
- [x] Persist server name, port, password, max users and voice range
- [x] Android Keystore protection for bridge secret
- [x] Native ARM64 bridge / smoke-test path
- [x] Same configurable Mumble port on TCP and UDP in smoke mode
- [x] Standalone VC Mumble Endstone plugin
- [x] Direct Android -> Endstone TCP bridge on port 27220
- [x] Protocol v1 HMAC-SHA256 challenge-response authentication
- [x] Full snapshot + incremental player-state sync
- [x] Minecraft XUID -> Mumble username pairing
- [x] Per-player voice range
- [x] Native Minecraft proximity state table
- [x] Same-dimension and squared-distance routing rules
- [x] Stale/missing state fail-closed behavior
- [x] `Server::processMsg()` regular-speech receiver filter preparation
- [x] Pin Mumble 1.5.915 source
- [x] Verify upstream archive by SHA-256
- [x] Reproducible Mumble Android source patcher
- [x] Embedded-safe Mumble lifecycle
- [x] App-private Mumble INI/SQLite configuration
- [x] QtService / Qt Android AAR embedding path
- [x] Standalone bridge integration/static tests
- [x] VC Mumble Endstone v0.1.1 verified loading on Endstone 0.11.10 test server

## Validation gates still required

- [ ] Cross-compile the full pinned Mumble core successfully in GitHub CI
- [ ] Verify the generated Qt AAR contains the native server and required Qt runtime/plugins
- [ ] Install core APK on a physical Android ARM64 device
- [ ] Verify stock Mumble Android/Desktop clients authenticate and join Root
- [ ] Verify two stock clients exchange real voice
- [ ] Verify Android app authenticates to the standalone Endstone plugin over a real public allocation
- [ ] Verify `/vcmumble pair` identity mapping live
- [ ] Verify Minecraft movement changes receiver routing live
- [ ] Verify dimension change isolates voice immediately
- [ ] Verify foreground service survives screen-off/background tests
- [ ] Verify Stop -> Start cleanly releases and reopens database/sockets

## Hardening / later phases

- [ ] TLS for direct Endstone bridge transport
- [ ] Secret rotation command/UI
- [ ] Whisper / normal / shout ranges
- [ ] Distance attenuation compatibility tests
- [ ] Wall/occlusion policy
- [ ] Team/radio channels
- [ ] Public TCP+UDP relay/tunnel mode for CGNAT/mobile data
- [ ] Release signing and APK release workflow
