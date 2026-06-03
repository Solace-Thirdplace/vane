#!/usr/bin/env bash
# Build all vane plugins and copy the normal server-plugin jars into ./build/.
# "Normal" = the Paper server plugins a user drops into plugins/, i.e. everything
# except the Velocity proxy plugin and plexmap (matching sign_and_zip.sh).
set -euo pipefail

cd "$(dirname "$0")"

die() {
    echo -e "\033[1;31merror:\033[m $*" >&2
    exit 1
}

echo "[+] Running gradlew build"
./gradlew build || die "Build failed"

dest="build"
mkdir -p "$dest"

echo "[+] Copying plugin jars into $dest/"
shopt -s nullglob
copied=0
for jar in target/vane*.jar; do
    case "$jar" in
        *velocity*|*plexmap*) continue ;;
    esac
    cp -v "$jar" "$dest/"
    copied=$((copied + 1))
done
[ "$copied" -gt 0 ] || die "No plugin jars found in target/ — did the build produce them?"

echo "[+] Done — copied $copied jar(s) into $dest/"
