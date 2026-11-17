#!/usr/bin/env bash
# Build first: mvn -q -B -DskipTests package
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JAVA_HOME_USED="${PHP_TRUFFLE_JAVA_HOME:-$ROOT/.graalvm/current}"
exec "$JAVA_HOME_USED/bin/java" --enable-native-access=ALL-UNNAMED \
  --sun-misc-unsafe-memory-access=allow -XX:-DontCompileHugeMethods \
  -Dpolyglot.engine.WarnInterpreterOnly=false ${PHP_TRUFFLE_OPTS:-} \
  -cp "$ROOT/target/classes:$ROOT/target/lib/*" dev.phpruntime.bc.BytecodeMain "$@"
