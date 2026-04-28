#!/bin/bash
# ============================================================
# osm-routing-engine — build, test, and run script
# Requires: Java 21+  (check: java -version)
# ============================================================

set -e  # exit on any error

SRC_MAIN="src/main/java"
SRC_TEST="src/test/java"
OUT_MAIN="out/classes"
OUT_TEST="out/test-classes"
MAIN_CLASS="com.osmrouter.Main"
TEST_CLASS="com.osmrouter.TestRunner"
JAVA_FLAGS="--enable-preview -source 21"

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

usage() {
  echo "Usage: ./build.sh [command]"
  echo ""
  echo "  build       Compile main sources"
  echo "  test        Compile and run tests"
  echo "  run         Build and run demo (synthetic grid)"
  echo "  run <file>  Build and run with a real .osm file"
  echo "  serve       Build and start REST API on port 8080"
  echo "  clean       Remove compiled output"
  echo "  all         Clean, build, test, run"
  echo ""
  echo "Example: ./build.sh run mymap.osm"
}

check_java() {
  if ! command -v java &> /dev/null; then
    echo -e "${RED}Java not found. Install Java 21: https://adoptium.net${NC}"
    exit 1
  fi
  JAVA_VER=$(java -version 2>&1 | awk -F'"' '/version/ {print $2}' | cut -d'.' -f1)
  if [ "$JAVA_VER" -lt 21 ] 2>/dev/null; then
    echo -e "${RED}Java 21+ required. Found version $JAVA_VER.${NC}"
    exit 1
  fi
}

build() {
  echo "Building..."
  mkdir -p $OUT_MAIN
  find $SRC_MAIN -name "*.java" > /tmp/sources.txt
  javac $JAVA_FLAGS -d $OUT_MAIN @/tmp/sources.txt
  echo -e "${GREEN}Build successful.${NC}"
}

run_tests() {
  build
  echo "Running tests..."
  mkdir -p $OUT_TEST
  javac $JAVA_FLAGS -cp $OUT_MAIN -d $OUT_TEST \
    $SRC_TEST/com/osmrouter/TestRunner.java
  java --enable-preview -cp $OUT_MAIN:$OUT_TEST $TEST_CLASS
}

run_demo() {
  build
  if [ -n "$1" ]; then
    java --enable-preview -cp $OUT_MAIN $MAIN_CLASS "$1"
  else
    java --enable-preview -cp $OUT_MAIN $MAIN_CLASS
  fi
}

serve() {
  build
  java --enable-preview -cp $OUT_MAIN $MAIN_CLASS --serve
}

clean() {
  rm -rf out/
  echo "Cleaned."
}

check_java

case "$1" in
  build)  build ;;
  test)   run_tests ;;
  run)    run_demo "$2" ;;
  serve)  serve ;;
  clean)  clean ;;
  all)    clean; run_tests; run_demo ;;
  *)      usage ;;
esac
