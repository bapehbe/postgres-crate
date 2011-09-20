(ns pallet.crate.postgres
  "Install and configure PostgreSQL.

   # Settings

   Settings are constructed with the settings-map function.

   The settings map has a :permissions, :options, and :recovery keys
   used to specify the default database hba.conf, postgresal.conf and
   recovery.conf files. Paths in the default options should contain a
   \"%s\" for the database cluster name (n.b. a cluster is a group
   of databases running under the same postmaster process).

   The settings-map should be passed to the `settings` crate function
   which fills out the target specific paths, etc. Paths passed to `settings`
   should contain a \"%s\" to receive the database name.

   For each database cluster, `database-settings` must be called to set up the
   database specific settings from the default settings passed to the `settings`
   function.  Database specific settings may be set up by passing a settings map
   to the `database-settings` function. Paths passed to database-settings should
   be complete amd not contain \"%s\" placeholders.

   ## Settings map details

   These keys can also appear under the :dbs key for database cluster specific
   settings, in which case the paths should be plain strings.

   For example:

       {:options {:port 5432
                  :data_directory \"/var/lib/pgsql/%s\"}
        :clusters {:db1
               {:options {:port :5433
                          :data_directory \"/var/lib/pgsql/db1\"}}}}

   Links:
   -  http://blog.2ndquadrant.com/en/2010/05/install-multiple-postgresql-servers-redhat-linux.html
  "
  (:require
   [pallet.action :as action]
   [pallet.action.directory :as directory]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.file :as file]
   [pallet.action.package :as package]
   [pallet.action.package.debian-backports :as debian-backports]
   [pallet.action.remote-file :as remote-file]
   [pallet.action.service :as service]
   [pallet.crate.etc-default :as etc-default]
   [pallet.parameter :as parameter]
   [pallet.script.lib :as lib]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore]
   [pallet.thread-expr :as thread-expr]
   [clojure.contrib.condition :as condition]
   [clojure.tools.logging :as logging]
   [clojure.string :as string])
  (:use
   pallet.thread-expr
   [pallet.script :only [defscript]]))

(def ^{:private true} pallet-cfg-preamble
"# This file was auto-generated by Pallet. Do not edit it manually unless you
# know what you are doing. If you are still using Pallet, you probably want to
# edit your Pallet scripts and rerun them.\n\n")


(def ^{:doc "Flag for recognising changes to configuration"}
  postgresql-config-changed-flag "postgresql-config")

(def default-settings-map
  {:version "9.0"
   :components #{:server :contrib}
   :owner "postgres"
   :options {:port 5432
             :max_connections 100
             :ssl false
             :shared_buffers "24MB"
             :log_line_prefix "%t "
             :datestyle "iso, ymd"
             :default_text_search_config "pg_catalog.english"}
   :permissions [["local" "all" "postgres" "ident" ""]
                 ["local" "postgres" "postgres" "ident" ""]]
   :start {:start :auto}
   :listen_addresses "127.0.0.1"})

(def allow-ident-permissions
  [["host" "all" "all" "127.0.0.1/32" "ident"]
   ["host" "all" "all" "::1/128" "ident"]])

(defn merge-settings
  "Merge postgresql settings maps"
  [& settings]
  (reduce
   (fn [defaults overrides]
     (merge
      defaults
      overrides
      (into {} (map
                #(vector % (merge (% defaults) (% overrides)))
                [:options :recovery :start]))
      (into {}
            (map
             #(vector % (distinct (concat (% defaults) (% overrides))))
             [:permissions]))))
   settings))

;;; default values, which are distribution and package dependent

(defn package-source
  "Decide where to get the packages from"
  [session version]
  (let [os-family (session/os-family session)]
    (cond
     (and (= :debian os-family) (= "9.0" version)) :debian-backports
     (and (= :ubuntu os-family) (= "9.0" version)) :martin-pitt-backports
     (and (= :centos os-family) (= "9.0" version)) :pgdg
     (and (= :fedora os-family) (= "9.0" version)) :pgdg
     :else :native)))

(def pgdg-repo-versions
  {"9.0" "9.0-2"})

(defmulti default-settings
  "Determine the default settings for the specified "
  (fn [session os-family package-source settings]
    [os-family package-source]))

(defn base-settings [session]
  {:service "postgresql"
   :owner "postgres"
   :initdb-via :initdb
   :options {:external_pid_file (str (stevedore/script (~lib/pid-root))
                                     "/postgresql.pid")}})

(defmethod default-settings [:debian :native]
  [session os-family package-source settings]
  (let [version (:version settings)]
    (merge-settings
     (base-settings session)
     {:packages ["postgresql"]
      :default-cluster-name "main"
      :bin (format "/usr/lib/postgresql/%s/bin/" version)
      :share (format "/usr/lib/postgresql/%s/share/" version)
      :wal_directory (format "/var/lib/postgresql/%s/%%s/archive" version)
      :postgresql_file (format
                        "/etc/postgresql/%s/%%s/postgresql.conf" version)
      :has-pg-wrapper true
      :has-multicluster-service true
      :options
      {:data_directory (format "/var/lib/postgresql/%s/%%s" version)
       :hba_file (format "/etc/postgresql/%s/%%s/pg_hba.conf" version)
       :ident_file (format "/etc/postgresql/%s/%%s/pg_ident.conf" version)
       :external_pid_file (format "/var/run/postgresql/%s-%%s.pid" version)
       :unix_socket_directory "/var/run/postgresql"}})))

(defmethod default-settings [:rh :native]
  [session os-family package-source settings]
  (let [version (:version settings)]
    (merge-settings
     (base-settings session)
     {:packages (map
                 #(str "postgresql-" (name %))
                 (:components settings #{:server :libs}))
      :default-cluster-name "data"
      :wal_directory (format "/var/lib/pgsql/%s/%%s/archive" version)
      :postgresql_file (format "/var/lib/pgsql/%s/%%s/postgresql.conf" version)
      :options
      {:data_directory (format "/var/lib/pgsql/%s/%%s" version)
       :hba_file (format "/var/lib/pgsql/%s/%%s/pg_hba.conf" version)
       :ident_file (format "/var/lib/pgsql/%s/%%s/pg_ident.conf" version)
       :external_pid_file (format "/var/run/postmaster-%s-%%s.pid" version)}})))

(defmethod default-settings [:rh :pgdg]
  [session os-family package-source settings]
  (let [version (:version settings)]
    (merge-settings
     (base-settings session)
     (default-settings session :rh :native settings)
     {:packages (map
                 #(str "postgresql" (string/replace version "." "")
                       "-" (name %))
                 (:components settings))
      :bin (format "/usr/pgsql-%s/bin/" version)
      :share (format "/usr/pgsql-%s/share/" version)
      :default-cluster-name "data"
      :service (str "postgresql-" version "-%s")
      :default-service (str "postgresql-" version)
      :use-port-in-pidfile true
      :wal_directory (format "/var/lib/pgsql/%s/%%s/archive" version)
      :postgresql_file (format "/var/lib/pgsql/%s/%%s/postgresql.conf" version)
      :options
      {:data_directory (format "/var/lib/pgsql/%s/%%s" version)
       :hba_file (format "/var/lib/pgsql/%s/%%s/pg_hba.conf" version)
       :ident_file (format "/var/lib/pgsql/%s/%%s/pg_ident.conf" version)}})))

(defmethod default-settings [:arch :native]
  [session os-family package-source settings]
  (let [version (:version settings)]
    (merge-settings
     (base-settings session)
     {:components []
      :packages ["postgresql"]
      :default-cluster-name "data"
      :initdb-via :initdb
      :wal_directory "/var/lib/postgres/%%s/archive/"
      :postgresql_file  "/var/lib/postgres/%%s/postgresql.conf"
      :options
      {:data_directory "/var/lib/postgres/%%s/"
       :hba_file  "/var/lib/postgres/%%s/pg_hba.conf"
       :ident_file "/var/lib/postgres/%%s/pg_ident.conf"}})))

(defmethod default-settings [:debian :debian-backports]
  [session os-family package-source settings]
  (let [version (:version settings)]
    (merge-settings
     (default-settings session :debian :native settings)
     {:packages [(str "postgresql-" version)]})))

(defmethod default-settings [:debian :martin-pitt-backports]
  [session os-family package-source settings]
  (default-settings session :debian :debian-backports settings))

;;; pg_hba.conf

(def ^{:private true}
  auth-methods #{"trust" "reject" "md5" "password" "gss" "sspi" "krb5"
                                     "ident" "ldap" "radius" "cert" "pam"})
(def ^{:private true}
  ip-addr-regex #"[0-9]{1,3}.[0-9]{1,3}+.[0-9]{1,3}+.[0-9]{1,3}+")

(defn- valid-hba-record?
  "Takes an hba-record as input and minimally checks that it could be a valid
   record."
  [{:keys [connection-type database user auth-method address ip-mask]
    :as record-map}]
  (and (#{"local" "host" "hostssl" "hostnossl"} (name connection-type))
       (every? #(not (nil? %)) [database user auth-method])
       (auth-methods (name auth-method))))

(defn- vector-to-map
  [record]
  (case (name (first record))
    "local" (apply
             hash-map
             (interleave
              [:connection-type :database :user :auth-method
               :auth-options]
              record))
    ("host"
     "hostssl"
     "hostnossl") (let [[connection-type database user address
                         & remainder] record]
     (if (re-matches
          ip-addr-regex (first remainder))
       ;; Not nil so must be an IP mask.
       (apply
        hash-map
        (interleave
         [:connection-type :database :user
          :address :ip-mask :auth-method
          :auth-options]
         record))
       ;; Otherwise, it may be an auth-method.
       (if (auth-methods
            (name (first remainder)))
         (apply
          hash-map
          (interleave
           [:connection-type :database :user
            :address :auth-method
            :auth-options]
           record))
         (condition/raise
          :type :postgres-invalid-hba-record
          :message
          (format
           "The fifth item in %s does not appear to be an IP mask or auth method."
           (pr-str record))))))
    (condition/raise
     :type :postgres-invalid-hba-record
     :message (format
               "The first item in %s is not a valid connection type."
               (name record)))))

(defn- record-to-map
  "Takes a record given as a map or vector, and turns it into the map version."
  [record]
  (cond
   (map? record) record
   (vector? record) (vector-to-map record)
   :else
   (condition/raise
    :type :postgres-invalid-hba-record
    :message (format "The record %s must be a vector or map." (name record)))))

(defn- format-auth-options
  "Given the auth-options map, returns a string suitable for inserting into the
   file."
  [auth-options]
  (string/join "," (map #(str (first %) "=" (second %)) auth-options)))

(defn- format-hba-record
  [record]
  (let [record-map (record-to-map record)
        record-map (assoc record-map :auth-options
                          (format-auth-options (:auth-options record-map)))
        ordered-fields (map #(% record-map "")
                            [:connection-type :database :user :address :ip-mask
                             :auth-method :auth-options])
        ordered-fields (map name ordered-fields)]
    (if (valid-hba-record? record-map)
      (str (string/join "\t" ordered-fields) "\n"))))

;;; postgresql.conf

(defn database-data-directory
  "Given a settings map and a database name, return the data directory
   for the database."
  [settings cluster]
  (format "%s/%s/recovery.conf" (-> settings :options :data_directory) cluster))

(defn- parameter-escape-string
  "Given a string, escapes any single-quotes."
  [string]
  (apply str (replace {\' "''"} string)))

(defn- format-parameter-value
  [value]
  (cond (number? value)
        (str value)
        (string? value)
        (str "'" value "'")
        (vector? value)
        (str "'" (string/join "," (map name value)) "'")
        (or (= value true) (= value false))
        (str value)
        :else
        (condition/raise
         :type :postgres-invalid-parameter
         :message (format
                   (str
                    "Parameters must be numbers, strings, or vectors of such. "
                    "Invalid value %s") (pr-str value))
         :value value)))

(defn- format-parameter
  "Given a key/value pair in a vector, formats it suitably for the
   postgresql.conf file.
   The value should be either a number, a string, or a vector of such."
  [[key value]]
  (let [key-str (name key)
        parameter-str (format-parameter-value value)]
    (str key-str " = " parameter-str "\n")))

(defn- format-start
  [[key value]]
  (name value))

;;; database cluster variants

(defn hot-standby-master
  "Set up hot standby master defaults"
  [settings]
  (merge-settings
   {:options
    {:wal_level "hot_standby"
     :max_wal_senders 5
     :wal_keep_segments 32
     :archive_mode "on"
     :archive_command (str "cp %p " (-> settings :wal_directory) "/%f")}}
   settings))

(defn hot-standby-replica
  "Set up hot standby replica defaults"
  [settings]
  (merge-settings
   {:options {:hot_standby "on"}
    :recovery
    {:standby_mode "on"
     :trigger_file (str (-> settings :options :data_directory) "/pg-failover")
     :restore_command (str "cp " (-> settings :wal_directory) "/%f \"%p\"")}}
   settings))

;;; Cluster specific settings

(defn cluster-settings-with-defaults
  "Combines a cluster specific settings map with the default settings"
  [cluster settings-map defaults]
  (let [expand-entry (fn [v]
                       (if (and (string? v) (re-find #".*%s" v))
                         (format v cluster)
                         v))
        expand (fn [m] (zipmap (keys m) (map expand-entry (vals m))))
        defaults (dissoc defaults :clusters) ; avoid nesting :clusters keys
        settings (merge-settings
                  (into {} (expand
                            (dissoc defaults :options :recovery :permissions)))
                  {:permissions (:permissions defaults)}
                  (into {} (map
                            #(vector % (expand (% defaults)))
                            [:options :recovery :start]))
                  settings-map)]
    (->
     settings
     ;; some paths that are always dependent on others
     (assoc :recovery_file (str
                            (-> settings :options :data_directory)
                            "/recovery.conf")
            :start_file (string/replace
                         (:postgresql_file settings)
                         #"postgresql.conf"
                         "start.conf"))
     ;; some specific differences
     (update-in [:service]
                #(if (and (= cluster (:default-cluster-name defaults))
                          (:default-service defaults))
                   (:default-service defaults)
                   %))
     (update-in [:options :external_pid_file]
                #(if (:use-port-in-pidfile defaults)
                   (format
                    (-> defaults :options :external_pid_file)
                    (-> settings :options :port))
                   %)))))

(defn settings-for-cluster
  "Returns the settings for the specified cluster"
  [session cluster & {:keys [instance]}]
  (get-in
   (parameter/get-target-settings session :postgresql instance)
   [:clusters (keyword cluster)]))

(defn check-settings
  "Check that settings are valid"
  [session settings cluster-settings cluster & keys]
  (let [error-fn (fn [session ^String message]
                   (logging/error (format message cluster settings))
                   (assert false)
                   session)
        missing-keys (remove #(get-in cluster-settings %) keys)]
    (->
     session
     (thread-expr/when->
      (not settings)
      (error-fn "No settings found %s %s"))
     (thread-expr/when->
      (not cluster-settings)
      (error-fn "No cluster settings found %s %s"))
     (thread-expr/when->
      (seq missing-keys)
      (error-fn (format "Missing keys %s %%s %%s" (vec missing-keys)))))))

(defn conf-file
  "Generates a postgresql configuration file"
  [session file-keys values-kw formatter & {:keys [instance cluster]}]
  (let [settings (parameter/get-target-settings session :postgresql instance)
        cluster (or cluster (:default-cluster-name settings))
        cluster-settings (settings-for-cluster
                          session cluster :instance instance)
        conf-path (get-in cluster-settings file-keys)
        hba-contents (apply str pallet-cfg-preamble
                            (map formatter (values-kw cluster-settings)))]
    (->
     session
     (check-settings settings cluster-settings cluster file-keys)
     (directory/directory
      (stevedore/script @(~lib/dirname ~conf-path))
      :owner (:owner settings "postgres") :mode "0700" :path true)
     (remote-file/remote-file
      conf-path
      :content hba-contents
      :literal true
      :flag-on-changed postgresql-config-changed-flag
      :owner (:owner settings)))))

(defn default-cluster-name
  "Returns the default cluster name"
  [session & {:keys [instance]}]
  (let [settings (parameter/get-target-settings session :postgresql instance)]
    (:default-cluster-name settings)))

;;; Crate functions

(defn settings-map
  "Build a settings map for postgresql.
      :version     postgresql version to install, eg \"9.0\"
      :components  postgresql components to install
      :permissions permissions to set in pg_hba.conf A sequence of records
                   (either vectors or maps of keywords/strings).
      :options     options to set in postgresql.conf
      :recovery    options to set in recovery.conf

   Unrecognised options will be added to the main configuration file.

   Example: (settings-map
              {:options {:listen_address [\"10.0.1.1\",\"localhost\"]}})"
  [{:keys [version components permissions options recovery]
    :as args}]
  (merge-settings default-settings-map args))

(defn cluster-settings
  "Add cluster specific postgresql settings map to the session map.
   This crate function must be called (usually in the :settings phase)
   for each database cluster you want to manage. Must be called after
   `settings`.

   Options:
   - instance     Specify the postgres instance to use for the cluster
   - variant      Specify a variant for the cluster. Current options are
                  :hot-standby-master and :hot-standby-replica

   For variant :hot-standby-replica, you will need to pass :primary_conninfo
   in the `settings-map`"
  [session cluster-name settings-map & {:keys [instance variant]}]
  (let [settings (parameter/get-target-settings session :postgresql instance)
        settings (cluster-settings-with-defaults
                   cluster-name settings-map settings)
        settings (case variant
                   :hot-standby-master (hot-standby-master settings)
                   :hot-standby-replica (hot-standby-replica settings)
                   settings)]
    (parameter/update-target-settings
     session :postgresql instance
     assoc-in [:clusters (keyword cluster-name)]
     settings)))

(defn settings
  "Add postgresql settings to the session map."
  [session settings-map & {:keys [instance]}]
  (let [package-source (package-source session (:version settings-map))
        settings (merge-settings
                  {:package-source package-source}
                  (default-settings
                    session (session/base-distribution session)
                    package-source settings-map)
                  settings-map)
        old-settings (parameter/get-target-settings
                      session :postgresql instance nil)]
    (logging/debugf "Postgresql Settings %s" settings)
    (->
     session
     (parameter/assoc-target-settings
      :postgresql instance
      (assoc settings :clusters (:clusters old-settings)))
     (thread-expr/when-let->
      [cluster-name (:default-cluster-name settings)]
      (cluster-settings cluster-name {} :instance instance)))))

(defn postgres
  "Install postgres."
  [session & {:keys [instance]}]
  (let [os-family (session/os-family session)
        settings (parameter/get-target-settings session :postgresql instance)
        packages (:packages settings)
        package-source (:package-source settings)
        version (:version settings)]
    (logging/debugf
     "postgresql %s from %s packages [%s]"
     version (name package-source) (string/join ", " packages))
    (->
     session
     (when-> (= package-source :martin-pitt-backports)
             (package/package-source
              "Martin Pitt backports"
              :aptitude {:url "ppa:pitti/postgresql"})
             (package/package-manager :update))
     (when-> (= package-source :debian-backports)
             (debian-backports/add-debian-backports)
             (package/package-manager :update)
             (package/package
              "libpq5"
              :enable (str
                       (stevedore/script (~lib/os-version-name))
                       "-backports")))
     (when->
      (= package-source :pgdg)
      (action/with-precedence {:action-id ::add-pgdg-rpm
                               :always-before `package/package}
        (package/add-rpm
         "pgdg.rpm"
         :url (format
               "http://yum.pgrpms.org/reporpms/%s/pgdg-%s-%s.noarch.rpm"
               version (name os-family) (pgdg-repo-versions version))))
      (action/with-precedence {:action-id ::pgdg-update
                               :always-before `package/package
                               :always-after ::add-pgdg-rpm}
        (package/package-manager :update)))
     ;; install packages
     (arg-> [session]
            (for-> [package (:packages settings)]
                   (package/package package))))))

(defn hba-conf
  "Generates a pg_hba.conf file from the arguments. Each record is either a
   vector or map of keywords/args.

   Note that pg_hba.conf is case-sensitive: all means all databases, ALL is a
   database named ALL.

   Also note that if you intend to execute subsequent commands, you'd do best to
   include entries in here that allow the admin user you are using easy access
   to the database. For example, allow the postgres user to have ident access
   over local.

   Options:
   :cluster        The database cluster to use
   :instance  The postgres instance to use"
  [session & {:keys [instance cluster]}]
  (conf-file
   session [:options :hba_file] :permissions format-hba-record
   :instance instance :cluster cluster))

(defn postgresql-conf
  "Generates a postgresql.conf file from the arguments.

   Options:
   :cluster        The database cluster to use
   :instance  The postgres instance to use"
  [session & {:keys [instance cluster]}]
  (conf-file
   session [:postgresql_file] :options format-parameter
   :instance instance :cluster cluster))

(defn recovery-conf
  "Generates a recovery.conf file from the arguments.

   Options:
   :cluster        The database cluster to use
   :instance  The postgres instance to use"
  [session & {:keys [instance cluster]}]
  (conf-file
   session [:recovery_file] :recovery format-parameter
   :instance instance :cluster cluster))

(defn start-conf
  "Generates a start.conf file from the arguments. This is debian specific. See
   `service-config`, which works across distributions.

   Options:
   :cluster        The database cluster to use
   :instance  The postgres instance to use"
  [session & {:keys [instance cluster]}]
  (conf-file
   session [:start_file] :start format-start
   :instance instance :cluster cluster))

(defn install-service
  "Generates a start.conf file from the arguments. This is specific to
   non-debian distributions. See `service-config`, which works across
   distributions.

   Options:
   :cluster        The database cluster to use
   :instance  The postgres instance to use"
  [session & {:keys [instance cluster]}]
  (let [settings (parameter/get-target-settings session :postgresql instance)]
    (->
     session
     (thread-expr/for->
      [cluster (keys (:clusters settings))]
      (thread-expr/let->
       [cluster-name (name cluster)
        cluster-settings (settings-for-cluster session cluster-name)]
       (thread-expr/when->
        (not= cluster-name (:default-cluster-name settings))
        (service/init-script
         (:service cluster-settings)
         :remote-file (service/init-script-path (:default-service settings)))
        (etc-default/write
         (str "pgsql/" (:service cluster-settings))
         :PGDATA (-> cluster-settings :options :data_directory)
         :PGPORT (-> cluster-settings :options :port))
        (thread-expr/if->
         (= :auto (-> cluster-settings :start :start))
         (service/service (:service cluster-settings) :action :enable)
         (service/service (:service cluster-settings) :action :disable))))))))

(defn service-config
  "Configure the service architecture."
  [session & {:keys [instance cluster]}]
  (let [settings (parameter/get-target-settings session :postgresql instance)]
    (if (:has-multicluster-service settings)
      (start-conf session :instance instance :cluster cluster)
      (install-service session :instance instance :cluster cluster))))

(declare service)

(defn initdb
  "Initialise a cluster"
  [session & {:keys [instance cluster]}]
  (let [settings (parameter/get-target-settings session :postgresql instance)
        cluster (or cluster (:default-cluster-name settings))
        initdb-via (:initdb-via settings :initdb)
        cluster-settings (settings-for-cluster
                          session cluster :instance instance)
        data-dir (-> cluster-settings :options :data_directory)]
    (case initdb-via
      :service (service session :action :initdb)
      :initdb (->
               session
               (directory/directory
                data-dir
                :owner (:owner settings "postgres")
                :mode "0700"
                :path true)
               (exec-script/exec-checked-script
                "initdb"
                (if (not (file-exists? ~(str data-dir "/PG_VERSION")))
                  (sudo
                   -u ~(:owner settings "postgres")
                   (str ~(or (:bin settings) "") initdb)
                   -D ~data-dir)))))))

;;; Scripts

(defn postgresql-script
  "Execute a postgresql script.

   The script is specified using remote-file content options (:content for
   a literal script)

   Options for how this script should be run:
     :as-user username       - Run this script having sudoed to this (system)
                               user. Default: postgres
     :db-name database       - the name of the database to connect to
     :cluster cluster-name   - the name of the cluster to connect to
     :instance instance-name - the instance (pg install) to use
     :ignore-result          - Ignore any error return value out of psql
     :title string           - A title to be used in script output."
  [session & {:keys [as-user instance cluster db-name ignore-result show-stdout
                     title]
              :or {show-stdout true}
              :as options}]
  (let [settings (parameter/get-target-settings session :postgresql instance)
        cluster (or cluster (:default-cluster-name settings))
        cluster-settings (settings-for-cluster session cluster)
        as-user (or as-user (-> settings :owner))
        file (str (stevedore/script @TMPDIR:-/tmp) "/"
                  (gensym "postgresql") ".sql")]
    (-> session
        (apply-map->
         remote-file/remote-file
         file
         :no-versioning true
         :owner as-user
         (select-keys options remote-file/content-options))
        (exec-script/exec-checked-script
         ;; Note that we stuff all output. This is because certain commands in
         ;; PostgreSQL are idempotent but spit out an error and an error exit
         ;; anyways (eg, create database on a database that already exists does
         ;; nothing, but is counted as an error).
         ;; Subshell used to isolate any cd
         (str "psql script" (if title (str " - " title) ""))
         ("(\n"
          cd (~lib/user-home ~as-user) "&&"
          sudo "-u" ~as-user
          ~(if (:has-pg-wrapper settings)
             ""
             (format
              "env PGDATA=%s PGPORT=%s"
              (-> cluster-settings :options :data_directory)
              (-> cluster-settings :options :port)))
          psql
          ~(if (:has-pg-wrapper settings)
             (format "--cluster %s/%s" (:version settings) cluster)
             "")
          ~(if db-name (str "-d " db-name) "")
          "-f" ~file
          ~(if show-stdout "" ">-")
          ~(if ignore-result "2>-" "")
          ~(if ignore-result "|| true" "") "\n )"))
        (remote-file/remote-file file :action :delete))))

(defn create-database
  "Create a database if it does not exist.

   You can specify database parameters by including a keyed parameter called
   :db-parameters, which indicates a vector of strings or keywords that will get
   translated in order to the options to the create database command. Passes on
   key/value arguments it does not understand to postgresql-script.

   Example: (create-database
              \"my-database\" :db-parameters [:encoding \"'LATIN1'\"])"
  [session db-name & rest]
  (let [{:keys [db-parameters db] :as options} rest
        db-parameters-str (string/join " " (map name db-parameters))]
    ;; Postgres simply has no way to check if a database exists and issue a
    ;; "CREATE DATABASE" only in the case that it doesn't. That would require a
    ;; function, but create database can't be done within a transaction, so
    ;; you're screwed. Instead, we just use the fact that trying to create an
    ;; existing database does nothing and stuff the output/error return.
    (apply postgresql-script
           session
           :content (format "CREATE DATABASE %s %s;" db-name db-parameters-str)
           :literal true
           (conj (vec rest) :ignore-result true))))

;; This is a format string that generates a temporary PL/pgsql function to
;; check if a given role exists, and if not create it. The first argument
;; should be the role name, the second should be any user-parameters.
(defn- create-role-pgsql
  [version]
  (cond
   (re-matches #"9.[0-2]" version)
   "do $$declare user_rec record;
BEGIN
 select into user_rec * from pg_roles where rolname='%1$s';
 if user_rec.rolname is null then
     create role %1$s %2$s;
 else
     alter role %1$s %2$s;
 end if;
END$$;"
   :else
   "create or replace function pg_temp.createuser() returns void as $$
 declare user_rec record;
 begin
 if user_rec.usename is null then
     create role %1$s %2$s;
 end if;
 end;
 $$ language plpgsql;
 select pg_temp.createuser();"))

(defn create-role
  "Create a postgres role if it does not exist.

   You can specify user parameters by including a keyed parameter called
   :user-parameters, which indicates a vector of strings or keywords that will
   get translated in order to the options to the create user command. Passes on
   key/value arguments to postgresql-script.

   Example (create-role
             \"myuser\" :user-parameters [:encrypted :password \"'mypasswd'\"])"
  [session username & rest]
  (let [{:keys [user-parameters db instance] :as options} rest
        settings (parameter/get-target-settings session :postgresql instance)
        user-parameters-str (string/join " " (map name user-parameters))]
    (apply postgresql-script
           session
           :content (format
                     (create-role-pgsql (:version settings))
                     username
                     (if (string/blank? user-parameters-str)
                       ""
                       (str "WITH " user-parameters-str)))
           :literal true
           rest)))


(defn service
  "Control the postgresql service.

   Specify `:if-config-changed true` to make actions conditional on a change in
   configuration.

   Other options are as for `pallet.action.service/service`. The service
   name is looked up in the request parameters."
  [session & {:keys [action if-config-changed if-flag instance] :as options}]
  (let [settings (parameter/get-target-settings session :postgresql instance)
        service (:service settings)
        options (if if-config-changed
                  (assoc options :if-flag postgresql-config-changed-flag)
                  options)]
    (->
     session
     (thread-expr/if->
      (:has-multicluster-service settings)
      (thread-expr/apply-map-> service/service service options)
      (thread-expr/for->
       [cluster (keys (:clusters settings))]
       (thread-expr/let->
        [cluster-name (name cluster)
         cluster-settings (settings-for-cluster session cluster-name)]
        (thread-expr/if->
         (= :auto (-> cluster-settings :start :start))
         (thread-expr/apply-map->
          service/service (:service cluster-settings) options))))))))

(defn controldata-script
  [session {:keys [as-user instance cluster] :as options}]
  (let [settings (parameter/get-target-settings session :postgresql instance)
         cluster (or cluster (:default-cluster-name settings))
         cluster-settings (settings-for-cluster session cluster)
         as-user (or as-user (-> settings :owner))]
     (stevedore/script
      (sudo "-u" ~as-user
       ~(if-let [bin (:bin settings)]
          (str bin "/pg_controldata")
          "pg_controldata")
       ~(-> cluster-settings :options :data_directory)))))

(defn controldata
  "Execute pg_controldata."
  [session & {:keys [as-user instance cluster] :as options}]
  (-> session
      (exec-script/exec-checked-script
       "pg_controldata"
       (~controldata-script session options))))

(defn log-settings
  "Log postgresql settings"
  [session & {:keys [instance level] :or {level :info}}]
  (let [settings (parameter/get-target-settings session :postgresql instance)]
    (logging/log level (format "Postgresql %s %s" (or instance "") settings))
    session))
