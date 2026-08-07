(ns graaljs-cherry.main
  "A REPL that compiles cherry expressions on the JVM and evaluates the
  resulting JS in an embedded GraalJS context."
  (:require
   [cherry.compiler :as compiler]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.net URI)
   (org.graalvm.polyglot Context PolyglotException Source Value)
   (org.graalvm.polyglot.io FileSystem IOAccess)
   (org.graalvm.polyglot.proxy ProxyExecutable))
  (:gen-class))

(set! *warn-on-reflection* true)

;;;; Module resolution

;; Cherry-compiled code imports bare specifiers like 'cherry-cljs/cljs.core.js'.
;; GraalJS resolves import specifiers through the polyglot FileSystem, so remap
;; bare specifiers to node_modules; everything else delegates to the default fs.

(defn- node-modules-dir ^String []
  (or (System/getenv "CHERRY_NODE_MODULES")
      (str (System/getProperty "user.dir") "/node_modules")))

(defn- bare-specifier? [^String path]
  (not (or (str/starts-with? path "/")
           (str/starts-with? path "./")
           (str/starts-with? path "../"))))

(defn- exports-target
  "Resolve a package.json exports value to a package-relative file path."
  [exports subpath]
  (let [subkey (if subpath (str "./" subpath) ".")
        node (if (and (map? exports)
                      (some #(str/starts-with? % ".") (keys exports)))
               (get exports subkey)
               (when-not subpath exports))]
    (loop [node node]
      (cond
        (string? node) node
        (map? node) (recur (or (get node "import")
                               (get node "module")
                               (get node "node")
                               (get node "default")))
        :else nil))))

(defn- resolve-specifier
  "Node-ish resolution of a bare import specifier against node_modules:
  literal file, then package.json exports/module/main, then index.js.
  ESM packages only."
  ^String [^String spec]
  (let [nm (node-modules-dir)
        direct (io/file nm spec)]
    (if (.isFile direct)
      (.getPath direct)
      (let [segs (str/split spec #"/")
            npkg (if (str/starts-with? spec "@") 2 1)
            pkg-dir (io/file nm (str/join "/" (take npkg segs)))
            subpath (not-empty (str/join "/" (drop npkg segs)))
            pj-file (io/file pkg-dir "package.json")
            pj (when (.isFile pj-file)
                 (try (json/read-str (slurp pj-file))
                      (catch Exception _ nil)))
            entry (or (exports-target (get pj "exports") subpath)
                      (when subpath
                        (cond
                          (.isFile (io/file pkg-dir (str subpath ".js"))) (str subpath ".js")
                          (.isDirectory (io/file pkg-dir subpath)) (str subpath "/index.js")))
                      (when-not subpath
                        (or (get pj "module") (get pj "main"))))]
        (.getPath (io/file pkg-dir (or entry "index.js")))))))

(defn- module-fs ^FileSystem []
  (let [delegate (FileSystem/newDefaultFileSystem)]
    (reify FileSystem
      (^java.nio.file.Path parsePath [_ ^String path]
        (.parsePath delegate
                    (if (bare-specifier? path)
                      (resolve-specifier path)
                      path)))
      (^java.nio.file.Path parsePath [_ ^URI uri]
        (.parsePath delegate uri))
      (checkAccess [_ path modes link-options]
        (.checkAccess delegate path modes link-options))
      (createDirectory [_ dir attrs]
        (.createDirectory delegate dir attrs))
      (delete [_ path]
        (.delete delegate path))
      (newByteChannel [_ path options attrs]
        (.newByteChannel delegate path options attrs))
      (newDirectoryStream [_ dir filter]
        (.newDirectoryStream delegate dir filter))
      (toAbsolutePath [_ path]
        (.toAbsolutePath delegate path))
      (toRealPath [_ path link-options]
        (.toRealPath delegate path link-options))
      (readAttributes [_ path attributes options]
        (.readAttributes delegate path attributes options)))))

;;;; GraalJS context

(defn make-context ^Context []
  (-> (Context/newBuilder ^"[Ljava.lang.String;" (into-array String ["js"]))
      (.allowIO (-> (IOAccess/newBuilder)
                    (.fileSystem (module-fs))
                    (.build)))
      (.allowExperimentalOptions true)
      (.option "engine.WarnInterpreterOnly" "false")
      (.build)))

(defn- callback ^ProxyExecutable [f]
  (reify ProxyExecutable
    (execute [_ args]
      (f (aget args 0))
      nil)))

(defn eval-await
  "Eval `js-code` (which must evaluate to a promise) and wait for it to settle.
  Returns [:ok Value] or [:err Value]."
  [^Context ctx ^String js-code]
  (let [src (.buildLiteral (Source/newBuilder "js" js-code "<repl>"))
        p (.eval ctx src)
        result (promise)]
    (.invokeMember p "then"
                   (object-array [(callback #(deliver result [:ok %]))
                                  (callback #(deliver result [:err %]))]))
    ;; promise jobs run when a polyglot call returns to the host; module loading
    ;; is synchronous through the fs, so pumping is only a safety net
    (loop [i 0]
      (when (and (not (realized? result)) (< i 1000))
        (.eval ctx "js" "undefined")
        (recur (inc i))))
    (if (realized? result)
      @result
      [:err "timed out waiting for promise"])))

(def ^:private bootstrap-js
  "(async function () {
     globalThis.cherry_repl_core = await import('cherry-cljs/cljs.core.js');
     return [undefined];
   })()")

(defn bootstrap
  "Load cljs.core into the context; returns its module namespace Value."
  ^Value [^Context ctx]
  (let [[status err] (eval-await ctx bootstrap-js)]
    (when (= :err status)
      (throw (ex-info (str "could not load cherry runtime: " err) {}))))
  (.getMember (.getBindings ctx "js") "cherry_repl_core"))

(defn- error->str [^Context ctx ^Value err]
  (-> (.eval ctx "js" "(e) => (e instanceof Error && e.stack) ? e.stack : String(e)")
      (.execute (object-array [err]))
      (.asString)))

;;;; Compilation

(defn compile-form
  "Compile one repl input; returns [new-state js]. The compiled snippet is an
  async IIFE resolving to the value in a one-element box (see squint's nrepl
  server, which this mirrors)."
  [state ^String code]
  (let [{:keys [javascript] :as new-state}
        (compiler/compile-string* code
                                  {:context :repl-return
                                   :repl true
                                   :async true
                                   :elide-exports true}
                                  state)]
    [new-state (str "(async function () {\n"
                    javascript
                    "\n;return [undefined];\n})()")]))

(defn- incomplete-input? [e]
  (some-> (ex-message e) (str/includes? "EOF while reading")))

;;;; REPL

(defn repl [^Context ctx]
  (let [core (bootstrap ctx)
        pr-str* (.getMember core "pr_str")]
    (loop [state nil
           buf nil]
      (let [current-ns (:ns state "user")]
        (print (if buf "      " (str current-ns "=> ")))
        (flush)
        (when-let [line (read-line)]
          (let [code (if buf (str buf "\n" line) line)]
            (if (and (not buf) (str/blank? line))
              (recur state nil)
              (let [[new-state js] (try (compile-form state code)
                                        (catch Exception e
                                          (if (incomplete-input? e)
                                            [::incomplete nil]
                                            (do (println "compile error:" (ex-message e))
                                                nil))))]
                (cond
                  (= ::incomplete new-state) (recur state code)
                  (nil? new-state) (recur state nil)
                  :else
                  (do
                    (when (System/getenv "CHERRY_PRINT_JS")
                      (println js))
                    (let [[status ^Value v]
                          (try (eval-await ctx js)
                               (catch PolyglotException e
                                 [:err (.getMessage e)]))]
                      (if (= :ok status)
                        (println (.asString (.execute pr-str* (object-array [(.getArrayElement v 0)]))))
                        (println "error:" (if (instance? Value v)
                                            (error->str ctx v)
                                            (str v))))
                      (recur new-state nil))))))))))))

(defn -main [& _args]
  (println "Cherry GraalJS REPL, Ctrl-D to exit")
  (with-open [ctx (make-context)]
    (repl ctx))
  (System/exit 0))
