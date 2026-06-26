#!/bin/bash
# BedwarsQOL — one-click installer for Lunar Client (macOS).
# Double-click this file. It copies the Weave loader + the mod into place and prints the
# exact line to paste into Lunar's JVM Arguments.

DIR="$(cd "$(dirname "$0")" && pwd)"
AGENT="Weave-Loader-Agent-1.3.3.jar"
MOD="BedwarsQOL-Lunar-0.3.0.jar"

echo ""
echo "  Installing BedwarsQOL for Lunar Client..."

if [ ! -f "$DIR/$AGENT" ] || [ ! -f "$DIR/$MOD" ]; then
  echo "  ❌ Couldn't find the bundled jars next to this installer."
  echo "     Keep this file in the same folder as $AGENT and $MOD."
  echo ""
  read -p "  Press Return to close."
  exit 1
fi

mkdir -p "$HOME/.weave/mods"
cp -f "$DIR/$AGENT" "$HOME/.weave/$AGENT"
cp -f "$DIR/$MOD"   "$HOME/.weave/mods/$MOD"

echo "  ✅ Done."
echo ""
echo "  ────────────────────────────────────────────────────────────"
echo "  ONE-TIME setup in Lunar (only needed the first time):"
echo ""
echo "   1. Open Lunar Client → Settings (gear icon)."
echo "   2. Turn ON 'Advanced Mode' (toggle next to the settings search box)."
echo "   3. In the 'JVM Arguments' box, paste EXACTLY this line:"
echo ""
echo "      -javaagent:$HOME/.weave/$AGENT"
echo ""
echo "   4. Save, choose version 1.8.9, and click Play."
echo ""
echo "  After it loads, set your stats backend once, in chat:"
echo "      /bedwarsqol statsurl <your-backend-url>"
echo "  Press Right Shift in-game to open the settings menu."
echo "  ────────────────────────────────────────────────────────────"
echo ""
echo "  (The -javaagent line above is filled in for THIS Mac — paste it as-is.)"
echo ""
read -p "  Press Return to close."
