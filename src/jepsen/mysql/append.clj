(ns jepsen.mysql.append
  "A test for transactional list append."
  (:require [clojure.tools.logging :refer [info warn]]
            [clojure [pprint :refer [pprint]]
                     [string :as str]]
            [dom-top.core :refer [loopr with-retry]]
            [elle.core :as elle]
            [jepsen [checker :as checker]
             [client :as client]
             [core :as jepsen]
             [generator :as gen]
             [util :as util :refer []]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.tests.cycle.append :as append]
            [jepsen.mysql [client :as c]]
            [next.jdbc :as j]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql.builder :as sqlb]
            [slingshot.slingshot :refer [try+ throw+]]))

(def default-table-count 3)

(defn table-name
  "Takes an integer and constructs a table name."
  [i]
  (str "txn" i))

(defn table-for
  "What table should we use for the given key?"
  [table-count k]
  (table-name (mod (hash k) table-count)))

(defn append-using-on-dup!
  "Appends an element to a key using an INSERT ... ON DUPLICATE KEY UPDATE
  statement."
  [conn test query_id table k e]
  (j/execute!
    conn
    [(str query_id
          "insert into " table " as t"
          " (id, sk, val) values (?, ?, ?)"
          " on duplicate key update"
          " val = CONCAT(t.val, ',', ?)")
     k k e e]))

(defn insert!
  "Performs an initial insert of a key with initial element e. Catches
  duplicate key exceptions, returning true if succeeded. If the insert fails
  due to a duplicate key, it'll break the rest of the transaction, assuming
  we're in a transaction, so we establish a savepoint before inserting and roll
  back to it on failure."
  [conn test query_id txn? table k e]
  (try
    ;(info (if txn? "" "not") "in transaction")
    (when txn? (j/execute! conn [(str query_id "savepoint upsert")]))
    (j/execute! conn
                [(str query_id "insert into " table " (id, sk, val)"
                      " values (?, ?, ?)")
                 k k e])
    (when txn? (j/execute! conn [(str query_id "release savepoint upsert")]))
    true
    (catch java.sql.SQLIntegrityConstraintViolationException e
      (if (re-find #"Duplicate entry" (.getMessage e))
        (do (info (if txn? "txn") "insert failed: " (.getMessage e))
            (when txn? (j/execute! conn
                                   [(str query_id
                                        "rollback to savepoint upsert")]))
            false)
        (throw e)))))

(defn update!
  "Performs an update of a key k, adding element e. Returns true if the update
  succeeded, false otherwise."
  [conn test query_id table k e]
  (let [res (-> conn
                (j/execute-one! [(str query_id
                                      "update " table " set val = CONCAT(val, ',', ?)"
                                      " where id = ?") e k]))]
    (-> res
        :next.jdbc/update-count
        pos?)))

(defn mop!
  "Executes a transactional micro-op on a connection. Returns the completed
  micro-op."
  [conn test query_id txn? [f k v]]
  (let [table-count (:table-count test default-table-count)
        table (table-for table-count k)]
    (Thread/sleep (long (rand-int 10)))
    [f k (case f
           :r (let [r (j/execute! conn
                                  [(str query_id
                                        "select (val) from " table " where "
                                        ;(if (< (rand) 0.5) "id" "sk")
                                        "id"
                                        " = ? ")
                                   k]
                                  {:builder-fn rs/as-unqualified-lower-maps})]
                (when-let [v (:val (first r))]
                  (mapv parse-long (str/split v #","))))

           :append
           (let [vs (str v)]
             (if (:on-conflict test)
               ; Use ON CONFLICT
               (append-using-on-dup! conn test query_id table k vs)
               ; Try an update, and if that fails, back off to an insert.
               (or (update! conn test query_id table k vs)
                   ; No dice, fall back to an insert
                   (insert! conn test query_id txn? table k vs)
                   ; OK if THAT failed then we probably raced with another
                   ; insert; let's try updating again.
                   (update! conn test query_id table k vs)
                   ; And if THAT failed, all bets are off. This happens even
                   ; under SERIALIZABLE, but I don't think it technically
                   ; VIOLATES serializability.
                   (throw+ {:type     ::homebrew-upsert-failed
                            :key      k
                            :element  v})))
             v))]))

; initialized? is an atom which we set when we first use the connection--we set
; up initial isolation levels, logging info, etc. This has to be stateful
; because we don't necessarily know what process is going to use the connection
; at open! time.
(defrecord Client [node conn initialized?]
  client/Client
  (open! [this test node]
    (let [c (c/open test node)]
      (assoc this
             :node          node
             :conn          c
             :initialized?  (atom false))))

  (setup! [_ test]
    (when (= (jepsen/primary test) node)
      (when (compare-and-set! initialized? false true)
        (dotimes [i (:table-count test default-table-count)]
          (j/execute! conn
                      [(str "create table if not exists " (table-name i)
                            " (id int not null primary key,
                            sk int not null,
                            val text)")])
          ; Make sure we start fresh--in case we're using an existing
          ; cluster and the DB automation isn't wiping the state for us.
          (j/execute! conn [(str "delete from " (table-name i))])))))

  (invoke! [_ test op]
    ; One-time connection setup
    (when (compare-and-set! initialized? false true)
      (c/set-transaction-isolation! conn (:isolation test)))

    (c/with-errors op
      (let [txn       (:value op)
            use-txn?  (< 1 (count txn))
            query_id  (str "/* " (:index op) "_" (:time op) " */ ")
            txn'      (if use-txn?
                      ;(if true
                        (j/with-transaction [t conn
                                             {:isolation (:isolation test)}]
                          (mapv (partial mop! t test query_id true) txn))
                        (mapv (partial mop! conn test query_id false) txn))]
        (assoc op :type :ok, :value txn'))))

  (teardown! [_ test])

  (close! [this test]
    (c/close! conn)))

(defn read-only
  "Converts writes to reads."
  [op]
  (loopr [txn' []]
         [[f k v :as mop] (:value op)]
         (recur (conj txn' (case f
                             :r mop
                             [:r k nil])))
         (assoc op :value txn')))

(defn on-follower?
  "Is the given operation going to execute on a follower?"
  [test op]
  (not= 0 (mod (:process op) (count (:nodes test)))))

(defn ro-gen
  "Nothing stops you from writing to a secondary, which is, uh, exciting. We'll
  set up our generator to *only* emit reads to any non-primary node."
  [gen]
  (reify gen/Generator
    (update [this test ctx event]
      (ro-gen (gen/update gen test ctx event)))

    (op [this test ctx]
      (when-let [[op gen'] (gen/op gen test ctx)]
        (cond (= :pending op)
              [:pending this]

              (on-follower? test op)
              [(read-only op) (ro-gen gen')]

              true
              [op (ro-gen gen')])))))

(defn workload
  "A list append workload."
  [opts]
  (-> (append/test (assoc (select-keys opts [:key-count
                                             :max-txn-length
                                             :max-writes-per-key])
                          :min-txn-length 1
                          :consistency-models [(:expected-consistency-model opts)]))
      (assoc :client (Client. nil nil nil))
      (update :generator ro-gen)))
