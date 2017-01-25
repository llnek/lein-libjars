;; Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns leiningen.wabbit

  (:require [leiningen.core.classpath :as cp]
            [leiningen.core.utils :as cu]
            [leiningen.core.project :as pj]
            [leiningen.core.main :as cm]
            [leiningen.jar :as jar]
            [leiningen.pom :as pom]
            [leiningen.javac :as lj]
            [leiningen.test :as lt]
            [clojure.pprint :as pp]
            [clojure.java.io :as io]
            [clojure.string :as cs]
            [clojure.set :as set]
            [robert.hooke :as h])

  (:use [czlab.wabbit.shared.core])

  (:import [czlab.wabbit.shared HelloWorld]
           [java.io File]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(def ^:private PKGDIR "pkg")


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- packLib
  ""
  [project toDir]
  (let
    [scoped (set (pj/pom-scope-profiles project :provided))
     dft (set (pj/expand-profile project :default))
     provided (remove
                (set/difference dft scoped)
                (-> project meta :included-profiles))
     project (pj/merge-profiles
               (pj/merge-profiles project
                                  [:uberjar]) provided)
     ;;_ (pom/check-for-snapshot-deps project)
     project (update-in project
                        [:jar-inclusions]
                        concat
                        (:uberjar-inclusions project))
     [_ jar] (first (jar/jar project nil))]
    (let
      [whites (select-keys project pj/whitelist-keys)
       project (-> (pj/unmerge-profiles project [:default])
                   (merge whites))
       deps (->> (cp/resolve-managed-dependencies
                   :dependencies
                   :managed-dependencies project)
                 (filter #(.endsWith (.getName %) ".jar")))
       jars (cons (io/file jar) deps)
       lib (io/file toDir "lib")]
      (.mkdirs lib)
      (doseq [fj jars
              :let [n (.getName fj)
                    t (io/file lib n)]]
        ;;(println "dep-jar = " t)
        (io/copy fj t)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- copyBin
  ""
  [root]
  (let [c2 (.getContextClassLoader (Thread/currentThread))
        c1 (.getClassLoader HelloWorld)
        bin (io/file root "bin")
        pfx "czlab/wabbit/shared/bin/"
        arr ["log4j2.xml"
             "wabbit"
             "wabbit.bat"
             "h2db-server"]]
    (.mkdirs bin)
    (doseq [r arr
            :let [res (str pfx r)
                  u (.getResource c2 res)]
            :when (some? u)]
      (with-open [inp (.openStream u)]
        (let [des (io/file bin r)]
          (io/copy inp des)
          (.setExecutable des true))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- copyDir
  ""
  [src des]

  (let [p (.getCanonicalPath src)
        z (inc (.length p))]
    (doseq [f (file-seq src)
            :let [cp (.getCanonicalPath f)
                  z' (.length cp)]
            :when (> z' z)]
      (let [part (.substring cp z)
            t (io/file des part)]
        (if (.isDirectory f)
          (.mkdirs t)
          (do
            (.mkdirs (.getParentFile t))
            (io/copy f t)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- packFiles
  ""
  [project toDir]

  (let
    [dirs ["conf" "etc" "src" "doc" "public"]
     root (:root project)]
    (.mkdir toDir)
    (cleanDir toDir)
    (doseq [d dirs
            :let [src (io/file root d)]]
      (copyDir src (io/file toDir d)))
    (copyBin toDir)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn wabbit
  "Podify wabbit to standalone application."
  [project & args]
  (let
    [dir (second (drop-while
                   #(not= "--to-dir" %) args))
     dir (or dir
             (io/file (:root project) PKGDIR))
     dir (io/file dir)]
    (packFiles project dir)
    (packLib project dir)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn hookJavac
  ""
  [task & args]
  (apply task args))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn activate
  ""
  []
  (h/add-hook #'lj/javac #'hookJavac))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF
