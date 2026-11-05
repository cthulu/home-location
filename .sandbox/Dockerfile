FROM --platform=linux/arm64 nikbucher/sbx-opencode2:latest

USER root

ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH="${PATH}:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools"

RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates \
        openjdk-21-jdk \
        xz-utils \
        wget \
    && rm -rf /var/lib/apt/lists/* \
    && wget -q "https://github.com/HomuHomu833/android-sdk-custom/releases/download/36.0.1/android-sdk-aarch64-linux-gnu.tar.xz" \
        -O /tmp/android-sdk.tar.xz \
    && echo "6882c56d8f097c7fd8292caf101485758c06b40bcee13959e34ccf423e4ef459  /tmp/android-sdk.tar.xz" \
        | sha256sum -c - \
    && mkdir -p "${ANDROID_HOME}" \
    && tar -xJf /tmp/android-sdk.tar.xz --strip-components=1 -C "${ANDROID_HOME}" \
    && rm /tmp/android-sdk.tar.xz \
    && mv "${ANDROID_HOME}/cmdline-tools" "${ANDROID_HOME}/cmdline-tools-custom" \
    && mkdir -p "${ANDROID_HOME}/cmdline-tools/latest" \
    && mv "${ANDROID_HOME}/cmdline-tools-custom/"* "${ANDROID_HOME}/cmdline-tools/latest/" \
    && rmdir "${ANDROID_HOME}/cmdline-tools-custom" \
    && "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" --sdk_root="${ANDROID_HOME}" "platforms;android-36" \
    && test -d "${ANDROID_HOME}/platforms/android-36" \
    && test -d "${ANDROID_HOME}/build-tools" \
    && mkdir -p /home/agent/.gradle \
    && printf '%s\n' "android.aapt2FromMavenOverride=${ANDROID_HOME}/build-tools/36.1.0/aapt2" \
        > /home/agent/.gradle/gradle.properties \
    && chown -R agent:agent "${ANDROID_HOME}" /home/agent/.gradle

USER agent
