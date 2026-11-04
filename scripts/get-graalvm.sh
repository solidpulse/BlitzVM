#!/usr/bin/env bash
# Downloads GraalVM CE for JDK 23 (the release that pairs with Truffle 24.1) into ./.graalvm.
# bin/php-truffle uses it automatically, which enables the Graal JIT for PHP code.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION=23.0.2
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
NAME="graalvm-community-jdk-${VERSION}_${OS}-${ARCH}_bin.tar.gz"
URL="https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-${VERSION}/${NAME}"
mkdir -p "$ROOT/.graalvm"
cd "$ROOT/.graalvm"
if ls -d graalvm-community-openjdk-${VERSION}* >/dev/null 2>&1; then
  echo "GraalVM already present in $ROOT/.graalvm"; exit 0
fi
curl -fSL -o graalvm.tar.gz "$URL"
curl -fsSL -o graalvm.tar.gz.sha256 "$URL.sha256"
echo "$(cat graalvm.tar.gz.sha256)  graalvm.tar.gz" | sha256sum -c -
tar xzf graalvm.tar.gz && rm graalvm.tar.gz graalvm.tar.gz.sha256
ls -d "$ROOT"/.graalvm/graalvm-community-openjdk-*
