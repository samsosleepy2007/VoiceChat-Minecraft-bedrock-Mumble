# Upstream Mumble integration (Phase 2)

This directory is reserved for the pinned upstream Mumble server source.

Planned baseline: Mumble 1.5.x stable line. Before vendoring, pin an exact release/tag and preserve its license notices.

Integration goals:

1. Build server-only components for Android arm64-v8a.
2. Expose server lifecycle through the `vcserver` native bridge.
3. Preserve the stock Mumble wire protocol so official Mumble/Mumla clients remain unmodified.
4. Add a small Minecraft player-state receiver fed by the Endstone bridge.
5. Apply proximity filtering in the server audio receiver selection path (`Server::processMsg` area) without decoding/re-encoding Opus.

Do not replace the Phase 1 socket smoke-test with claims of Mumble compatibility until an actual Mumble client can authenticate, join, and exchange audio against the APK.
