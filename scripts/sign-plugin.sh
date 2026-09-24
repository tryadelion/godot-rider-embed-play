#!/bin/sh
# Builds, signs and verifies the plugin zip for JetBrains Marketplace.
#
# Expects the key and certificate made once with docs/PUBLISHING.md, in
#   $SIGNING_DIR (default: ~/.sleepyfant-signing)
#     private.pem             private key (converted from private_encrypted.pem)
#     chain.crt               certificate
# Asks for the key password unless PRIVATE_KEY_PASSWORD is already set.
# Output: build/distributions/godot-embed-play-<version>-signed.zip
set -e
cd "$(dirname "$0")/.."

DIR="${SIGNING_DIR:-$HOME/.sleepyfant-signing}"
for f in private.pem chain.crt; do
    if [ ! -f "$DIR/$f" ]; then
        echo "Missing $DIR/$f. Create the key and certificate first (docs/PUBLISHING.md, 'Signing key')." >&2
        exit 1
    fi
done
export PRIVATE_KEY_FILE="$DIR/private.pem"
export CERTIFICATE_CHAIN_FILE="$DIR/chain.crt"

if [ -z "$PRIVATE_KEY_PASSWORD" ]; then
    printf 'Private key password: '
    stty -echo
    read -r PRIVATE_KEY_PASSWORD
    stty echo
    echo
    export PRIVATE_KEY_PASSWORD
fi

./gradlew signPlugin verifyPluginSignature
echo
ls -l build/distributions/*-signed.zip
