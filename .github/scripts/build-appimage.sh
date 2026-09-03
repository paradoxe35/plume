#!/usr/bin/env bash
#
# Wraps the jpackage app image as an AppImage.
#
# jpackage has no AppImage target — it produces .deb and .rpm, both of which need a package manager
# and root. An AppImage is the one Linux format a user can download and run, so it is built from
# the same app image rather than as a separate packaging path.
#
# Usage: build-appimage.sh <app-image-dir> <output-dir>

set -euo pipefail

APP_IMAGE_DIR="${1:?usage: build-appimage.sh <app-image-dir> <output-dir>}"
OUTPUT_DIR="${2:?usage: build-appimage.sh <app-image-dir> <output-dir>}"

APP_NAME="Plume"
ARCH="${ARCH:-x86_64}"
WORK_DIR="$(mktemp -d)"
APP_DIR="$WORK_DIR/$APP_NAME.AppDir"

trap 'rm -rf "$WORK_DIR"' EXIT

if [ ! -x "$APP_IMAGE_DIR/bin/$APP_NAME" ]; then
    echo "No launcher at $APP_IMAGE_DIR/bin/$APP_NAME — run :desktop:createDistributable first." >&2
    exit 1
fi

mkdir -p "$APP_DIR/usr" "$OUTPUT_DIR"
cp -a "$APP_IMAGE_DIR/." "$APP_DIR/usr/"

# AppRun resolves its own location: an AppImage is mounted at an unpredictable path, so anything
# relative to the working directory breaks the moment it is launched from elsewhere.
cat > "$APP_DIR/AppRun" <<'LAUNCHER'
#!/usr/bin/env bash
HERE="$(dirname "$(readlink -f "${0}")")"
exec "$HERE/usr/bin/Plume" "$@"
LAUNCHER
chmod +x "$APP_DIR/AppRun"

cat > "$APP_DIR/plume.desktop" <<'ENTRY'
[Desktop Entry]
Type=Application
Name=Plume
Comment=AI revision and translation, anywhere you can select text
Exec=Plume
Icon=plume
Categories=Utility;
Terminal=false
ENTRY

# jpackage leaves an icon in the app image; falling back to a generated one keeps the build honest
# rather than failing over a picture.
if [ -f "$APP_IMAGE_DIR/lib/$APP_NAME.png" ]; then
    cp "$APP_IMAGE_DIR/lib/$APP_NAME.png" "$APP_DIR/plume.png"
else
    echo "warning: no icon in the app image; generating a placeholder" >&2
    printf '\x89PNG\r\n\x1a\n' > "$APP_DIR/plume.png"
fi

TOOL="$WORK_DIR/appimagetool"
if command -v appimagetool >/dev/null 2>&1; then
    TOOL="$(command -v appimagetool)"
else
    curl -fsSL -o "$TOOL" \
        "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-$ARCH.AppImage"
    chmod +x "$TOOL"
fi

# CI runners have no FUSE, so appimagetool cannot mount itself; this unpacks and runs it instead.
export APPIMAGE_EXTRACT_AND_RUN=1
export ARCH

"$TOOL" "$APP_DIR" "$OUTPUT_DIR/$APP_NAME-$ARCH.AppImage"

echo "built $OUTPUT_DIR/$APP_NAME-$ARCH.AppImage"
