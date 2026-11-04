#!/usr/bin/env bash
# Downloads GraalVM Community 25.5 (JDK 25, pairs with Truffle 25.5) into ./.graalvm and
# points ./.graalvm/current at it. bin/php-truffle uses it automatically, which enables
# the Graal JIT for PHP code.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG=graal-25.5.5.0.3
BUILD=jdk-25i5-25.0.5.0.3
case "$(uname -m)" in
  x86_64) ARCH=x64 ;;
  aarch64|arm64) ARCH=aarch64 ;;
  *) echo "unsupported arch $(uname -m)" >&2; exit 1 ;;
esac
case "$(uname -s)" in
  Linux) OS=linux ;;
  Darwin) OS=macos ;;
  *) echo "unsupported OS" >&2; exit 1 ;;
esac
NAME="graalvm-community-${BUILD}_${OS}-${ARCH}_bin.tar.gz"
URL="https://github.com/graalvm/graalvm-ce-builds/releases/download/${TAG}/${NAME}"
mkdir -p "$ROOT/.graalvm/$TAG"
cd "$ROOT/.graalvm/$TAG"
if [[ -z "$(ls -A)" ]]; then
  curl -fSL -o graalvm.tar.gz "$URL"
  curl -fsSL -o graalvm.tar.gz.sha256 "$URL.sha256"
  echo "$(cut -d' ' -f1 graalvm.tar.gz.sha256)  graalvm.tar.gz" | sha256sum -c -
  tar xzf graalvm.tar.gz && rm graalvm.tar.gz graalvm.tar.gz.sha256
fi
HOME_DIR="$(ls -d "$ROOT/.graalvm/$TAG"/*/ | head -1)"
HOME_DIR="${HOME_DIR%/}"
[[ -d "$HOME_DIR/Contents/Home" ]] && HOME_DIR="$HOME_DIR/Contents/Home"
ln -sfn "$HOME_DIR" "$ROOT/.graalvm/current"
"$ROOT/.graalvm/current/bin/java" -version
