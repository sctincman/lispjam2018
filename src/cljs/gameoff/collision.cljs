(ns gameoff.collision
  (:require [gameoff.signals :as s]
            [gameoff.render.core :as r]
            [gameoff.render.threejs.core :as render]
            [clojure.core.matrix :as m]))

(defprotocol ^:export IAABB
  (left [this])
  (right [this])
  (top [this])
  (bottom [this])
  (back [this])
  (front [this])
  (mins [this])
  (maxs [this]))

(defrecord ^:export AABB [center width height depth half-width half-height half-depth]
  IAABB
  (left [this] (- (first center) half-width))
  (right [this] (+ (first center) half-width))
  (bottom [this] (- (second center) half-height))
  (top [this] (+ (second center) half-height))
  (back [this] (- (nth center 2) half-depth))
  (front [this] (+ (nth center 2) half-depth))
  (mins [this] [(left this) (bottom this) (back this)])
  (maxs [this] [(right this) (top this) (front this)]))

(defrecord ^:export WABB [min-point max-point]
  IAABB
  (left [this] (first min-point))
  (right [this] (first max-point))
  (bottom [this] (nth min-point 2))
  (top [this] (nth max-point 2))
  (back [this] (second min-point))
  (front [this] (second max-point))
  (mins [this] min-point)
  (maxs [this] max-point))

(defn ^:export add-aabb [entity offset width height depth]
  (assoc entity :aabb
         (->AABB offset width height depth
                 (/ width 2.0) (/ height 2.0) (/ depth 2.0))))

(defn ^:export spatial?
  "Does component have a position and collision components."
  [entity]
  (and (some? (:position entity))
       (some? (:collision entity))))

(defn update-wabbs
  "A horrible hack!"
  [world]
  (let [current-scene (get-in world [:scene :current-scene])
        scenes @(get-in world [:backend :scenes])
        box3 (js/THREE.Box3.)
        empty (js/THREE.Object3D.)]
    (reduce-kv (fn [world id entity]
                 (if-not (spatial? entity)
                   world
                   (do (r/update-object! (:backend world) id world) ;because we use ThreeJS's AABB, we need to update from physics...
                       (if-let [root  (get-in scenes [current-scene :children id :root])]
                         (let [box3  (.setFromObject box3 root)
                               wabb (->WABB [(aget box3 "min" "x") (aget box3 "min" "y") (aget box3 "min" "z")]
                                            [(aget box3 "max" "x") (aget box3 "max" "y") (aget box3 "max" "z")])]
                           (assoc-in world [id :aabb] wabb))
                         world))))
               world
               world)))

(defn ^:export add-space
  "Spatial component. Stores a location based hash of entities with collision components."
  [world bucket-size]
  (let [inverse (/ 1 bucket-size)]
    (-> world
        (assoc-in [:space :bucket] [inverse inverse inverse])
        (assoc-in [:space :cells] (hash-map)))))

(defn pos->bucket-space
  [position bucket]
  (map int (m/mul position bucket)))

(defn aabb-buckets [space entity]
  (let [start-point (pos->bucket-space (m/add (:position entity)
                                        (mins (:aabb entity)))
                                 (:bucket space))
        end-point (pos->bucket-space (m/add (:position entity)
                                      (maxs (:aabb entity)))
                               (:bucket space))]
    (for [x (range (first start-point) (inc (first end-point)))
          y (range (second start-point) (inc (second end-point)))
          z (range (second start-point) (inc (second end-point)))]
      [x y z])))

(defn populate-cells
  "Buckets entities by their position and collisions"
  ([world]
   (let [bucket (get-in world [:space :bucket])]
     (reduce-kv (fn [cells id entity]
                  (if-not (and (spatial? entity) (some? (:aabb entity)))
                    cells
                    (let [start-point (pos->bucket-space (mins (get entity :aabb))
                                                         bucket)
                          end-point   (pos->bucket-space (maxs (get entity :aabb))
                                                         bucket)]
                      (reduce (fn [cells x]
                                (reduce (fn [cells y]
                                          (reduce (fn [cells z]
                                                    (update cells [x y z] conj id))
                                                  cells
                                                  (range (nth start-point 2) (inc (nth end-point 2)))))
                                        cells
                                        (range (second start-point) (inc (second end-point)))))
                              cells
                              (range (first start-point) (inc (first end-point)))))))
                (hash-map)
                world))))

(defn aabb-intersects? [first-aabb second-aabb]
  (and (some? first-aabb) (some? second-aabb)
       (and (<= (left first-aabb) (right second-aabb))
            (>= (right first-aabb) (left second-aabb)))
       (and (<= (bottom first-aabb) (top second-aabb))
            (>= (top first-aabb) (bottom second-aabb)))
       (and (<= (back first-aabb) (front second-aabb))
            (>= (front first-aabb) (back second-aabb)))))

