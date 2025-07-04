(ns metabase-enterprise.serialization.v2.backfill-ids-test
  (:require
   [clojure.test :refer :all]
   [metabase-enterprise.serialization.test-util :as ts]
   [metabase-enterprise.serialization.v2.backfill-ids :as serdes.backfill]
   [metabase.test :as mt]
   [toucan2.core :as t2]))

(deftest backfill-needed-test
  (mt/with-empty-h2-app-db!
    (ts/with-temp-dpc [:model/Collection {c1-id :id} {:name "some collection"}
                       :model/Collection {c2-id :id} {:name "other collection"}
                       ;; These two deliberately have the same name!
                       :model/Collection {c3-id :id} {:name     "child collection"
                                                      :location (str "/" c1-id "/")}
                       :model/Collection {c4-id :id} {:name     "child collection"
                                                      :location (str "/" c2-id "/")}]

      (let [coll-ids [c1-id c2-id c3-id c4-id]
            all-eids #(t2/select-fn-set :entity_id :model/Collection :id [:in coll-ids])]
        (testing "all collections have entity_ids"
          (is (every? some? (all-eids))))

        (testing "removing the entity_ids"
          (doseq [id coll-ids]
            (t2/update! :model/Collection id {:entity_id nil}))
          (is (every? nil? (all-eids))))

        (testing "backfill now recreates them"
          (serdes.backfill/backfill-ids-for! :model/Collection)
          (is (every? some? (all-eids))))))))

(deftest no-overwrite-test
  (mt/with-empty-h2-app-db!
    (ts/with-temp-dpc [:model/Collection {c1-id :id c1-eid :entity_id} {:name "some collection"}
                       :model/Collection {c2-id :id}                   {:name "other collection"}]
      (testing "deleting the entity_id for one of them"
        (t2/update! :model/Collection c2-id {:entity_id nil})
        (is (= #{c1-eid nil}
               (t2/select-fn-set :entity_id :model/Collection :type nil))))

      (testing "backfill"
        (serdes.backfill/backfill-ids-for! :model/Collection)
        (testing "sets a blank entity_id"
          (is (some? (t2/select-one-fn :entity_id :model/Collection :id c2-id))))
        (testing "does not change the original entity_id"
          (is (= c1-eid (t2/select-one-fn :entity_id :model/Collection :id c1-id))))))))

(deftest repeatable-test
  (mt/with-empty-h2-app-db!
    (ts/with-temp-dpc [:model/Collection {c1-eid :entity_id} {:name "some collection"}
                       :model/Collection {c2-id :id}         {:name "other collection"}]
      (testing "deleting the entity_id for one of them"
        (t2/update! :model/Collection c2-id {:entity_id nil})
        (is (= #{c1-eid nil}
               (t2/select-fn-set :entity_id :model/Collection :type nil))))

      (testing "backfilling twice"
        (serdes.backfill/backfill-ids-for! :model/Collection)
        (let [first-eid (t2/select-one-fn :entity_id :model/Collection :id c2-id)]
          (t2/update! :model/Collection c2-id {:entity_id nil})
          (is (= #{c1-eid nil}
                 (t2/select-fn-set :entity_id :model/Collection :type nil)))
          (serdes.backfill/backfill-ids-for! :model/Collection)
          (testing "produces the same entity_id both times"
            (is (= first-eid (t2/select-one-fn :entity_id :model/Collection :id c2-id)))))))))
