#!/bin/bash
#
# Gravitee.io - Singularitee startup script
#
# Usage: ./gravitee.sh
#
# Environment:
#   GRAVITEE_HOME  - Installation directory (default: parent of bin/)
#   JAVA_OPTS      - Additional JVM options
#   HF_TOKEN       - HuggingFace token for gated model downloads
#

set -e

# Resolve GRAVITEE_HOME
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
GRAVITEE_HOME="${GRAVITEE_HOME:-$(dirname "$SCRIPT_DIR")}"
export GRAVITEE_HOME

# Find the bootstrap JAR
BOOTSTRAP_JAR=$(find "$GRAVITEE_HOME/lib" -maxdepth 1 -name 'gravitee-singularitee-standalone-bootstrap-*.jar' | head -1)
if [ -z "$BOOTSTRAP_JAR" ]; then
    echo "ERROR: Bootstrap JAR not found in $GRAVITEE_HOME/lib/"
    exit 1
fi

# Default JVM options
DEFAULT_JAVA_OPTS="\
  --enable-preview \
  --enable-native-access=ALL-UNNAMED \
  --sun-misc-unsafe-memory-access=allow \
  -Dgravitee.home=$GRAVITEE_HOME \
  -Dvertx.disableDnsResolver=true \
  -Dlogback.configurationFile=$GRAVITEE_HOME/config/logback.xml"

exec java $DEFAULT_JAVA_OPTS $JAVA_OPTS -jar "$BOOTSTRAP_JAR" "$@"
