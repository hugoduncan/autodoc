(ns autodoc.build-html
  (:refer-clojure :exclude [empty complement]) 
  (:import [java.util.jar JarFile]
           [java.io File FileWriter BufferedWriter StringReader])
  (:require [clojure.contrib.str-utils :as str])
  (:use [net.cgrand.enlive-html :exclude (deftemplate)]
        [clojure.contrib.pprint :only (pprint cl-format)]
        [clojure.contrib.pprint.examples.json :only (print-json)]
        [clojure.contrib.pprint.utilities :only (prlabel)]
        [clojure.contrib.duck-streams :only (with-out-writer)]
        [clojure.contrib.java-utils :only (file)]
        [clojure.contrib.shell-out :only (sh)]
        [autodoc.collect-info :only (contrib-info)]
        [autodoc.params :only (params)]))

(def *layout-file* "layout.html")
(def *master-toc-file* "master-toc.html")
(def *local-toc-file* "local-toc.html")

(def *overview-file* "overview.html")
(def *description-file* "description.html")
(def *namespace-api-file* "namespace-api.html")
(def *sub-namespace-api-file* "sub-namespace-api.html")
(def *index-html-file* "api-index.html")
(def *index-json-file* "api-index.json")

(defn template-for
  "Get the actual filename corresponding to a template. We check in the project
specific directory first, then sees if a parameter with that name is set, then 
looks in the base template directory."
  [base] 
  (let [custom-template (File. (str (params :param-dir) "/templates/" base))]
    (if (.exists custom-template)
      custom-template
      (if-let [param (params (keyword (.replaceFirst base "\\.html$" "")))]
        (StringReader. param)
        (-> (clojure.lang.RT/baseLoader) (.getResourceAsStream (str "templates/" base)))))))

(def memo-nodes
     (memoize
      (fn [source]
        (if-let [source (template-for source)]
          (map annotate (select (html-resource source) [:body :> any-node]))))))

