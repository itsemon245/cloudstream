#!/usr/bin/env bash
# One-off: creates the fork's APK signing key and stores it as GitHub Actions secrets.
# Back up the two files it creates. Without them, future APKs can't install over this one.
set -euo pipefail

REPO="${REPO:-itsemon245/cloudstream}"
KEY_DIR="${KEY_DIR:-$HOME/.android}"
KEYSTORE="$KEY_DIR/cloudstream-fork-release.jks"
PASSWORD_FILE="$KEY_DIR/cloudstream-fork-release.password"
KEYTOOL="${KEYTOOL:-keytool}"

if [ -e "$KEYSTORE" ] || [ -e "$PASSWORD_FILE" ]; then
    echo "Refusing to overwrite the existing key in $KEY_DIR." >&2
    exit 1
fi

mkdir -p "$KEY_DIR"
umask 077
openssl rand -hex 24 > "$PASSWORD_FILE"
password="$(cat "$PASSWORD_FILE")"

"$KEYTOOL" -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -alias cloudstream \
    -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Cloudstream Fork" \
    -storepass "$password" -keypass "$password"

base64 -w0 "$KEYSTORE" | gh secret set RELEASE_KEYSTORE_BASE64 -R "$REPO"
gh secret set RELEASE_KEYSTORE_PASSWORD -R "$REPO" --body "$password"

echo "Key created and secrets set for $REPO."
echo "Back up both files somewhere safe:"
echo "  $KEYSTORE"
echo "  $PASSWORD_FILE"
