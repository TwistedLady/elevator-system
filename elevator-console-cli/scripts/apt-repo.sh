#!/usr/bin/env bash
# Build the .deb and (re)publish a signed, flat local APT repo under target/apt-repo/.
# Idempotent; re-run after `cargo deb` or code changes. See the printed apt-source instructions.
set -euo pipefail

SIGN_EMAIL="apt@elevator-system.local"
HERE="$(cd -- "$(dirname -- "$0")/.." && pwd)"
REPO="$HERE/target/apt-repo"

echo ">> building .deb"
( cd "$HERE" && cargo deb >/dev/null )

echo ">> assembling flat repo at $REPO"
rm -rf "$REPO"
mkdir -p "$REPO"
cp "$HERE"/target/debian/*.deb "$REPO/"

cd "$REPO"
dpkg-scanpackages --multiversion . > Packages
gzip -9 -k -f Packages

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
gpg --export "$SIGN_EMAIL" > elevator-console.gpg

echo ">> done: $REPO"
echo
echo "First-time setup (needs sudo — run these once):"
echo "  sudo install -m0644 $REPO/elevator-console.gpg /etc/apt/keyrings/elevator-console.gpg"
echo "  echo 'deb [signed-by=/etc/apt/keyrings/elevator-console.gpg] file://$REPO ./' | sudo tee /etc/apt/sources.list.d/elevator-console.list"
echo
echo "Then, and after every republish:"
echo "  sudo apt update && sudo apt install elevator-console-cli"
