#!/usr/bin/env bash
#
# Build a self-contained, natively-packaged distribution of a Spring Boot app.
#
#   macOS    -> .dmg   (or .app with PACKAGE_TYPE=app-image)
#   Linux    -> .deb or .rpm, whichever the machine can build
#   Windows  -> .msi   (requires the WiX Toolset; run from Git Bash / MSYS2)
#
# jpackage cannot cross-compile: run this on the OS you want an installer for.
#
# Environment knobs:
#   JAVA_HOME          required; a JDK 21+ with jlink/jpackage (GraalVM 25 here)
#   PACKAGE_TYPE       override the auto-picked type; "app-image" needs no
#                      installer tooling at all and works everywhere
#   WIN_UPGRADE_UUID   stable GUID for MSI upgrades; derived from the bundle id
#                      if unset, but pin it once you have shipped a release
#   WIN_CONSOLE=1      build a console launcher on Windows so stdout is visible

set -euo pipefail

# ==========================================
# 0. Platform detection and path hygiene
# ==========================================
case "$(uname -s)" in
    Darwin)                      OS=mac     ;;
    Linux)                       OS=linux   ;;
    MINGW*|MSYS*|CYGWIN*)        OS=windows ;;
    *) echo "Error: unsupported platform: $(uname -s)" >&2; exit 1 ;;
esac
echo "Platform: $OS"

if [ -z "${JAVA_HOME:-}" ]; then
    echo "Error: JAVA_HOME is not set. Point it at your GraalVM Java 25 installation." >&2
    exit 1
fi

# On Windows JAVA_HOME is usually "C:\Program Files\..." — backslashes and a drive
# letter that bash cannot exec. cygpath turns it into /c/Program Files/... Every
# other path in this script stays *relative*, which sidesteps MSYS's habit of
# rewriting absolute POSIX paths when it hands arguments to native Windows exes.
if [ "$OS" = windows ] && command -v cygpath >/dev/null 2>&1; then
    JAVA_HOME=$(cygpath -u "$JAVA_HOME")
fi

# Quote every one of these: "Program Files" will word-split otherwise.
JAVA="$JAVA_HOME/bin/java"
JAR="$JAVA_HOME/bin/jar"
JDEPS="$JAVA_HOME/bin/jdeps"
JLINK="$JAVA_HOME/bin/jlink"
JPACKAGE="$JAVA_HOME/bin/jpackage"

for tool in "$JAVA" "$JAR" "$JDEPS" "$JLINK" "$JPACKAGE"; do
    # Git Bash resolves the .exe suffix on exec but not on a -x test.
    [ -x "$tool" ] || [ -x "$tool.exe" ] || {
        echo "Error: $tool not found. Is JAVA_HOME a full JDK?" >&2; exit 1; }
done

echo "Using Java JDK from: $JAVA_HOME"
"$JAVA" -version

JAVA_MAJOR=$("$JAVA" -XshowSettings:properties -version 2>&1 \
    | sed -n 's/.*java\.specification\.version *= *\([0-9][0-9]*\).*/\1/p' | head -1)
JAVA_MAJOR=${JAVA_MAJOR:-25}

# ==========================================
# 1. Build the app and ask Maven who it is
# ==========================================
# Git Bash runs the POSIX wrapper fine, but mvnw.cmd is the supported path on
# Windows and copes with Windows-shaped paths in ~/.m2 etc.
MVNW="./mvnw"
[ "$OS" = windows ] && [ -f "./mvnw.cmd" ] && MVNW="./mvnw.cmd"

"$MVNW" -DskipTests package

# Maven output can pick up a trailing CR on Windows, which silently corrupts
# every path built from it. Strip it at the source.
mvn_eval() { "$MVNW" help:evaluate -Dexpression="$1" -q -DforceStdout | tr -d '\r'; }

APP_NAME=$(mvn_eval project.artifactId)
GROUP_ID=$(mvn_eval project.groupId)
RAW_VERSION=$(mvn_eval project.version)

