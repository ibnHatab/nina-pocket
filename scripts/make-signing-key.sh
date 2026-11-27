#!/usr/bin/env bash
# Nina Pocket's release signing key: made once, on the owner's machine, by the owner.
#   scripts/make-signing-key.sh            → ~/.config/nina/nina-pocket-release.{jks,pass} (0600)
#                                             and the repo's CI secrets KEYSTORE_B64, KEYSTORE_PASS, KEY_ALIAS
# The password is random and never printed; the secrets are set from files (nothing on a command line).
# Keep a backup of both files somewhere safe: an app signed with a lost key cannot be updated, only
# uninstalled and installed again (its data lost).
set -euo pipefail
repo=${REPO:-ibnHatab/nina-pocket}
dir="$HOME/.config/nina"; ks="$dir/nina-pocket-release.jks"; pf="$dir/nina-pocket-release.pass"; alias=nina-pocket
mkdir -p "$dir"; chmod 700 "$dir"
if [ -e "$ks" ]; then echo "$ks exists: not made again (delete it first to replace the key)"; else
  umask 077
  openssl rand -base64 30 | tr -d '\n/+=' | head -c 32 > "$pf"
  keytool -genkeypair -keystore "$ks" -storetype PKCS12 -alias "$alias" -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass:file "$pf" -dname "CN=Nina Pocket" > /dev/null
  chmod 600 "$ks" "$pf"
  echo "made $ks"
fi
base64 -w0 "$ks" | gh secret set KEYSTORE_B64 --repo "$repo"
gh secret set KEYSTORE_PASS --repo "$repo" < "$pf"
printf '%s' "$alias" | gh secret set KEY_ALIAS --repo "$repo"
gh secret list --repo "$repo"
cat <<MSG

Local builds with the same key (e.g. in ~/.profile or before ./build.sh):
  export KEYSTORE=$ks
  export KEYSTORE_PASS="\$(cat $pf)"
  export KEY_ALIAS=$alias
Back up $ks and $pf.
MSG
