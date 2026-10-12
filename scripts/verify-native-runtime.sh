#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/jniLibs/arm64-v8a"
missing=0
for f in libproot.so libproot-loader.so libtalloc.so libandroid-shmem.so; do
  if [ ! -f "$DEST/$f" ]; then
    echo "[!] Missing: $DEST/$f"
    missing=1
  fi
done
if [ "$missing" -ne 0 ]; then
  echo "[!] Run: bash scripts/prepare-proot-from-termux.sh"
  exit 1
fi
file "$DEST"/*
echo "[+] Required native runtime files are present."

# Verify the PRoot ELF has a usable hash table and report its DT_NEEDED entries.
readelf -d "$DEST/libproot.so" | grep -E "HASH|GNU_HASH|NEEDED" || true
