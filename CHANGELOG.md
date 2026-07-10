# Changelog

## [0.2.1](https://github.com/TwistedLady/elevator-system/compare/v0.2.0...v0.2.1) (2026-07-10)


### Bug Fixes

* **infra:** reachable api, wired PG creds, durable TLS + Kafka, replica-aware cluster ([#72](https://github.com/TwistedLady/elevator-system/issues/72)) ([308c39a](https://github.com/TwistedLady/elevator-system/commit/308c39a0726faa0c92d9ca0716d55e69b1504983))

## [0.2.0](https://github.com/TwistedLady/elevator-system/compare/v0.1.1...v0.2.0) (2026-07-10)


### Features

* require passenger JWT on POST /api/call and extract the load simulator into elevator-sim ([#68](https://github.com/TwistedLady/elevator-system/issues/68)) ([692a977](https://github.com/TwistedLady/elevator-system/commit/692a9775653e684a25b79f3c220625f12ee4a8cf))
* unify the two consoles (one header, 3 tabs) and move the 10k sim into the api ([#65](https://github.com/TwistedLady/elevator-system/issues/65)) ([177a6d6](https://github.com/TwistedLady/elevator-system/commit/177a6d66a5cd50996399b878a1c7c452e3e3e85c))


### Bug Fixes

* **chart:** seed job passes only --count to the simulate CLI ([#69](https://github.com/TwistedLady/elevator-system/issues/69)) ([5026010](https://github.com/TwistedLady/elevator-system/commit/50260101b0d29e883bd162f67430cd36f29c1850))

## [0.1.1](https://github.com/TwistedLady/elevator-system/compare/v0.1.0...v0.1.1) (2026-07-10)


### Bug Fixes

* add ~/.local/bin to PATH so the self-hosted deploy finds kubectl/kind ([#63](https://github.com/TwistedLady/elevator-system/issues/63)) ([9c24595](https://github.com/TwistedLady/elevator-system/commit/9c2459517f8be42c8b2a77c337096e62f00b60b2))

## [0.1.0](https://github.com/TwistedLady/elevator-system/compare/v0.0.1...v0.1.0) (2026-07-09)


### Features

* automatic versioning via release-please + Conventional Commits ([#60](https://github.com/TwistedLady/elevator-system/issues/60)) ([90a3ec0](https://github.com/TwistedLady/elevator-system/commit/90a3ec0bd674debc7fd39b4bfec96cb7b7064dec))


### Bug Fixes

* decouple Helm chart version from release-please ([#61](https://github.com/TwistedLady/elevator-system/issues/61)) ([a284319](https://github.com/TwistedLady/elevator-system/commit/a284319c79880c9aa3876e8d23c572f3a6192745))
