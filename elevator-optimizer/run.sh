#!/usr/bin/env bash
# Compile and run the optimization lab with the system Scala.
# Usage: ./run.sh [elevators] [floors] [requests] [budget]
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out
scalac -d out src/*.scala
scala -cp out pl.feelcodes.elevator.optimizer.Lab "$@"
