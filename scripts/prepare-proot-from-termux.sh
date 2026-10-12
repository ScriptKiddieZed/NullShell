#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/jniLibs/arm64-v8a"
PREFIX="${TERMUX_PREFIX:-/data/data/com.termux/files/usr}"

need() { command -v "$1" >/dev/null 2>&1 || { echo "[!] Missing command: $1"; exit 1; }; }
need readelf
need python
need file

PROOT="$PREFIX/bin/proot"
TALLOC="$PREFIX/lib/libtalloc.so.2"
SHMEM="$PREFIX/lib/libandroid-shmem.so"
LOADER="$PREFIX/libexec/proot/loader"
LOADER32="$PREFIX/libexec/proot/loader32"

for f in "$PROOT" "$TALLOC" "$SHMEM" "$LOADER"; do
  [ -f "$f" ] || { echo "[!] Missing: $f"; echo "    Install/update Termux packages: pkg install proot libtalloc libandroid-shmem"; exit 1; }
done

rm -f "$DEST"/libproot.so "$DEST"/libproot-loader.so "$DEST"/libproot-loader32.so \
      "$DEST"/libtalloc.so "$DEST"/libtalloc.so.2 "$DEST"/libandroid-shmem.so

# Android only packages jniLibs files named lib*.so, so libtalloc.so.2 would be
# silently dropped from the APK. Ship it as libtalloc.so instead.
cp -L "$PROOT" "$DEST/libproot.so"
cp -L "$TALLOC" "$DEST/libtalloc.so"
cp -L "$SHMEM" "$DEST/libandroid-shmem.so"
cp -L "$LOADER" "$DEST/libproot-loader.so"
if [ -f "$LOADER32" ]; then cp -L "$LOADER32" "$DEST/libproot-loader32.so"; fi

# Same-length in-place rename of "libtalloc.so.2" -> "libtalloc.so" (NUL padded)
# in PRoot's DT_NEEDED and in libtalloc's own SONAME. No patchelf needed.
python - "$DEST/libproot.so" "$DEST/libtalloc.so" <<'PY'
import sys
old=b"libtalloc.so.2\0"; new=b"libtalloc.so\0\0\0"
for path in sys.argv[1:]:
    d=open(path,"rb").read()
    n=d.count(old)
    if n==0 and path.endswith("libproot.so"):
        sys.exit("[!] libtalloc.so.2 string not found in "+path)
    open(path,"wb").write(d.replace(old,new))
    print("[+] patched %d occurrence(s) in %s"%(n,path))
PY

chmod 755 "$DEST"/libproot.so "$DEST"/libproot-loader*.so
chmod 644 "$DEST/libtalloc.so" "$DEST/libandroid-shmem.so"

echo "[+] Native runtime prepared in: $DEST"
file "$DEST"/*
echo "--- PRoot dependencies ---"
readelf -d "$DEST/libproot.so" | grep -E 'HASH|GNU_HASH|NEEDED' || true
if ! readelf -d "$DEST/libproot.so" | grep -qE 'HASH|GNU_HASH'; then
  echo "[!] PRoot has no DT_HASH/DT_GNU_HASH. Refusing to package a broken ELF."
  exit 1
fi
if ! readelf -d "$DEST/libproot.so" | grep -q 'Shared library: \[libtalloc.so\]'; then
  echo "[!] Unexpected PRoot dependency: expected libtalloc.so after patch"
  exit 1
fi
echo "[+] PRoot now depends on libtalloc.so (patched)."
echo "--- SHA256 ---"
sha256sum "$DEST"/*
