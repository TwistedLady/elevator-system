# elevator-console-cli — build, test, package, install.
# `make help` lists targets. Everything here is a thin wrapper over cargo.

BIN     := elevator-console-cli
PREFIX  ?= $(HOME)/.local
IMAGE   ?= elevator-console-cli:local

.DEFAULT_GOAL := help

.PHONY: help build release test lint fmt fmt-check check install uninstall deb docker dist clean

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | \
	  awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}'

build: ## Debug build
	cargo build

release: ## Optimised, stripped release binary (target/release/$(BIN))
	cargo build --release

test: ## Run the unit tests
	cargo test

lint: ## Clippy with warnings denied
	cargo clippy --all-targets -- -D warnings

fmt: ## Auto-format
	cargo fmt --all

fmt-check: ## Verify formatting (CI)
	cargo fmt --all --check

check: fmt-check lint test ## Everything CI runs: fmt + clippy + tests

install: release ## Install to $(PREFIX)/bin (override with PREFIX=)
	install -d "$(PREFIX)/bin"
	install -m 0755 target/release/$(BIN) "$(PREFIX)/bin/$(BIN)"
	@echo "installed $(PREFIX)/bin/$(BIN)"

uninstall: ## Remove the installed binary
	rm -f "$(PREFIX)/bin/$(BIN)"

deb: ## Build a Debian package (needs: cargo install cargo-deb)
	cargo deb

docker: ## Build the container image ($(IMAGE))
	docker build -t $(IMAGE) .

dist: release ## Stripped binary + sha256 in dist/
	mkdir -p dist
	cp target/release/$(BIN) dist/$(BIN)
	cd dist && sha256sum $(BIN) > $(BIN).sha256
	@echo "dist/$(BIN) ready"

clean: ## Remove build artifacts
	cargo clean
	rm -rf dist
