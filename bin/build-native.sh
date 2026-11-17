#!/usr/bin/env bash
# Build the CLI and optimizing Truffle runtime with the matching GraalVM distribution.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRAALVM_HOME="${GRAALVM_HOME:-$ROOT/.graalvm/current}"
ENGINE_CACHE="${BLITZ_NATIVE_ENGINE_CACHE:-0}"
case "$ENGINE_CACHE" in
  0) CACHE_FLAGS=(-H:-AuxiliaryEngineCache) ;;
  1) CACHE_FLAGS=(-H:+AuxiliaryEngineCache -H:ReservedAuxiliaryImageBytes=1073741824) ;;
  *) echo "BLITZ_NATIVE_ENGINE_CACHE must be 0 or 1" >&2; exit 1 ;;
esac
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
# Preserve compiler graphs and DSL field-offset updaters for the whole language,
# including generated nested classes. Only run-dependent initialization is deferred.
BUILD_INIT="dev.phpruntime"
# Startup properties / report hook; include interval; byte-string diagnostics.
RUN_INIT="dev.phpruntime.bytecode.BytecodeEmitter,dev.phpruntime.PhpLanguage,dev.phpruntime.runtime.PhpStrings"
# Host lifecycle and per-process request / regex-cache state.
RUN_INIT+=',dev.phpruntime.host,dev.phpruntime.sched,dev.phpruntime.runtime.PhpRequest,dev.phpruntime.builtins.PregFunctions'
# Entropy sources and the runtime TZDIR / zoneinfo filesystem snapshot. Builtins
# registration invokes HashFunctions, so its table must also initialize at runtime.
RUN_INIT+=',dev.phpruntime.builtins.HashFunctions,dev.phpruntime.builtins.Bcrypt,dev.phpruntime.builtins.Builtins,dev.phpruntime.timelib.ZoneinfoDb'
# Date scanner selection is a startup property, not an image-build choice.
RUN_INIT+=',dev.phpruntime.timelib.ParseDate'
# The deployed machine's locale is a run-time property. Host formatting paths
# (Truffle trace/diagnostics, PHP date/number text) must not require the builder's
# locale bundle, so include them all rather than hard-coding en_US.
# Bound build resources on the shared machine. Additional native-image options may
# be passed as arguments, e.g. bin/build-native.sh --parallelism=2 -J-Xmx4g.
exec "$GRAALVM_HOME/bin/native-image" \
  --parallelism=4 -J-Xmx6g \
  -H:+AddAllCharsets \
  -H:+UnlockExperimentalVMOptions \
  -H:+IncludeAllLocales \
  "${CACHE_FLAGS[@]}" \
  -J--enable-native-access=ALL-UNNAMED \
  -J--sun-misc-unsafe-memory-access=allow \
  "--initialize-at-build-time=$BUILD_INIT" \
  "--initialize-at-run-time=$RUN_INIT" \
  --enable-native-access=ALL-UNNAMED,org.graalvm.truffle \
  -cp "$ROOT/target/classes:$ROOT/target/lib/*" \
  "$@" dev.phpruntime.host.Main "$ROOT/target/blitzvm"
