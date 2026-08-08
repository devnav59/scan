#!/bin/bash
# Build Release APK - robust script that works even if gradle-wrapper.jar is missing
set -e

echo "=== TradeScanner Build Script ==="

# Check for keystore
if [ ! -f "app/keystore/tradescanner.jks" ]; then
  echo "Keystore not found, generating public keystore..."
  mkdir -p app/keystore
  if command -v keytool >/dev/null 2>&1; then
    keytool -genkeypair -v \
      -keystore app/keystore/tradescanner.jks \
      -keyalg RSA -keysize 2048 -validity 10000 \
      -alias tradescanner -storepass tradescanner -keypass tradescanner \
      -dname "CN=TradeScanner Public, OU=Dev, O=TradeScanner, L=Tehran, ST=Tehran, C=IR"
  else
    echo "keytool not found, trying openssl..."
    openssl genrsa -out /tmp/key.pem 2048
    openssl req -new -x509 -key /tmp/key.pem -out /tmp/cert.pem -days 10000 -subj "/CN=TradeScanner Public/OU=Dev/O=TradeScanner/L=Tehran/ST=Tehran/C=IR"
    openssl pkcs12 -export -out app/keystore/tradescanner.jks -inkey /tmp/key.pem -in /tmp/cert.pem -name tradescanner -password pass:tradescanner
    rm -f /tmp/key.pem /tmp/cert.pem
  fi
  echo "Keystore generated"
fi

# Ensure properties
cat > app/keystore/keystore.properties <<EOF
storeFile=keystore/tradescanner.jks
storePassword=tradescanner
keyAlias=tradescanner
keyPassword=tradescanner
EOF

# Check for wrapper jar
if [ ! -s "gradle/wrapper/gradle-wrapper.jar" ]; then
  echo "gradle-wrapper.jar missing or empty, regenerating..."
  if command -v gradle >/dev/null 2>&1; then
    gradle wrapper --gradle-version 8.6
  else
    echo "Gradle not found, installing via sdkman or apt is recommended"
    echo "Attempting to download wrapper jar via curl..."
    mkdir -p gradle/wrapper
    curl -L -o gradle/wrapper/gradle-wrapper.jar https://github.com/gradle/gradle/raw/v8.6.0/gradle/wrapper/gradle-wrapper.jar || \
    wget -O gradle/wrapper/gradle-wrapper.jar https://github.com/gradle/gradle/raw/v8.6.0/gradle/wrapper/gradle-wrapper.jar || \
    echo "Download failed, please install gradle 8.6 manually: https://gradle.org/install/"
  fi
fi

echo "Wrapper ready, building..."

# Make gradlew executable
chmod +x gradlew

# Build
if [ -s "gradle/wrapper/gradle-wrapper.jar" ]; then
  ./gradlew clean assembleRelease --stacktrace
else
  echo "Fallback: using system gradle"
  gradle clean assembleRelease --stacktrace
fi

echo "=== Build finished ==="
find app/build/outputs -name "*.apk" -type f | xargs ls -lh || echo "No APK found, check logs"
