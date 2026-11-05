# Android Docker Sandbox image

This image extends [`nikbucher/sbx-opencode2`](https://github.com/nikbucher/sbx-opencode2) with JDK 21 and the AArch64 GNU/Linux build of [Android SDK Custom](https://github.com/HomuHomu833/android-sdk-custom) 36.0.1. The archive supplies AArch64 SDK tools and Build Tools 36.1.0; sdkmanager installs Android platform 36 to match this project's `compileSdk`/`targetSdk`. The image is explicitly built for `linux/arm64` (AArch64), so both the JDK and Android SDK tools run natively.

The Android SDK is installed under `/opt/android-sdk`, with `ANDROID_HOME` and `ANDROID_SDK_ROOT` set. The image downloads a version-pinned SDK archive and verifies its SHA-256 checksum during the build. The archive supplies AArch64 SDK command-line tools, platform-tools, and build-tools; sdkmanager installs the Android 36 platform data package. Gradle is configured to use the SDK's AArch64 `aapt2` rather than downloading its x86-64 Maven binary. No x86-64 emulation is required.

Build and load the image from the repository root:

```sh
docker build --platform linux/arm64 -f .sandbox/Dockerfile -t home-location-android .
docker image save home-location-android -o /tmp/home-location-android.tar
sbx template load /tmp/home-location-android.tar
```

Start OpenCode from the repository directory so the repo is available as the sandbox workspace:

```sh
sbx run --template home-location-android opencode
```

Inside the sandbox, run the project's Gradle tasks as usual:

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

If the mounted workspace contains a host-specific `local.properties`, make sure its `sdk.dir` does not point to the host's SDK; remove that entry inside the sandbox so Gradle uses `ANDROID_HOME`.

To confirm the toolchain is available:

```sh
java -version
sdkmanager --list_installed
```
