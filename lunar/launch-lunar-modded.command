#!/bin/bash
# Launch the OFFICIAL Lunar Client with the Weave agent injected via JAVA_TOOL_OPTIONS.
# The Electron launcher inherits this env var and passes it to the game JVM it spawns,
# so the Weave loader (and our mod in ~/.weave/mods/) loads — without modifying Lunar,
# without lcqt, and without relying on Lunar's per-profile JVM-args field.
#
# Usage: fully quit Lunar first, then double-click this file (or run it in a terminal).

AGENT="$HOME/.weave/Weave-Loader-Agent-1.3.3.jar"
LUNAR="/Applications/Lunar Client.app/Contents/MacOS/Lunar Client"

if [ ! -f "$AGENT" ]; then echo "Weave agent not found at: $AGENT"; exit 1; fi
if [ ! -x "$LUNAR" ]; then echo "Lunar Client not found at: $LUNAR"; exit 1; fi

export JAVA_TOOL_OPTIONS="-javaagent:$AGENT"
echo "Launching Lunar with JAVA_TOOL_OPTIONS=$JAVA_TOOL_OPTIONS"
exec "$LUNAR" "$@"
