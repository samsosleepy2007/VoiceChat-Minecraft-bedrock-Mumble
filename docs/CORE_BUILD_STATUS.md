# Embedded Mumble Core build status

This file tracks explicit Android embedded-Mumble build checkpoints.

- Baseline branch: `test/vc-mumble-github-import`
- Baseline commit: `4679d074a2c041efb6874fb50418ef6595b4dea5`
- Goal: build a real ARM64 Android APK with the pinned Mumble 1.5.915 server core and validate it before merging to `main`.
- Current checkpoint: rerun the embedded core workflow against the latest fixes.

Do not merge this work to `main` until the embedded core APK builds successfully and a real Mumble client can connect.
