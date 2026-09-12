#!/usr/bin/env bash

set -e

./mvnw -DskipTests  package 

APP_NAME=$(mvn help:evaluate -Dexpression=project.artifactId -q -DforceStdout)

APP_VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
APP_VERSION=${APP_VERSION%-*} 

MAIN_CLASS="$(mvn help:evaluate -Dexpression=main.class -q -DforceStdout)"

MAC_IDENTIFIER="${v%.*}"     
MAC_IDENTIFIER="${pkg//_/}"  
echo $MAC_IDENTIFIER

export FAT_JAR=$(
 find target -iname "*.jar" | while read -r l ; do echo "$l"; done 
)
ICON_PATH="assets/icon.icns"   # Optional: Path to macOS .icns file

# Output directory paths
BUILD_DIR="build/dist"
CUSTOM_JRE_DIR="${BUILD_DIR}/custom-jre"
INPUT_DIR="${BUILD_DIR}/input"
OUTPUT_DIR="dist"

# Validate JAVA_HOME or GraalVM path
if [ -z "$JAVA_HOME" ]; then
    echo "Error: JAVA_HOME is not set. Please set JAVA_HOME to your GraalVM Java 25 installation."
    exit 1
fi

echo "Using Java JDK from: $JAVA_HOME"
$JAVA_HOME/bin/java -version

# Clean up previous build artifacts
rm -rf "$BUILD_DIR" "$OUTPUT_DIR"
mkdir -p "$INPUT_DIR" "$OUTPUT_DIR"

# Copy fat JAR into input directory
cp "$FAT_JAR" "$INPUT_DIR/app.jar"

# ==========================================
# 2. Extract Required JDK Modules with jdeps
# ==========================================
echo "--> Analyzing application dependencies to build custom runtime..."

# Detect standard Java modules required by your app
DETECTED_MODULES=$($JAVA_HOME/bin/jdeps \
    --multi-release 25 \
    --ignore-missing-deps \
    --print-module-deps \
    "$INPUT_DIR/app.jar")

# Core modules needed by Spring and JavaFX Desktop apps (adds fallback safety)
BASE_MODULES="java.base,java.desktop,java.sql,java.naming,java.management,java.instrument,java.logging,jdk.unsupported"

MODULES="${DETECTED_MODULES},${BASE_MODULES}"
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
    --mac-package-identifier ""
    --runtime-image "$CUSTOM_JRE_DIR"
    --type dmg
    --dest "$OUTPUT_DIR"
    --java-options "-Djava.awt.headless=false"
    --java-options "-Dprism.verbose=true"
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
echo "=========================================="
