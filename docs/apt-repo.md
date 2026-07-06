# apt-repo.md — install the console with `apt install`

The Rust console ships as a Debian package served from a **signed, local apt repository**, so it
installs by name like any system package.

## Publish (repo maintainer)

```bash
cd elevator-console-cli
scripts/apt-repo.sh          # builds the .deb, then signs + indexes target/apt-repo/
```

The script is idempotent — re-run it after every code change to refresh the index. It signs with a
local GPG key (uid `apt@elevator-system.local`) and exports the public key as
`target/apt-repo/elevator-console.gpg`.

## Wire it into apt (once, needs sudo)

```bash
REPO=elevator-console-cli/target/apt-repo   # absolute path
sudo install -m0644 "$REPO/elevator-console.gpg" /etc/apt/keyrings/elevator-console.gpg
echo "deb [signed-by=/etc/apt/keyrings/elevator-console.gpg] file://$REPO ./" \
  | sudo tee /etc/apt/sources.list.d/elevator-console.list
```

`signed-by` scopes the key to this one repo — no `[trusted=yes]`, no global trust.

## Install / upgrade / remove

```bash
sudo apt update
sudo apt install elevator-console-cli     # by name
elevator-console-cli monitor              # run it

sudo apt remove elevator-console-cli      # clean uninstall
```

To publish an **upgrade**, bump `version` in `elevator-console-cli/Cargo.toml`, re-run
`scripts/apt-repo.sh`, then `sudo apt update && sudo apt install --only-upgrade elevator-console-cli`.

## Notes

- **Flat repo, `amd64` only.** One architecture, one component. Cross-arch needs extra `cargo deb`
  targets.
- **Local `file://`.** To share it, serve `target/apt-repo/` over HTTP (or GitHub Pages) and swap the
  `file://` in the source line for the URL — nothing else changes.
