Target repository: `samsosleepy2007/VoiceChat-Minecraft-bedrock-Mumble`

# GitHub test APK build

Target branch: `test/android-apk`

Workflow: `.github/workflows/android-debug.yml`

Expected artifact contents:

- `app-debug.apk`
- `app-debug.apk.sha256`

The workflow builds the smoke APK on GitHub Actions using JDK 17, Android SDK 36 and Gradle 9.6. It does not merge into `main`.
