#!/usr/bin/env bash
# Build the CLI and optimizing Truffle runtime with the matching GraalVM distribution.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRAALVM_HOME="${GRAALVM_HOME:-$ROOT/.graalvm/current}"
if [[ ! -x "$GRAALVM_HOME/bin/native-image" ]]; then
  echo "native-image not found in $GRAALVM_HOME; set GRAALVM_HOME to the matching GraalVM." >&2
  exit 1
fi
REQUIRED_VERSION="$(sed -n 's|.*<graalvm.version>\(.*\)</graalvm.version>.*|\1|p' "$ROOT/pom.xml")"
if [[ "$("$GRAALVM_HOME/bin/native-image" --version)" != *"$REQUIRED_VERSION"* ]]; then
  echo "GraalVM must match pom.xml's Truffle version ($REQUIRED_VERSION)." >&2
  exit 1
fi
cd "$ROOT"
mvn -q -B -DskipTests package
# Truffle keeps stateless providers and interop exports in the image heap. Class
# initialization directives do not implicitly include a class's nested classes.
BUILD_INIT="dev.phpruntime.PhpLanguageProvider,dev.phpruntime.js.JsLanguageProvider,dev.phpruntime.bc.BytecodeLanguageProvider"
# DSL cache updaters must be created before SVM deletes their hosted-only field
# names and recomputes Unsafe offsets for the native object layout.
BUILD_INIT+=',dev.phpruntime.bytecode.PhpBytecodeRootNodeFactory$CallCalleeNodeGen,dev.phpruntime.js.nodes.JsCallsFactory$DispatchNodeGen'
for PREFIX in dev/phpruntime/parser/PhpParseErrorGen dev/phpruntime/js/parser/JsParseErrorGen dev/phpruntime/runtime/PhpNullGen; do
  for FILE in "$ROOT/target/classes/$PREFIX"*.class; do
    CLASS="${FILE#"$ROOT/target/classes/"}"
    CLASS="${CLASS%.class}"
    BUILD_INIT+=",${CLASS//\//.}"
  done
done
# Bound build resources on the shared machine. Additional native-image options may
# be passed as arguments, e.g. bin/build-native.sh --parallelism=2 -J-Xmx4g.
exec "$GRAALVM_HOME/bin/native-image" \
  --parallelism=4 -J-Xmx6g \
  -H:+AddAllCharsets \
  -J--enable-native-access=ALL-UNNAMED \
  -J--sun-misc-unsafe-memory-access=allow \
  "--initialize-at-build-time=$BUILD_INIT" \
  --enable-native-access=ALL-UNNAMED,org.graalvm.truffle \
  -cp "$ROOT/target/classes:$ROOT/target/lib/*" \
  "$@" dev.phpruntime.host.Main "$ROOT/target/blitzvm"
