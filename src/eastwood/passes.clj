(ns eastwood.passes
  (:refer-clojure :exclude [get-method])
  (:require [clojure.string :as str]
            [eastwood.copieddeps.dep1.clojure.tools.analyzer.ast :refer [update-children postwalk walk]]
            [eastwood.util :as util]
            [eastwood.copieddeps.dep1.clojure.tools.analyzer.env :as env]
            [eastwood.copieddeps.dep1.clojure.tools.analyzer.utils :as utils]
            [eastwood.copieddeps.dep2.clojure.tools.analyzer.jvm :as ana.jvm]))

(defmulti reflect-validated :op)

(defn arg-type-str [arg-types]
  (str/join ", "
            (map #(if (nil? %) "nil" (.getName ^Class %)) arg-types)))

(defn get-ctor [ast]
  (let [cls (:val (:class ast))
        arg-type-vec (mapv :tag (:args ast))
        arg-type-arr (into-array Class arg-type-vec)]
;;    (println (format "dbgx: get-ctor cls=%s arg-types=%s"
;;                     cls (arg-type-str arg-type-vec)))
    (try
      (.getConstructor ^Class cls arg-type-arr)
      (catch NoSuchMethodException e
        (try
          (.getDeclaredConstructor ^Class cls arg-type-arr)
          (catch NoSuchMethodException e
            {:class cls, :arg-types arg-type-vec}))))))

(defn get-field [ast]
  (let [cls (:class ast)
        fld-name (name (:field ast))]
    (try
      (.getField ^Class cls fld-name)
      (catch NoSuchFieldException e
        (try
          (.getDeclaredField ^Class cls fld-name)
          (catch NoSuchFieldException e
            {:class cls, :field-name fld-name}))))))

(defn get-method [ast]
  (let [cls (:class ast)
        method-name (name (:method ast))
        arg-type-vec (mapv :tag (:args ast))
        arg-type-arr (into-array Class arg-type-vec)]
;;    (println (format "dbgx: get-method cls=%s method=%s arg-types=%s"
;;                     cls method-name (arg-type-str arg-type-vec)))
    (when (some nil? arg-type-vec)
      (println (format "Error: Bad arg-type nil for method named %s for class %s, full arg type list (%s).  ast pprinted below for debugging tools.analyzer:"
                       method-name
                       (.getName ^Class cls)
                       (arg-type-str arg-type-vec)))
      (util/pprint-ast-node ast))
    (try
      (.getMethod ^Class cls method-name arg-type-arr)
      (catch NoSuchMethodException e
        (try
          (.getDeclaredMethod ^Class cls method-name arg-type-arr)
          (catch NoSuchMethodException e
            {:class cls, :method-name method-name,
             :arg-types arg-type-vec}))))))

(defmethod reflect-validated :default [ast] ast)

(defmethod reflect-validated :new [ast]
  (if (:validated? ast)
    (assoc ast :reflected-ctor (get-ctor ast))
    ast))

(defmethod reflect-validated :instance-field [ast]
  (assoc ast :reflected-field (get-field ast)))

(defmethod reflect-validated :instance-call [ast]
  (if (:validated? ast)
    (assoc ast :reflected-method (get-method ast))
    ast))

(defmethod reflect-validated :static-field [ast]
  (assoc ast :reflected-field (get-field ast)))

(defmethod reflect-validated :static-call [ast]
  (if (:validated? ast)
    (assoc ast :reflected-method (get-method ast))
    ast))

(defmulti propagate-def-name :op)

(defmethod propagate-def-name :default
  [{:keys [env] :as ast}]
  (if-let [def-name (:name env)]
    (update-children ast (fn [ast] (assoc-in ast [:env :name] def-name)))
    ast))

(defmethod propagate-def-name :def
  [{:keys [name] :as ast}]
  (update-children ast (fn [ast] (assoc-in ast [:env :name] name))))

(defn add-partly-resolved-forms
  "For every node that has a :raw-forms key, add a new
key :eastwood/partly-resolved-forms.  The value associated with the
new key is nearly the same as that associated with :raw-forms, except
that every list that starts with a symbol will have that symbol
replaced by one that is resolved, with a namespace."
  [ast]
  (let [pw (fn [{:keys [env raw-forms] :as ast}]
             (if raw-forms
               (let [resolved-forms
                     (mapv (fn [form]
                             (if (seq? form)
                               (let [[op & args] form
                                     ^clojure.lang.Var var (env/ensure (ana.jvm/global-env)
                                                                       (utils/resolve-var op env))
                                     resolved-var-sym (if (nil? var)
                                                        op
                                                        (symbol (str (.ns var)) (name (.sym var))))]
                                 (cons resolved-var-sym args))
                               form))
                           (:raw-forms ast))]
                 (assoc ast :eastwood/partly-resolved-forms resolved-forms))
               ast))]
    (postwalk ast pw)))


(def ^:private ^:dynamic *ancestors*)

(defn add-ancestors-pre [ast]
  (swap! *ancestors* #(update-in % [:ancestors] conj ast))
  ast)

(defn add-ancestors-post [ast]
  (swap! *ancestors* #(update-in % [:ancestors] pop))
  (let [{:keys [ancestors]} @*ancestors*]
    (assoc ast :eastwood/ancestors ancestors)))

(defn add-ancestors [ast]
  (binding [*ancestors* (atom {:ancestors []})]
    (walk ast add-ancestors-pre add-ancestors-post)))

(defn has-code-loc? [x]
  (if (util/has-keys? x [:file :line :column])
    x))

(defn code-loc [ast]
  (has-code-loc? (:env ast)))

(defn nil-safe-rseq [s]
  (if (nil? s)
    nil
    (rseq s)))

(defn nearest-ast-with-loc
  "Given an ast that contains something in the source code we would
like to create a warning about, return the nearest ancestor ast T that
has non-nil values for (-> T :env :line) and also for :column
and :file.  Assumes the ast has earlier been put through
add-ancestors."
  [ast]
  (let [places (concat [ast] (nil-safe-rseq (:eastwood/ancestors ast)))
        first-ast-with-loc (first (filter code-loc places))]
    first-ast-with-loc))

(defn all-asts-with-locs [ast]
  (let [places (concat [ast] (nil-safe-rseq (:eastwood/ancestors ast)))]
    (filter code-loc places)))
