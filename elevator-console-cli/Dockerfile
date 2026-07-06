# Multi-stage build for the elevator-console-cli.
#
# Build context is this module directory:
#   docker build -t elevator-console-cli:local elevator-console-cli/
#
# The image is meant for the headless subcommands (watch / selftest / order / simulate / itest),
# which talk to the elevator-api over HTTP with the CA bundled into the binary. The interactive
# `monitor` TUI needs a real terminal, so run it with `-it`. `kubectl`/`git`-backed views (K8s tab,
# git header) are inert here — those binaries aren't in the image and the code degrades gracefully.

FROM rust:1-slim AS build
# ring compiles a little C, so the builder needs a C toolchain.
RUN apt-get update && apt-get install -y --no-install-recommends gcc && rm -rf /var/lib/apt/lists/*
WORKDIR /app

# Cache the dependency compile: build against a stub main, then drop it and build the real sources.
COPY Cargo.toml Cargo.lock ./
RUN mkdir src && echo 'fn main() {}' > src/main.rs && \
    cargo build --release && rm -rf src

COPY . .
# `touch` so cargo notices the real main.rs is newer than the cached stub build.
RUN touch src/main.rs && cargo build --release

FROM debian:stable-slim AS runtime
LABEL org.opencontainers.image.title="elevator-console-cli" \
      org.opencontainers.image.description="Terminal dashboard and order sender for the elevator system"
COPY --from=build /app/target/release/elevator-console-cli /usr/local/bin/elevator-console-cli
ENTRYPOINT ["elevator-console-cli"]
CMD ["--help"]
