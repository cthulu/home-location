# Android Docker Sandbox image

This image extends [`nikbucher/sbx-opencode2`](https://github.com/nikbucher/sbx-opencode2) with JDK 21 and Android SDK 36, matching this project's Java target and `compileSdk`/`targetSdk`. The image is explicitly built for `linux/arm64` (AArch64), and the JDK runs natively.

The Android SDK is installed under `/opt/android-sdk`, with `ANDROID_HOME` and `ANDROID_SDK_ROOT` set. The image includes Android platform 36, Build Tools 36.0.0, and platform-tools. **Google's Linux SDK command-line tools and native platform-tools (including `adb`) are x86-64 binaries, not AArch64.** They therefore require x86-64 QEMU/binfmt emulation in the sandbox runtime; this is not a fully native Android toolchain. Configure Docker's x86-64 emulation support for the SBX runtime before building/running this image. On Linux Docker hosts, this is commonly registered with `docker run --privileged --rm tonistiigi/binfmt --install amd64`; Docker Desktop manages emulation separately. If the SBX runtime does not provide binfmt emulation, Gradle's native Android build tools will not run in this ARM64 image.

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

To confirm the toolchain is available:

```sh
java -version
sdkmanager --list_installed
```
