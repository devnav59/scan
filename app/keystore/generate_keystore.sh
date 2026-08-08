#!/bin/bash
# Generate debug/public keystore for CI and local builds
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KEYSTORE_FILE="$SCRIPT_DIR/tradescanner.jks"

if [ -f "$KEYSTORE_FILE" ]; then
  echo "Keystore already exists at $KEYSTORE_FILE"
  exit 0
fi

if ! command -v keytool &> /dev/null; then
  echo "keytool not found, trying to find java..."
  exit 1
fi

keytool -genkeypair -v \
  -keystore "$KEYSTORE_FILE" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias tradescanner \
  -storepass tradescanner \
  -keypass tradescanner \
  -dname "CN=TradeScanner, OU=Dev, O=TradeScanner, L=Tehran, ST=Tehran, C=IR"

echo "Keystore generated at $KEYSTORE_FILE"

# Ensure properties file exists
cat > "$SCRIPT_DIR/keystore.properties" <<EOF
storeFile=keystore/tradescanner.jks
storePassword=tradescanner
keyAlias=tradescanner
keyPassword=tradescanner
EOF

echo "keystore.properties created"
