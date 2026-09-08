#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
TEST_DIR="$PROJECT_DIR/test-env"
MINDUSTRY_JAR="$TEST_DIR/Mindustry.jar"
MOD_JAR="$PROJECT_DIR/build/libs/AutoDrillNEXTDesktop.jar"
MODS_DIR="$HOME/.local/share/Mindustry/mods"

if [ ! -f "$MINDUSTRY_JAR" ]; then
    echo "Mindustry not found at $MINDUSTRY_JAR"
    echo "Run ./scripts/download.sh first"
    exit 1
fi

echo "Building mod..."
"$PROJECT_DIR/gradlew" -p "$PROJECT_DIR" jar

echo "Deploying mod to $MODS_DIR..."
mkdir -p "$MODS_DIR"
cp "$MOD_JAR" "$MODS_DIR/"

echo "Starting Mindustry..."
cd "$TEST_DIR"
java -jar "$MINDUSTRY_JAR"
