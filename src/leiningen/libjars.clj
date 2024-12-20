;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
;;
;; Copyright © 2013-2024, Kenneth Leung. All rights reserved.

(ns leiningen.libjars

  (:require [leiningen.jar :as jar]
            [leiningen.pom :as pom]
            [leiningen.javac :as lj]
            [leiningen.test :as lt]
            [clojure.string :as cs]
            [clojure.pprint :as pp]
            [clojure.java.io :as io]
            [leiningen.core.utils :as cu]
            [leiningen.core.main :as cm]
            [leiningen.core.project :as pj]
            [leiningen.core.classpath :as cp])

  (:import [java.io
            File
            IOException]
           [java.nio.file
            Files
            Path
            Paths
            FileVisitResult
            SimpleFileVisitor]
           [java.nio.file.attribute BasicFileAttributes]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def ^:private pkg-dir "pkg")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- clean-dir

  "Clean out recursively a directory with native Java.
  https://docs.oracle.com/javase/tutorial/essential/io/walk.html"
  [dir]

  (let [root (io/file dir)]
    (->> (proxy [SimpleFileVisitor][]
           (visitFile [^Path file
                       ^BasicFileAttributes attrs]
             (Files/delete file)
             FileVisitResult/CONTINUE)
           (postVisitDirectory [^Path dir
                                ^IOException ex]
             (if (not= dir root)
               (Files/delete dir))
             FileVisitResult/CONTINUE))
         (Files/walkFileTree (Paths/get (.toURI root))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- pack-lib

  "Largely from leiningen.uberjar itself."
  [project toDir]

  (let [scoped (set (pj/pom-scope-profiles project :provided))
        dft (set (pj/expand-profile project :default))
        provided (remove (clojure.set/difference dft scoped)
                         (-> project meta :included-profiles))
        project (pj/merge-profiles
                  (pj/merge-profiles project
                                     [:uberjar]) provided)
        ;;_ (pom/check-for-snapshot-deps project)
        project (update-in project
                           [:jar-inclusions]
                           concat (:uberjar-inclusions project))
        [_ jar] (first (jar/jar project nil))
        whites (select-keys project pj/whitelist-keys)
        project (merge (pj/unmerge-profiles project
                                            [:default]) whites)
        deps (->> (cp/resolve-managed-dependencies
                   :dependencies
                   :managed-dependencies project)
                  (filter #(.endsWith (.getName ^File %) ".jar")))
        lib (io/file toDir)
        jars (cons (io/file jar) deps)]
    (.mkdirs lib)
    (doseq [^File fj jars
            :let [t (io/file lib (.getName fj))]] (io/copy fj t))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- sanitize

  "Interpolate the string."
  [src data]

  (reduce #(let [[k v] %2]
             (-> (cs/replace %1 (str "{{" k "}}") v)
                 (cs/replace (str "@@" k "@@") v))) src data))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- copy-bin

  "Copy files in the bin sub-dir."
  [project root]

  (let [c2 (.getContextClassLoader (Thread/currentThread))
        data {"kill-port" (str (:kill-port project))}
        bin (doto (io/file root "bin") .mkdirs)
        pfx "czlab/bixby/shared/bin/"
        arr {"log4j2.xml" false
             ;"bixby.bat" false
             "h2db" true
             "bixby" true}
        vmopts (cs/join \space
                        (->> (:jvm-opts project)
                             (map #(sanitize % data))))
        data (assoc data
                    "vmopts" vmopts
                    "agent" (str (:agentlib project)))]
    (doseq [[r edit?] arr]
      (when-some [u (.getResource c2 (str pfx r))]
        (with-open [inp (.openStream u)]
          (let [des (io/file bin r)]
            (if-not edit?
              (io/copy inp des)
              (spit des
                    (-> (slurp inp)
                        (sanitize data))))
            (.setExecutable des true)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- copy-dir

  "Copy files from src to des."
  [src des]

  (let [p (.getCanonicalPath ^File src)
        z (+ 1 (.length p))]
    (doseq [^File f (file-seq src)
            :let [cp (.getCanonicalPath f)
                  z' (.length cp)]
            :when (> z' z)]
      (let [part (.substring cp z)
            t (io/file des part)]
        (if (.isDirectory f)
          (.mkdirs t)
          (do (.mkdirs (.getParentFile t)) (io/copy f t)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- pack-files

  "Package files to a directory."
  [project toDir]

  (let [dirs ["conf" "etc" "src" "doc" "public"]]
    (doseq [d dirs
            :let [src (io/file (:root project) d)]]
      (copy-dir src (io/file toDir d)))
    (copy-bin project toDir)
    (let [d (io/file toDir "logs")]
      (.mkdirs d)
      (spit (io/file d "readme.txt") "log files"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- XXXbixby

  "Podify bixby to standalone application."
  [project & args]

  (let [dir (io/file (or (second (drop-while
                                   #(not= "--to-dir" %) args))
                         (io/file (:root project) pkg-dir)))]
    (.mkdir (io/file dir))
    (clean-dir dir)
    (pack-lib project dir)
    (pack-files project dir)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn libjars

  "Download all jars required by bixby."
  [project & args]

  (let [dir (io/file (:root project) "lib")]
    (.mkdir (io/file dir))
    (clean-dir dir)
    (pack-lib project dir)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

