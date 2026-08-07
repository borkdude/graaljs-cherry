#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

mkdir -p classes
echo "AOT compiling..."
clojure -M:native -e "(compile 'graaljs-cherry.main)"

echo "Building native image..."
native-image \
  -cp "$(clojure -Spath -A:native)" \
  --features=clj_easy.graal_build_time.InitClojureClasses \
  --no-fallback \
  -J-Xmx8g \
  -o graaljs-cherry \
  graaljs_cherry.main
