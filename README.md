# graaljs-cherry

A native-image REPL that compiles [cherry](https://github.com/squint-cljs/cherry)
expressions on the JVM and evaluates the resulting JS in an embedded GraalJS context.

> [!WARNING]
> This prototype was largely written by an LLM. Review before relying on it.

## Prerequisites

- GraalVM 25.0.2 with `native-image` on the PATH
- `npm install` (fetches the `cherry-cljs` runtime into `node_modules`)

## Run on the JVM

```bash
clojure -M -m graaljs-cherry.main
```

## Build and run the native image

```bash
./build-native.sh
./graaljs-cherry
```

```
user=> (defn foo [x] (inc x))
#object[foo]
user=> (foo 41)
42
user=> (require '[clojure.string :as s])
nil
user=> (s/join ", " (map inc [1 2 3]))
"2, 3, 4"
```

## How it works

Each REPL input is compiled with `cherry.compiler/compile-string*` in
`:repl` mode, wrapped in an async IIFE and evaluated in a GraalJS
`Context`. Vars live on `globalThis.<ns>` so they persist across evals.
Compiled code imports bare specifiers like `cherry-cljs/cljs.core.js`.
A custom polyglot `FileSystem` resolves those against `node_modules`,
also for the imports that a REPL `require` emits. Set
`CHERRY_NODE_MODULES` to use a different `node_modules` directory.
