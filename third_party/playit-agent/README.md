# playit-agent source pin

VC Mumble Server experimentally embeds Android ARM64 executables built from the upstream playit-agent source.

- Upstream: `playit-cloud/playit-agent`
- Tag: `v1.0.10`
- Commit: `9e7b9a1cb42d057e7993e21ef4fe32348d1e7fcd`
- License: BSD-2-Clause

The upstream source is not vendored here. CI fetches the pinned tag, verifies the exact commit, cross-compiles with Android NDK, and stages the resulting executables as native APK payloads.