# Installers are fussy about version strings: Windows MSI wants a numeric
# major.minor.build (255/255/65535 ceilings) and .deb wants one starting with a
# digit. Reduce whatever Maven says to three numbers.
sanitize_version() {
    local v
    v=$(printf '%s' "$1" | sed -E 's/^[^0-9]*//; s/[^0-9.].*$//; s/\.+$//')
    local a b c
    IFS='.' read -r a b c _ <<<"$v"
    a=$((10#${a:-1})); b=$((10#${b:-0})); c=$((10#${c:-0}))
    printf '%s.%s.%s\n' "$a" "$b" "$c"
}
APP_VERSION=$(sanitize_version "$RAW_VERSION")
[ "$APP_VERSION" = "$RAW_VERSION" ] || \
    echo "Note: project version '$RAW_VERSION' normalized to '$APP_VERSION' for packaging."

if [ "$OS" = windows ]; then
    IFS='.' read -r vmaj vmin vbld <<<"$APP_VERSION"
    if [ "$vmaj" -gt 255 ] || [ "$vmin" -gt 255 ] || [ "$vbld" -gt 65535 ]; then
        echo "Error: MSI versions cap at 255.255.65535; got $APP_VERSION." >&2
        exit 1
    fi
fi

# A reverse-DNS bundle identifier. macOS uses this to tell your app apart from
# every other app on the machine, so it has to be non-empty and unique. We reuse
# it on Windows to derive a stable MSI upgrade code.
BUNDLE_ID="${GROUP_ID}.${APP_NAME//_/}"
echo "Bundle identifier: $BUNDLE_ID"

# The Spring Boot fat jar, *not* the ${artifactId}.jar.original the repackage goal
# leaves behind. Match on the version so we only ever get one hit.
FAT_JAR="target/${APP_NAME}-${RAW_VERSION}.jar"
if [ ! -f "$FAT_JAR" ]; then
    echo "Error: expected fat jar not found at $FAT_JAR" >&2
    exit 1
fi
echo "Using fat jar: $FAT_JAR"

# A Spring Boot fat jar is not an ordinary executable jar: our classes live under
# BOOT-INF/classes and our dependencies under BOOT-INF/lib, neither of which a plain
# `java -cp` understands. The manifest's Main-Class is the Boot launcher, which sets up
# a classloader that can read those nested entries and *then* calls our Start-Class.
# Hand jpackage the launcher; hand the launcher our application.
MAIN_CLASS="org.springframework.boot.loader.launch.JarLauncher"

BUILD_DIR="build/dist"
CUSTOM_JRE_DIR="${BUILD_DIR}/custom-jre"
INPUT_DIR="${BUILD_DIR}/input"
EXPLODED_DIR="${BUILD_DIR}/exploded"
OUTPUT_DIR="dist"

rm -rf "$BUILD_DIR" "$OUTPUT_DIR"
mkdir -p "$INPUT_DIR" "$EXPLODED_DIR" "$OUTPUT_DIR"
cp "$FAT_JAR" "$INPUT_DIR/app.jar"

# ==========================================
# 2. Extract Required JDK Modules with jdeps
# ==========================================
echo "--> Analyzing application dependencies to build custom runtime..."

# jdeps cannot see through the Boot fat jar's nested layout either: pointed at app.jar
# it only ever reports what the *launcher* needs, which is almost nothing. Unzip first
# so it can analyze BOOT-INF/classes and every jar in BOOT-INF/lib for real.
#
# `jar xf` rather than `unzip`: Git for Windows ships no unzip, but a JDK is the one
# thing this script already guarantees. `jar` extracts into the cwd, so walk up out of
# EXPLODED_DIR by however deep it happens to be rather than hardcoding "../../../".
REL_PREFIX=$(printf '%s' "$EXPLODED_DIR" | awk -F/ '{for (i=1;i<=NF;i++) printf "../"}')
( cd "$EXPLODED_DIR" && "$JAR" xf "${REL_PREFIX}${INPUT_DIR}/app.jar" )

DETECTED_MODULES=$("$JDEPS" \
    --multi-release "$JAVA_MAJOR" \
    --ignore-missing-deps \
    --print-module-deps \
    --class-path "${EXPLODED_DIR}/BOOT-INF/lib/*" \
    --recursive \
    "${EXPLODED_DIR}/BOOT-INF/classes" \
    "${EXPLODED_DIR}"/BOOT-INF/lib/*.jar 2>/dev/null)

# Drop anything jlink cannot resolve from the JDK itself (GraalVM's org.graalvm.* modules
# show up here because of the native-image hints on the classpath).
DETECTED_MODULES=$(echo "$DETECTED_MODULES" | tr ',' '\n' | tr -d '\r' \
    | grep -E '^(java|jdk)\.' | paste -sd, -)

# jdeps only sees *static* references. Anything reached reflectively or via
# ServiceLoader is invisible to it and has to be named by hand:
#   jdk.crypto.ec / jdk.crypto.cryptoki - TLS cipher suites; without these every
#                                         outbound https:// call dies in the handshake
#   java.security.jgss                   - embedded Tomcat's default realm touches
#                                         org.ietf.jgss at startup
#   java.xml.crypto, java.naming, java.management, java.instrument - Spring internals
#   jdk.localedata, jdk.charsets, jdk.zipfs - date/number formatting, non-UTF-8 I/O
#   java.desktop, java.datatransfer, java.prefs - required by JavaFX
BASE_MODULES="java.base,java.datatransfer,java.desktop,java.instrument,java.logging"
BASE_MODULES="${BASE_MODULES},java.management,java.management.rmi,java.naming,java.net.http"
BASE_MODULES="${BASE_MODULES},java.prefs,java.rmi,java.scripting,java.security.jgss"
BASE_MODULES="${BASE_MODULES},java.security.sasl,java.sql,java.transaction.xa,java.xml"
BASE_MODULES="${BASE_MODULES},java.xml.crypto,jdk.charsets,jdk.crypto.cryptoki"
BASE_MODULES="${BASE_MODULES},jdk.crypto.ec,jdk.localedata,jdk.unsupported,jdk.zipfs"

# jdk.crypto.mscapi backs Windows' native keystore; harmless to ask for elsewhere,
# but jlink errors on an unknown module name, so add it only where it exists.
[ "$OS" = windows ] && BASE_MODULES="${BASE_MODULES},jdk.crypto.mscapi"

MODULES=$(echo "${DETECTED_MODULES},${BASE_MODULES}" | tr ',' '\n' | sed '/^$/d' | sort -u | paste -sd, -)
echo "Modules to include: $MODULES"

# ==========================================
# 3. Create Minified Custom JRE with jlink
# ==========================================
echo "--> Building lightweight stripped-down JRE..."

"$JLINK" \
    --add-modules "$MODULES" \
    --strip-debug \
    --no-header-files \
    --no-man-pages \
    --compress zip-6 \
    --output "$CUSTOM_JRE_DIR"

# ==========================================
# 4. Package the native distribution
# ==========================================
# Pick the installer format this machine can actually produce.
if [ -n "${PACKAGE_TYPE:-}" ]; then
    TYPE="$PACKAGE_TYPE"
else
    case "$OS" in
        mac)     TYPE=dmg ;;
        windows) TYPE=msi ;;
        linux)
            if   command -v dpkg-deb >/dev/null 2>&1; then TYPE=deb
            elif command -v rpmbuild >/dev/null 2>&1; then TYPE=rpm
            else
                echo "Note: neither dpkg-deb nor rpmbuild found; producing a plain app-image."
                echo "      Install one of them (or set PACKAGE_TYPE) for a real installer."
                TYPE=app-image
            fi
            ;;
    esac
fi

# jpackage shells out to WiX for msi/exe and fails late and cryptically without it.
if [ "$OS" = windows ] && { [ "$TYPE" = msi ] || [ "$TYPE" = exe ]; }; then
    if ! command -v candle >/dev/null 2>&1 && ! command -v wix >/dev/null 2>&1; then
        echo "Error: building a .$TYPE needs the WiX Toolset on your PATH." >&2
        echo "       Install WiX 3.14 (https://wixtoolset.org/), or re-run with" >&2
        echo "       PACKAGE_TYPE=app-image for a runnable folder with no installer." >&2
        exit 1
    fi
fi

echo "--> Packaging native $OS distribution ($TYPE)..."

JPACKAGE_ARGS=(
    --name "$APP_NAME"
    --app-version "$APP_VERSION"
    --input "$INPUT_DIR"
    --main-jar "app.jar"
    --main-class "$MAIN_CLASS"
    --runtime-image "$CUSTOM_JRE_DIR"
    --type "$TYPE"
    --dest "$OUTPUT_DIR"
    --java-options "-Djava.awt.headless=false"
)

# Optional, and a no-op until you actually drop an icon in assets/. jpackage wants a
# different format per platform: .icns on macOS, .ico on Windows, .png on Linux.
case "$OS" in
    mac)     ICON_PATH="assets/icon.icns" ;;
    windows) ICON_PATH="assets/icon.ico"  ;;
    linux)   ICON_PATH="assets/icon.png"  ;;
esac
[ -f "$ICON_PATH" ] && JPACKAGE_ARGS+=(--icon "$ICON_PATH")

# Platform-specific options. jpackage hard-errors on an option belonging to another
# OS ("Option [--mac-package-identifier] is not valid on this platform"), and the
# installer-only ones are rejected for --type app-image, so gate both.
case "$OS" in
    mac)
        JPACKAGE_ARGS+=(--mac-package-identifier "$BUNDLE_ID")
        ;;
    windows)
        if [ "$TYPE" != app-image ]; then
            # Without a *stable* upgrade UUID each release installs alongside the last
            # instead of replacing it. Derived from the bundle id so it stays put, but
            # pin it in WIN_UPGRADE_UUID once you have shipped.
            if [ -z "${WIN_UPGRADE_UUID:-}" ]; then
                if command -v md5sum >/dev/null 2>&1; then
                    h=$(printf '%s' "$BUNDLE_ID" | md5sum | cut -d' ' -f1)
                elif command -v md5 >/dev/null 2>&1; then
                    h=$(printf '%s' "$BUNDLE_ID" | md5 -q)
                else
                    echo "Error: cannot derive an upgrade GUID; set WIN_UPGRADE_UUID." >&2
                    exit 1
                fi
                WIN_UPGRADE_UUID="${h:0:8}-${h:8:4}-${h:12:4}-${h:16:4}-${h:20:12}"
            fi
            echo "Windows upgrade UUID: $WIN_UPGRADE_UUID"
            JPACKAGE_ARGS+=(
                --win-upgrade-uuid "$WIN_UPGRADE_UUID"
                --win-menu
                --win-shortcut
                --win-dir-chooser
            )
        fi
        # A jpackage launcher is a GUI binary: no console, so stdout/stderr vanish.
        # WIN_CONSOLE=1 builds a console launcher instead, which is the only sane way
        # to see a startup failure — at the cost of a console window for every user.
        [ "${WIN_CONSOLE:-}" = "1" ] && JPACKAGE_ARGS+=(--win-console)
        ;;
    linux)
        if [ "$TYPE" != app-image ]; then
            # deb/rpm package names must be lowercase and cannot contain underscores.
            LINUX_PKG=$(printf '%s' "$APP_NAME" | tr '[:upper:]_' '[:lower:]-' \
                        | sed -E 's/[^a-z0-9.+-]//g; s/^[^a-z0-9]+//')
            JPACKAGE_ARGS+=(
                --linux-package-name "$LINUX_PKG"
                --linux-shortcut
            )
        fi
        ;;
esac

"$JPACKAGE" "${JPACKAGE_ARGS[@]}"

echo "=========================================="
echo "Build Successful!"
echo "Artifacts in ${OUTPUT_DIR}/:"
ls -1 "$OUTPUT_DIR"
echo
case "$OS" in
    mac)
        echo "A double-clicked .app throws stdout and stderr away. If it dies on launch,"
        echo "run the launcher binary from a terminal to see why:"
        echo "  /Applications/${APP_NAME}.app/Contents/MacOS/${APP_NAME}"
        ;;
    windows)
        echo "The installed launcher is a GUI binary with no console, so a crash on"
        echo "startup is silent. Re-run this script with WIN_CONSOLE=1 to build a"
        echo "console launcher, or log to a file:"
        echo "  \"C:\\Program Files\\${APP_NAME}\\${APP_NAME}.exe\""
        ;;
    linux)
        echo "If it dies on launch, run the installed launcher from a terminal:"
        echo "  /opt/${APP_NAME}/bin/${APP_NAME}"
        ;;
esac
echo "=========================================="
