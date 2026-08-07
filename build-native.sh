#!/usr/bin/env bash
# usage: build-native.sh [--small]
# --small drops the Truffle JIT: half the size, interpreter-only JS
set -euo pipefail
cd "$(dirname "$0")"

alias=native
if [ "${1:-}" = "--small" ]; then
  alias=native-small
fi

mkdir -p classes
echo "AOT compiling..."
clojure -M:"$alias" -e "(compile 'graaljs-cherry.main)"

echo "Building native image..."
native-image \
  -cp "$(clojure -Spath -A:"$alias")" \
  --features=clj_easy.graal_build_time.InitClojureClasses \
  --no-fallback \
  -Os \
  -J-Xmx8g \
  -o graaljs-cherry \
  graaljs_cherry.main
