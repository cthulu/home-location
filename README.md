# Home Location

Separate Android application for publishing movement-aware device location to PubNub. A separate subscriber forwards accepted coordinates to Home Assistant's `input_number` helpers.

The research, architecture, payload contract, permissions, and implementation plan are in [`specs/`](specs/).

## Foundation

This project includes the Android/Kotlin/Gradle foundation copied from Quick Add, with the Keep and Calendar feature code removed. The application package is `nl.freshlytyped.homelocation`.

Build with:

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

## OpenCode in Docker Sandbox

To run this repository with OpenCode V2 in a Docker Sandbox, build and load the project-specific `linux/arm64` (AArch64) image from the repository root. It extends the [OpenCode V2 sandbox image](https://github.com/nikbucher/sbx-opencode2) with JDK 21 and Android SDK 36 required by this project:

```bash
docker build --platform linux/arm64 -f .sandbox/Dockerfile -t home-location-android .
docker image save home-location-android -o /tmp/home-location-android.tar
sbx template load /tmp/home-location-android.tar
sbx run --template home-location-android opencode
```

Run `sbx run` from this repository's directory so Docker Sandboxes makes the repo available as the workspace. Google's Android native tools are x86-64, so the SBX runtime must provide x86-64 QEMU/binfmt emulation; only the image and JDK are native AArch64. See [`.sandbox/README.md`](.sandbox/README.md) for this limitation, emulation setup, image contents, and verification commands. Once OpenCode starts, use `./gradlew assembleDebug` or `./gradlew testDebugUnitTest`.

Location functionality is intentionally not implemented yet; the specifications define the next implementation phases.
