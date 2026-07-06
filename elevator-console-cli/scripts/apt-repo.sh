#!/usr/bin/env bash
# Build the .deb and (re)publish a signed local APT repository for it.
#
#   scripts/apt-repo.sh
#
# Output: a flat, GPG-signed apt repo under  target/apt-repo/  containing:
#   *.deb  Packages[.gz]  Release  Release.gpg  InRelease  elevator-console.gpg (public key)
#
# After running once, add it as an apt source (see the printed instructions or docs/apt-repo.md).
# Re-run any time after `cargo deb` / code changes to refresh the index. Idempotent.
set -euo pipefail

SIGN_EMAIL="apt@elevator-system.local"          # the repo signing key's uid
HERE="$(cd -- "$(dirname -- "$0")/.." && pwd)"  # elevator-console-cli/
REPO="$HERE/target/apt-repo"

echo ">> building .deb"
( cd "$HERE" && cargo deb >/dev/null )

echo ">> assembling flat repo at $REPO"
rm -rf "$REPO"
mkdir -p "$REPO"
cp "$HERE"/target/debian/*.deb "$REPO/"

cd "$REPO"
# Index every .deb in this dir (flat repo: paths are relative to the repo root).
dpkg-scanpackages --multiversion . > Packages
gzip -9 -k -f Packages

# A flat-repo Release file that lists the checksums of Packages[.gz].
apt-ftparchive \
  -o "APT::FTPArchive::Release::Origin=elevator-system" \
  -o "APT::FTPArchive::Release::Label=elevator-console" \
  -o "APT::FTPArchive::Release::Suite=stable" \
  -o "APT::FTPArchive::Release::Codename=stable" \
  -o "APT::FTPArchive::Release::Architectures=amd64" \
  -o "APT::FTPArchive::Release::Components=main" \
  release . > Release

echo ">> signing"
rm -f Release.gpg InRelease
gpg --batch --yes --local-user "$SIGN_EMAIL" --armor --detach-sign -o Release.gpg Release
gpg --batch --yes --local-user "$SIGN_EMAIL" --clearsign -o InRelease Release
# Ship the public key so apt can verify (dearmored, ready for /etc/apt/keyrings).
gpg --export "$SIGN_EMAIL" > elevator-console.gpg

echo ">> done: $REPO"
echo
echo "First-time setup (needs sudo — run these once):"
echo "  sudo install -m0644 $REPO/elevator-console.gpg /etc/apt/keyrings/elevator-console.gpg"
echo "  echo 'deb [signed-by=/etc/apt/keyrings/elevator-console.gpg] file://$REPO ./' | sudo tee /etc/apt/sources.list.d/elevator-console.list"
echo
echo "Then, and after every republish:"
echo "  sudo apt update && sudo apt install elevator-console-cli"
