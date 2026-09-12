#!/usr/bin/env bash

set -euo pipefail

./mvnw -DskipTests package

APP_NAME=$(./mvnw help:evaluate -Dexpression=project.artifactId -q -DforceStdout)
GROUP_ID=$(./mvnw help:evaluate -Dexpression=project.groupId -q -DforceStdout)

APP_VERSION=$(./mvnw help:evaluate -Dexpression=project.version -q -DforceStdout)
APP_VERSION=${APP_VERSION%-SNAPSHOT}

# A reverse-DNS bundle identifier. macOS uses this to tell your app apart from every
# other app on the machine, so it has to be non-empty and it has to be unique.
MAC_IDENTIFIER="${GROUP_ID}.${APP_NAME//_/}"
echo "Bundle identifier: $MAC_IDENTIFIER"

# The Spring Boot fat jar, *not* the ${artifactId}.jar.original the repackage goal
# leaves behind. Match on the version so we only ever get one hit.
FAT_JAR="target/${APP_NAME}-$(./mvnw help:evaluate -Dexpression=project.version -q -DforceStdout).jar"
if [ ! -f "$FAT_JAR" ]; then
    echo "Error: expected fat jar not found at $FAT_JAR"
    exit 1
fi
echo "Using fat jar: $FAT_JAR"

# A Spring Boot fat jar is not an ordinary executable jar: our classes live under
# BOOT-INF/classes and our dependencies under BOOT-INF/lib, neither of which a plain
# `java -cp` understands. The manifest's Main-Class is the Boot launcher, which sets up
# a classloader that can read those nested entries and *then* calls our Start-Class.
# Hand jpackage the launcher; hand the launcher our application.
MAIN_CLASS="org.springframework.boot.loader.launch.JarLauncher"

ICON_PATH="assets/icon.icns"   # Optional: Path to macOS .icns file

# Output directory paths
BUILD_DIR="build/dist"
CUSTOM_JRE_DIR="${BUILD_DIR}/custom-jre"
INPUT_DIR="${BUILD_DIR}/input"
EXPLODED_DIR="${BUILD_DIR}/exploded"
OUTPUT_DIR="dist"

# Validate JAVA_HOME or GraalVM path
if [ -z "${JAVA_HOME:-}" ]; then
    echo "Error: JAVA_HOME is not set. Please set JAVA_HOME to your GraalVM Java 25 installation."
    exit 1
fi

echo "Using Java JDK from: $JAVA_HOME"
$JAVA_HOME/bin/java -version

# Clean up previous build artifacts
rm -rf "$BUILD_DIR" "$OUTPUT_DIR"
mkdir -p "$INPUT_DIR" "$EXPLODED_DIR" "$OUTPUT_DIR"

# Copy fat JAR into input directory
cp "$FAT_JAR" "$INPUT_DIR/app.jar"

# ==========================================
# 2. Extract Required JDK Modules with jdeps
# ==========================================
echo "--> Analyzing application dependencies to build custom runtime..."

# jdeps cannot see through the Boot fat jar's nested layout either: pointed at app.jar
# it only ever reports what the *launcher* needs, which is almost nothing. Unzip first
# so it can analyze BOOT-INF/classes and every jar in BOOT-INF/lib for real.
(cd "$EXPLODED_DIR" && unzip -qo "../../../$INPUT_DIR/app.jar")

DETECTED_MODULES=$($JAVA_HOME/bin/jdeps \
    --multi-release 25 \
    --ignore-missing-deps \
    --print-module-deps \
    --class-path "${EXPLODED_DIR}/BOOT-INF/lib/*" \
    --recursive \
    "${EXPLODED_DIR}/BOOT-INF/classes" \
    "${EXPLODED_DIR}"/BOOT-INF/lib/*.jar 2>/dev/null)

# Drop anything jlink cannot resolve from the JDK itself (GraalVM's org.graalvm.* modules
# show up here because of the native-image hints on the classpath).
DETECTED_MODULES=$(echo "$DETECTED_MODULES" | tr ',' '\n' | grep -E '^(java|jdk)\.' | paste -sd, -)

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

MODULES=$(echo "${DETECTED_MODULES},${BASE_MODULES}" | tr ',' '\n' | sort -u | paste -sd, -)
echo "Modules to include: $MODULES"

# ==========================================
# 3. Create Minified Custom JRE with jlink
# ==========================================
echo "--> Building lightweight stripped-down JRE..."

$JAVA_HOME/bin/jlink \
    --add-modules "$MODULES" \
    --strip-debug \
    --no-header-files \
    --no-man-pages \
    --compress zip-6 \
    --output "$CUSTOM_JRE_DIR"

# ==========================================
# 4. Package Application Bundle with jpackage
# ==========================================
echo "--> Packaging native macOS distribution (.dmg)..."

JPACKAGE_ARGS=(
    --name "$APP_NAME"
    --app-version "$APP_VERSION"
    --input "$INPUT_DIR"
    --main-jar "app.jar"
    --main-class "$MAIN_CLASS"
    --mac-package-identifier "$MAC_IDENTIFIER"
    --runtime-image "$CUSTOM_JRE_DIR"
    --type dmg
    --dest "$OUTPUT_DIR"
    --java-options "-Djava.awt.headless=false"
)

# Attach macOS icon if it exists
if [ -f "$ICON_PATH" ]; then
    JPACKAGE_ARGS+=(--icon "$ICON_PATH")
fi

# Run jpackage
$JAVA_HOME/bin/jpackage "${JPACKAGE_ARGS[@]}"

echo "=========================================="
echo "Build Successful!"
echo "Installer generated at: ${OUTPUT_DIR}/${APP_NAME}-${APP_VERSION}.dmg"
echo
echo "A double-clicked .app throws stdout and stderr away. If it dies on launch,"
echo "run the launcher binary from a terminal to see why:"
echo "  /Applications/${APP_NAME}.app/Contents/MacOS/${APP_NAME}"
echo "=========================================="
