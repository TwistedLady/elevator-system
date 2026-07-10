# Multi-stage build for elevator-console-cli. Build context is this module dir:
#   docker build -t elevator-console-cli:local elevator-console-cli/
# gcc is installed only because ring (TLS) compiles some C.
# Headless subcommands work as-is; the interactive `monitor` TUI needs `-it`.

FROM rust:1-slim AS build
RUN apt-get update && apt-get install -y --no-install-recommends gcc && rm -rf /var/lib/apt/lists/*
WORKDIR /app

COPY Cargo.toml Cargo.lock ./
RUN mkdir src && echo 'fn main() {}' > src/main.rs && \
    cargo build --release && rm -rf src

COPY . .
RUN touch src/main.rs && cargo build --release

FROM debian:stable-slim AS runtime
LABEL org.opencontainers.image.title="elevator-console-cli" \
      org.opencontainers.image.description="Terminal dashboard and order sender for the elevator system"
COPY --from=build /app/target/release/elevator-console-cli /usr/local/bin/elevator-console-cli
ENTRYPOINT ["elevator-console-cli"]
CMD ["--help"]
