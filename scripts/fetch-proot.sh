#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
echo "[i] This standalone Android prototype uses the Android-compatible PRoot shipped by Termux."
echo "[i] Run: ./scripts/prepare-proot-from-termux.sh"
exec "$SCRIPT_DIR/prepare-proot-from-termux.sh"
