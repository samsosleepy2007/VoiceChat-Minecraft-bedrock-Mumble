# Embedded Mumble Core build status

This file tracks explicit Android embedded-Mumble build checkpoints.

- Branch: `test/vc-mumble-github-import`
- Original validated baseline: `4679d074a2c041efb6874fb50418ef6595b4dea5`
- Previous failed core checkpoint: `52b656f494e5a6dda5dfa2e226a9da836b1bc061` (Mumble 1.5.915 expected Qt5 while the Android runtime is Qt 6.8.3)
- Current source: Mumble `1.6.870` Qt6 RC, pinned to the official release archive and SHA-256 digest.
- Android Qt runtime: Qt `6.8.3` arm64-v8a.
- Current checkpoint: validate the Mumble 1.6.870 source patch, native build, Qt AAR packaging, and core APK end-to-end in GitHub Actions.

Do not merge this work to `main` until the embedded core APK builds successfully and a real Mumble client can connect to the server on an ARM64 Android device.