(defmacro deffragment
  [name source args & forms]
  `(def ~name
        (fn ~args
          (if-let [nodes# (memo-nodes ~source)]
            (flatmap (transformation ~@forms) nodes#)))))  

(def memo-html-resource
     (memoize
      (fn [source]
        (if-let [source (template-for source)]
          (html-resource source)))))

(defmacro deftemplate
  "A template returns a seq of string:
   Overridden from enlive to defer evaluation of the source until runtime.
   Builds in \"template-for\""
  [name source args & forms] 
  `(def ~name
        (comp emit* 
              (fn ~args
                (if-let [nodes# (memo-html-resource ~source)]
                  (flatmap (transformation ~@forms) nodes#))))))

;;; Thanks to Chouser for this regex TODO: except it doesn't work!
(defn expand-links 
  "Return a seq of nodes with links expanded into anchor tags."
  [s]
  (when s
    (for [x (str/re-partition #"(\w+://.*?)([.>]*(?: |$))" s)]
      (if (vector? x)
        [{:tag :a :attrs {:href (x 1)} :content [(x 1)]} (x 2)]
        x))))

(deftemplate page *layout-file*
  [title prefix master-toc local-toc page-content]
  [:html :head :title] (content title)
  [:link] #(apply (set-attr :href (str prefix (:href (:attrs %)))) [%])
  [:img] #(apply (set-attr :src (str prefix (:src (:attrs %)))) [%])
  [:a#page-header] (content (or (params :page-title) (params :name)))
  [:div#leftcolumn] (content master-toc)
  [:div#right-sidebar] (content local-toc)
  [:div#content-tag] (content page-content)
  [:div#copyright] (content (params :copyright)))

(defn branch-subdir [branch-name] 
  (when branch-name (str "branch-" branch-name)))

(defn create-page [output-file branch title prefix master-toc local-toc page-content]
  (let [dir (if branch 
              (file (params :output-path) (branch-subdir branch))
              (file (params :output-path)))] 
    (when (not (.exists dir))
      (.mkdirs dir))
    (with-out-writer (file dir output-file) 
      (print
       (apply str (page title prefix master-toc local-toc page-content))))))

(defmulti ns-html-file class)

(defmethod ns-html-file clojure.lang.IPersistentMap [ns-info]
  (str (:short-name ns-info) "-api.html"))

(defmethod ns-html-file String [ns-name]
  (str ns-name "-api.html"))

(defn overview-toc-data 
  [ns-info]
  (for [ns ns-info] [(:short-name ns) (:short-name ns)]))

(defn var-tag-name [ns v] (str (:full-name ns) "/" (:name v)))

(defn var-toc-entries 
  "Build the var-name, <a> tag pairs for the vars in ns"
  [ns]
  (for [v (:members ns)] [(:name v) (var-tag-name ns v)]))

(defn ns-toc-data [ns]
  (apply 
   vector 
   ["Overview" "toc0" (var-toc-entries ns)]
   (for [sub-ns (:subspaces ns)]
     [(:short-name sub-ns) (:short-name sub-ns) (var-toc-entries sub-ns)])))

(defn var-url
  "Return the relative URL of the anchored entry for a var on a namespace detail page"
  [ns v] (str (ns-html-file (:base-ns ns)) "#" (var-tag-name ns v)))

(defn add-ns-vars [ns]
  (clone-for [v (:members ns)]
             #(at % 
                [:a] (do->
                      (set-attr :href (var-url ns v))
                      (content (:name v))))))

(defn process-see-also
  "Take the variations on the see-also metadata and turn them into a canonical [link text] form"
  [see-also-seq]
  (map 
   #(cond
      (string? %) [% %] 
      (< (count %) 2) (repeat 2 %)
      :else %) 
   see-also-seq))

(defn see-also-links [ns]
  (if-let [see-also (seq (:see-also ns))]
    #(at %
       [:span#see-also-link] 
       (clone-for [[link text] (process-see-also (:see-also ns))]
         (fn [t] 
           (at t
             [:a] (do->
                   (set-attr :href link)
                   (content text))))))))

(defn external-doc-links [ns external-docs]
  (if-let [ns-docs (get external-docs (:short-name ns))]
    #(at %
       [:span#external-doc-link] 
       (clone-for [[link text] ns-docs]
         (fn [t] 
           (at t
             [:a] (do->
                   (set-attr :href (str "doc/" link))
                   (content text))))))))

(defn namespace-overview [ns template]
  (at template
    [:#namespace-tag] 
    (do->
     (set-attr :id (:short-name ns))
     (content (:short-name ns)))
    [:#author] (content (or (:author ns) "unknown author"))
    [:a#api-link] (set-attr :href (ns-html-file ns))
    [:pre#namespace-docstr] (content (expand-links (:doc ns)))
    [:span#var-link] (add-ns-vars ns)
    [:span#subspace] (if-let [subspaces (seq (:subspaces ns))]
                       (clone-for [s subspaces]
                         #(at % 
                            [:span#name] (content (:short-name s))
                            [:span#sub-var-link] (add-ns-vars s))))
    [:span#see-also] (see-also-links ns)
    [:.ns-added] (when (:added ns)
                   #(at % [:#content]
                        (content (str "Added in " (params :name)
                                      " version " (:added ns)))))
    [:.ns-deprecated] (when (:deprecated ns)
                        #(at % [:#content]
                             (content (str "Deprecated since " (params :name)
                                           " version " (:deprecated ns)))))))

(deffragment make-project-description *description-file* [])

(deffragment make-overview-content *overview-file* [branch ns-info]
  [:span#header-project] (content (or (params :name) "Project"))
  [:#branch-name] (when branch (content (str "(" branch " branch)")))
  [:div#project-description] (content (or 
                                       (make-project-description)
                                       (params :description)))

  [:div#namespace-entry] (clone-for [ns ns-info] #(namespace-overview ns %)))

(deffragment make-master-toc *master-toc-file* [ns-info branch-names prefix]
  [:ul#left-sidebar-list :li] (clone-for [ns ns-info]
                                #(at %
                                   [:a] (do->
                                         (set-attr :href (ns-html-file ns))
                                         (content (:short-name ns)))))
  [:div.BranchTOC] (when branch-names
                     #(at %
                          [:ul#left-sidebar-branch-list :li]
                          (clone-for [branch branch-names]
                            (let [subdir (if (= branch (first branch-names))
                                           nil
                                           (str (branch-subdir branch) "/"))]
                             (fn [n] 
                               (at n 
                                   [:a] (do->
                                         (set-attr :href (str prefix subdir "index.html"))
                                         (content branch)))))))))

(deffragment make-local-toc *local-toc-file* [toc-data]
  [:.toc-section] (clone-for [[text tag entries] toc-data]
                    #(at %
                       [:a] (do->
                             (set-attr :href (str "#" tag))
                             (content text))
                       [:.toc-entry] (clone-for [[subtext subtag] entries]
                                       (fn [node]
                                         (at node
                                           [:a] (do->
                                                 (set-attr :href (str "#" subtag))
                                                 (content subtext))))))))

(defn make-overview [ns-info master-toc branch first-branch? prefix]
  (create-page "index.html"
               (when (not first-branch?) branch)
               (str (params :name) " - Overview")
               prefix
               master-toc
               (make-local-toc (overview-toc-data ns-info))
               (make-overview-content branch ns-info)))

;;; TODO: redo this so the usage parts can be styled
(defn var-usage [v]
  (if-let [arglists (:arglists v)]
    (cl-format nil
               "~<Usage: ~:i~@{~{(~a~{ ~a~})~}~^~:@_~}~:>~%"
               (map #(vector %1 %2) (repeat (:name v)) arglists))
    (if (= (:var-type v) "multimethod")
      "No usage documentation available")))

(def 
  #^{:doc "Gets the commit hash for the last commit that included this file. We
do this for source links so that we don't change them with every commit (unless that file
actually changed). This reduces the amount of random doc file changes that happen."}
  get-last-commit-hash
  (memoize
   (fn [file branch]
     (let [hash (.trim (sh "git" "rev-list" "--max-count=1" "HEAD" file 
                       :dir (params :root)))]
       (when (not (.startsWith hash "fatal"))
         hash)))))

(defn web-src-file [file branch]
  (when-let [web-src-dir (params :web-src-dir)]
    (when-let [hash (get-last-commit-hash file branch)]
      (cl-format nil "~a~a/~a" web-src-dir hash file))))

(def src-prefix-length
  (memoize
   (fn []
     (.length (.getPath (File. (params :root)))))))

(def memoized-working-directory
     (memoize 
      (fn [] (.getAbsolutePath (file ".")))))

(defn var-base-file
  "strip off the prepended path to the source directory from the filename"
  [f]
  (cond
   (.startsWith f (params :root)) (.substring f (inc (src-prefix-length)))
   (.startsWith f (memoized-working-directory)) (.substring f (inc (.length (memoized-working-directory))))
   true (.getPath (file (params :source-path) f))))

(defn var-src-link [v branch]
  (when (and (:file v) (:line v))
    (when-let [web-file (web-src-file (var-base-file (:file v)) branch)]
      (cl-format nil "~a#L~d" web-file (:line v)))))

;;; TODO: factor out var from namespace and sub-namespace into a separate template.
(defn var-details [ns v template branch]
  (at template 
    [:#var-tag] 
    (do->
     (set-attr :id (var-tag-name ns v))
     (content (:name v)))
    [:span#var-type] (content (:var-type v))
    [:pre#var-usage] (content (var-usage v))
    [:pre#var-docstr] (content (expand-links (:doc v)))
    [:a#var-source] (fn [n] (when-let [link (var-src-link v branch)]
                              (apply (set-attr :href link) [n])))
    [:.var-added] (when (:added v)
                   #(at % [:#content]
                        (content (str "Added in " (params :name)
                                      " version " (:added v)))))
    [:.var-deprecated] (when (:deprecated v)
                        #(at % [:#content]
                             (content (str "Deprecated since " (params :name)
                                           " version " (:deprecated v)))))))

(declare common-namespace-api)

(deffragment render-sub-namespace-api *sub-namespace-api-file*
 [ns branch external-docs]
  (common-namespace-api ns branch external-docs))

(deffragment render-namespace-api *namespace-api-file*
 [ns branch external-docs]
  (common-namespace-api ns branch external-docs))

(defn make-ns-content [ns branch external-docs]
  (render-namespace-api ns branch external-docs))

(defn common-namespace-api [ns branch external-docs]
  (fn [node]
    (at node
        [:#namespace-name] (content (:short-name ns))
        [:#branch-name] (when branch (content (str "(" branch " branch)")))
        [:span#author] (content (or (:author ns) "Unknown"))
        [:span#long-name] (content (:full-name ns))
        [:pre#namespace-docstr] (content (expand-links (:doc ns)))
        [:span#see-also] (see-also-links ns)
        [:.ns-added] (when (:added ns)
                       #(at % [:#content]
                            (content (str "Added in " (params :name) " version " (:added ns)))))
        [:.ns-deprecated] (when (:deprecated ns)
                            #(at % [:#content]
                                 (content (str "Deprecated since " (params :name)
                                               " version " (:deprecated ns)))))
        [:span#external-doc] (external-doc-links ns external-docs)
        [:div#var-entry] (clone-for [v (:members ns)] #(var-details ns v % branch))
        [:div#sub-namespaces]
        (substitute (map #(render-sub-namespace-api % branch external-docs) (:subspaces ns))))))

(defn make-ns-page [ns master-toc external-docs branch first-branch? prefix]
  (create-page (ns-html-file ns)
               (when (not first-branch?) branch)
               (str (:short-name ns) " API reference (" (params :name) ")")
               prefix
               master-toc
               (make-local-toc (ns-toc-data ns))
               (make-ns-content ns branch external-docs)))

(defn vars-by-letter 
  "Produce a lazy seq of two-vectors containing the letters A-Z and Other with all the 
vars in ns-info that begin with that letter"
  [ns-info]
  (let [chars (conj (into [] (map #(str (char (+ 65 %))) (range 26))) "Other")
        var-map (apply merge-with conj 
                       (into {} (for [c chars] [c [] ]))
                       (for [v (mapcat #(for [v (:members %)] [v %]) ns-info)]
                         {(or (re-find #"[A-Z]" (-> v first :name .toUpperCase))
                              "Other")
                          v}))]
    (for [c chars] [c (sort-by #(-> % first :name .toUpperCase) (get var-map c))])))

(defn doc-prefix [v n]
  "Get a prefix of the doc string suitable for use in an index"
  (let [doc (:doc v)
        len (min (count doc) n)
        suffix (if (< len (count doc)) "..." ".")]
    (str (.replaceAll 
          (.replaceFirst (.substring doc 0 len) "^[ \n]*" "")
          "\n *" " ")
         suffix)))

(defn gen-index-line [v ns]
  (let [var-name (:name v)
        overhead (count var-name)
        short-name (:short-name ns)
        doc-len (+ 50 (min 0 (- 18 (count short-name))))]
    #(at %
         [:a] (do->
               (set-attr :href
                         (str (ns-html-file ns) "#" (:full-name ns) "/" (:name v)))
               (content (:name v)))
         [:#line-content] (content 
                           (cl-format nil "~vt~a~vt~a~vt~a~%"
                                      (- 29 overhead)
                                      (:var-type v) (- 43 overhead)
                                      short-name (- 62 overhead)
                                      (doc-prefix v doc-len))))))

;; TODO: skip entries for letters with no members
(deffragment make-index-content *index-html-file* [branch vars-by-letter]
  [:span.project-name-span] (content (params :name))
  [:#branch-name] (when branch (content (str "(" branch " branch)")))
  [:div#index-body] (clone-for [[letter vars] vars-by-letter]
                      #(at %
                         [:h2] (set-attr :id letter)
                         [:span#section-head] (content letter)
                         [:span#section-content] (clone-for [[v ns] vars]
                                                   (gen-index-line v ns)))))

(defn make-index-html [ns-info master-toc branch first-branch? prefix]
  (create-page *index-html-file*
               (when (not first-branch?) branch)
               (str (params :name) " - Index")
               prefix
               master-toc
               nil
               (make-index-content branch (vars-by-letter ns-info))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;
;;; Make the JSON index
;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ns-file 
  "Get the file name (relative to src/ in clojure.contrib) where a namespace lives" 
  [ns]
  (let [ns-name (.replaceAll (:full-name ns) "-" "_")
        ns-file (.replaceAll ns-name "\\." "/")]
    (str ns-file ".clj")))

(defn namespace-index-info [ns branch]
  (assoc (select-keys ns [:doc :author])
    :name (:full-name ns)
    :wiki-url (str (params :web-home) (ns-html-file ns))
    :source-url (web-src-file (.getPath (file (params :source-path) (ns-file ns))) branch)))

(defn var-index-info [v ns branch]
  (assoc (select-keys v [:name :doc :author :arglists])
    :namespace (:full-name ns)
    :wiki-url (str (params :web-home) "/" (var-url ns v))
    :source-url (var-src-link v branch)))

(defn structured-index 
  "Create a structured index of all the reference information about contrib"
  [ns-info branch]
  (let [namespaces (concat ns-info (mapcat :subspaces ns-info))
        all-vars (mapcat #(for [v (:members %)] [v %]) namespaces)]
     {:namespaces (map #(namespace-index-info % branch) namespaces)
      :vars (map (fn [[v ns]] (apply var-index-info v ns branch [])) all-vars)}))


(defn make-index-json
  "Generate a json formatted index file that can be consumed by other tools"
  [ns-info branch]
  (when (params :build-json-index)
    (with-out-writer (BufferedWriter.
                      (FileWriter. (file (params :output-path) *index-json-file*)))
      (print-json (structured-index ns-info branch)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;
;;; Wrap the external doc
;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn add-href-prefix [node prefix]
  (at node
    [:a] #(apply (set-attr :href (str prefix (:href (:attrs %)))) [%])))
(defmacro select-content-text [node selectr]
  `(first (:content (first (select [~node] ~selectr)))))

(defn get-title [node]
  (or (select-content-text node [:title])
      (select-content-text node [:h1])))

(defn external-doc-map [v]
  (apply 
   merge-with concat
   (map 
    (partial apply assoc {}) 
    (for [[offset title :as elem] v]
      (let [[_ dir nm] (re-find #"(.*/)?([^/]*)\.html" offset)
            package (if dir (apply str (interpose "." (into [] (.split dir "/")))))]
        (if dir
          [package [elem]]
          [nm [elem]]))))))

(defn wrap-external-doc [staging-dir target-dir master-toc]
  (when staging-dir
    (external-doc-map
     (doall          ; force the side effect (generating the xml files
      (for [file (filter #(.isFile %) (file-seq (java.io.File. staging-dir)))]
        (let [source-path (.getAbsolutePath file)
              offset (.substring source-path (inc (.length staging-dir)))
              target-path (str target-dir "/" offset)
              page-content (first (html-resource (java.io.File. source-path)))
              title (get-title page-content)
              prefix (apply str (repeat (count (.split offset "/")) "../"))]
          (create-page target-path nil title prefix (add-href-prefix master-toc prefix) nil page-content)
          [offset title]))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;
;;; Put it all together
;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn make-all-pages 
  ([] (make-all-pages [[nil true nil (contrib-info)]]))
  ([branch-name first? all-branches ns-info]
     (prlabel make-all-pages branch-name first? all-branches)
     (let [prefix (if first? nil "../")
           master-toc (make-master-toc ns-info all-branches prefix)
           external-docs (wrap-external-doc (params :external-doc-tmpdir) "doc" master-toc)]
       (make-overview ns-info master-toc branch-name first? prefix)
       (doseq [ns ns-info]
         (make-ns-page ns master-toc external-docs branch-name first? prefix))
       (make-index-html ns-info master-toc branch-name first? prefix)
       (make-index-json ns-info branch-name))))
