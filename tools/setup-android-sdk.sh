#!/bin/sh
# Local-only Android toolchain, no sudo. Installs into ~/Android.
set -e
ROOT=$HOME/Android
SDK=$ROOT/sdk
JDK=$ROOT/jdk

mkdir -p "$SDK" "$ROOT"

if [ ! -x "$JDK/bin/javac" ]; then
    echo "[1/4] downloading JDK 17"
    curl -fsSL -o "$ROOT/jdk.tar.gz" \
      "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
    mkdir -p "$JDK"
    tar xzf "$ROOT/jdk.tar.gz" -C "$JDK" --strip-components=1
    rm -f "$ROOT/jdk.tar.gz"
fi
echo "java: $("$JDK/bin/java" -version 2>&1 | head -1)"

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "[2/4] downloading cmdline-tools"
    curl -fsSL -o "$ROOT/cmdline-tools.zip" \
      "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    mkdir -p "$SDK/cmdline-tools"
    cd "$SDK/cmdline-tools"
    python3 -c "import zipfile;zipfile.ZipFile('$ROOT/cmdline-tools.zip').extractall('.')"
    rm -rf latest && mv cmdline-tools latest
    rm -f "$ROOT/cmdline-tools.zip"
fi

export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$PATH"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"

echo "[3/4] accepting licenses"
yes 2>/dev/null | sdkmanager --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true

echo "[4/4] installing packages"
sdkmanager --sdk_root="$SDK" \
    "platform-tools" \
    "platforms;android-37" \
    "build-tools;37.0.0" \
    "ndk;27.2.12479018" \
    "cmake;3.22.1"

echo "DONE"
sdkmanager --sdk_root="$SDK" --list_installed | head -20
