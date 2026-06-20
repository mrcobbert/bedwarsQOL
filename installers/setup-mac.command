#!/bin/bash

set -euo pipefail

NODE_VERSION="v22.23.0"
REPO_TARBALL="https://github.com/mrcobbert/bedwarsQOL/archive/refs/heads/main.tar.gz"
WORKDIR="$HOME/.bedwarsqol-setup"

say()  { printf '\n\033[1;36m%s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m%s\033[0m\n' "$*"; }
die()  { printf '\n\033[1;31mSetup failed:\033[0m %s\n\nAsk in the Discord for help and copy the red text above.\n' "$*" >&2; exit 1; }

trap 'die "unexpected error on line $LINENO"' ERR

# 1. Pick the right Node build for this Mac
case "$(uname -m)" in
  arm64)  NODE_PLAT="darwin-arm64" ;;
  x86_64) NODE_PLAT="darwin-x64" ;;
  *)      die "unsupported CPU: $(uname -m)" ;;
esac

mkdir -p "$WORKDIR"
cd "$WORKDIR"

NODE_DIR="$WORKDIR/node-${NODE_VERSION}-${NODE_PLAT}"
if [[ ! -x "$NODE_DIR/bin/node" ]]; then
  say "Downloading Node.js..."
  curl -fL# -o node.tar.gz "https://nodejs.org/dist/${NODE_VERSION}/node-${NODE_VERSION}-${NODE_PLAT}.tar.gz" \
    || die "couldn't download Node.js (check your internet connection)"
  tar -xzf node.tar.gz
  rm -f node.tar.gz
fi
export PATH="$NODE_DIR/bin:$PATH"
ok "Node.js ready ($(node -v))."

# 2. Download the Worker source
say "Downloading the BedwarsQOL stats backend…"
curl -fL# -o source.tar.gz "$REPO_TARBALL" || die "couldn't download the mod source"
rm -rf src-extract && mkdir src-extract
tar -xzf source.tar.gz -C src-extract
rm -f source.tar.gz
WORKER_DIR="$(find "$WORKDIR/src-extract" -type d -path '*/server/stats-worker' | head -1)"
[[ -n "$WORKER_DIR" ]] || die "couldn't find the worker folder in the download"
cd "$WORKER_DIR"
ok "Source ready."

# 3. Install dependencies
say "Installing (this can take a minute)…"
npm install --no-fund --no-audit || die "npm install failed"
ok "Installed."

# 4. Log in to Cloudflare (opens a browser)
npx wrangler login
LOGIN_STATUS="$(npx wrangler whoami 2>&1 || true)"
if printf '%s' "$LOGIN_STATUS" | grep -qiE "not authenticated|not logged|wrangler login"; then
  say "A browser window will open — log in or sign up for Cloudflare, then click \"Allow\"."
  npx wrangler login || die "Cloudflare login was cancelled"
fi
ok "Logged in to Cloudflare."

# 5. Deploy
say "Deploying your stats backend… (if asked to pick a workers.dev name, type anything)"
DEPLOY_OUT="$(npx wrangler deploy 2>&1)" || { printf '%s\n' "$DEPLOY_OUT"; die "deploy failed"; }
printf '%s\n' "$DEPLOY_OUT"

URL="$(printf '%s\n' "$DEPLOY_OUT" | grep -Eo 'https://[a-zA-Z0-9._-]+\.workers\.dev' | head -1)"
[[ -n "$URL" ]] || die "deployed, but couldn't read the URL from the output above"

CMD="/bedwarsqol statsurl $URL"
printf '%s' "$CMD" | pbcopy 2>/dev/null || true

# Done
printf '\n\033[1;32m======================================================\n'
printf   '  DONE!  Your stats backend is live.\n'
printf   '======================================================\033[0m\n\n'
printf 'In Minecraft, paste this into chat (it is already on your clipboard):\n\n'
printf '   \033[1;33m%s\033[0m\n\n' "$CMD"
printf 'Then turn on Hypixel Stats in the mod settings (press Right Shift).\n\n'
printf 'You can close this window.\n\n'