(defn intersect-wabb [first-wabb second-wabb]
  (let [min-point (map max (mins first-wabb) (mins second-wabb))
        max-point (map min (maxs first-wabb) (maxs second-wabb))]
    (->WABB min-point max-point)))

(defn ^:export aabb-collision-pairs
  "Returns list of collision pairs"
  [world cells]
  (reduce-kv (fn [pairs cell children]
               (reduce (fn [pairs first-key]
                         (reduce (fn [pairs second-key]
                                   (if (and (not (= first-key second-key))
                                            (or (not (qualified-keyword? first-key))
                                                (not (= (namespace first-key)
                                                        (namespace second-key))))
                                            (aabb-intersects? (get-in world [first-key :aabb])
                                                              (get-in world [second-key :aabb])))
                                     (update pairs first-key
                                             (fn [others]
                                               (set (conj others second-key))))
                                     pairs))
                                 pairs
                                 children))
                       pairs
                       children))
             {}
             cells))

(defn null-handler [prime & more]
  prime)

(defn smallest-component [[x y z]]
  (cond
    (< x (min y z)) [x 0 0]
    (< y (min x z)) [0 y 0]
    :else [0 0 z]))

(defn largest-component [[x y z]]
  (cond
    (> x (max y z)) [x 0 0]
    (> y (max x z)) [0 y 0]
    :else [0 0 z]))

(defn wabb-closest-side-normal [point wabb]
  (let [[x y z] point
        [x1 y1 z1] (mins wabb)
        [x2 y2 z2] (maxs wabb)
        d-left (- x x1)
        d-right (- x2 x)
        d-back (- y y1)
        d-front (- y2 y)
        d-down (- z z1)
        d-up (- z2 z)]
    (cond
      (< d-left (min d-right d-back d-front d-up d-down)) [-1 0 0]
      (< d-right (min d-left d-back d-front d-up d-down)) [1 0 0]
      (< d-back (min d-right d-left d-front d-up d-down)) [0 -1 0]
      (< d-front (min d-left d-back d-right d-up d-down)) [0 1 0]
      (< d-down (min d-right d-back d-front d-up d-left)) [0 0 -1]
      (< d-up (min d-left d-back d-front d-right d-down)) [0 0 1])))

(defn clear-velocities [entity delta-t world]
  (update-in entity [:body :velocities] dissoc :collisions))

(defn wabb-solid-handler [prime second-key delta-t world]
  (if-let [mass-prime (get-in prime [:body :mass])]
    (let [mass-secondary (get-in world [second-key :body :mass] js/Infinity)
          overlap (intersect-wabb (:aabb prime) (get-in world [second-key :aabb]))
          dimensions (m/sub (maxs overlap) (mins overlap))
          center-point (m/sub (maxs overlap)
                              (m/mul 0.5 dimensions))
          weight-factor (- 1 (/ mass-prime (+ mass-prime mass-secondary)))

          direction (wabb-closest-side-normal center-point (get-in world [second-key :aabb]))
          displacement (m/mul weight-factor
                              dimensions
                              direction)
          ;;huge hack, but meh, game "physics",
          velocity-component (m/mul weight-factor
                                    (m/dot direction
                                           (get-in prime [:body :velocities :forces] [0 0 0]))
                                    direction)]
      (when (pos? (m/dot direction
                         (get world :up [0 0 1])))
        (when-let [standing? (get prime :standing?)]
          (s/propagate standing? true)))
      (-> prime
          (update :position m/add displacement)
          (update-in [:body :velocities :forces] m/sub velocity-component)
          (assoc-in [:body :v-total] (reduce m/add [0 0 0]
                                             (vals (get-in prime [:body :velocities]))))))
    prime))

(defn win-condition [prime second-key delta-t world]
  (println "WIN!")
  (assoc-in prime [:body :acceleration :forces] [0 0.00008 0]))

(defn handle-collision
  [primary-key secondary-key world delta-t]
  (let [primary-entity (get world primary-key)
        primary-event (get-in world [primary-key :collision :internal] null-handler)
        secondary-event (get-in world [secondary-key :collision :external] null-handler)]
    (-> primary-entity
        (primary-event secondary-key delta-t world)
        (secondary-event primary-key delta-t world))))

(defn ^:export handle-collisions
  "Not sure yet"
  [world delta-t]
  (let [world (update-wabbs world)
        cells (populate-cells world)
        world (assoc-in world [:space :cells] cells)
        pairs (aabb-collision-pairs world cells)]
    (reduce-kv (fn [world primary-key collider-keys]
                 (let [pre-event (get-in world [primary-key :collision :pre] null-handler)
                       post-event (get-in world [primary-key :collision :post] null-handler)
                       world (update world primary-key pre-event delta-t world)]
                   (update (reduce (fn [world collider-key]
                                     (assoc world primary-key
                                            (handle-collision primary-key collider-key world delta-t)))
                                   world
                                   collider-keys)
                           primary-key
                           post-event delta-t world)))
               world
               pairs)))
