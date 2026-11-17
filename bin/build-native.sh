#!/usr/bin/env bash
# Build the CLI and optimizing Truffle runtime with the matching GraalVM distribution.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRAALVM_HOME="${GRAALVM_HOME:-$ROOT/.graalvm/current}"
if [[ ! -x "$GRAALVM_HOME/bin/native-image" ]]; then
  echo "native-image not found in $GRAALVM_HOME; set GRAALVM_HOME to the matching GraalVM." >&2
  exit 1
fi
cd "$ROOT"
mvn -q -B -DskipTests package
# Bound build resources on the shared machine. Additional native-image options may
# be passed as arguments, e.g. bin/build-native.sh --parallelism=2 -J-Xmx4g.
exec "$GRAALVM_HOME/bin/native-image" \
  --no-fallback --parallelism=4 -J-Xmx6g \
  --enable-native-access=ALL-UNNAMED \
  -cp "$ROOT/target/classes:$ROOT/target/lib/*" \
  "$@" dev.phpruntime.host.Main "$ROOT/target/blitzvm"
