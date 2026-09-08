#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
TEST_DIR="$PROJECT_DIR/test-env"
MINDUSTRY_VERSION="v158.1"
MINDUSTRY_JAR="$TEST_DIR/Mindustry.jar"

if [ -f "$MINDUSTRY_JAR" ]; then
    echo "Mindustry $MINDUSTRY_VERSION already present at $MINDUSTRY_JAR"
    exit 0
fi

URL="https://github.com/Anuken/Mindustry/releases/download/$MINDUSTRY_VERSION/Mindustry.jar"
echo "Downloading Mindustry $MINDUSTRY_VERSION..."
mkdir -p "$TEST_DIR"
curl -L "$URL" -o "$MINDUSTRY_JAR"
echo "Downloaded to $MINDUSTRY_JAR"
