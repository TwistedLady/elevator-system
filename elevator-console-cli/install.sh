#!/usr/bin/env sh
# Build the elevator-console-cli release binary and install it on your PATH.
#
#   ./install.sh                 # -> ~/.local/bin/elevator-console-cli
#   PREFIX=/usr/local ./install.sh   # -> /usr/local/bin/elevator-console-cli (may need sudo)
#
# Needs a Rust toolchain (rustup/cargo). No system libraries.
set -eu

PREFIX="${PREFIX:-$HOME/.local}"
BIN_DIR="$PREFIX/bin"
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

if ! command -v cargo >/dev/null 2>&1; then
    echo "error: cargo not found. Install Rust from https://rustup.rs" >&2
    exit 1
fi

echo "building release binary…"
cargo build --release --manifest-path "$SCRIPT_DIR/Cargo.toml"

mkdir -p "$BIN_DIR"
install -m 0755 "$SCRIPT_DIR/target/release/elevator-console-cli" "$BIN_DIR/elevator-console-cli"

echo "installed: $BIN_DIR/elevator-console-cli"
case ":$PATH:" in
    *":$BIN_DIR:"*) ;;
    *) echo "note: $BIN_DIR is not on your PATH — add it, e.g.  export PATH=\"$BIN_DIR:\$PATH\"" ;;
esac
