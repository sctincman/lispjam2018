(ns gameoff.cards.test
  (:require [reagent.core :as reagent :refer [atom]]
            [gameoff.core :as core]
            [gameoff.render.core :as render]
            [gameoff.signals :as signals]
            [gameoff.behavior :as behavior]
            [gameoff.physics :as physics]
            [devcards.core :as dc])
  (:require-macros
   [devcards.core
    :as dc
    :refer [defcard defcard-doc defcard-rg deftest]]))

(defcard-rg basic-cube
  (fn [game-state _] [core/reagent-renderer game-state])
  (atom {:test-cube {:position [0.0 0.0 0.0]
                     :rotation [0.0 0.0 0.0 1.0]
                     :renders {:base {:type :cube
                                      :geom :cube
                                      :material :cube}}}})
  {:inspect-data true})

(defcard-rg load-obj
  (fn [game-state _] [core/reagent-renderer game-state])
  (atom {:fox (-> {:position [0.0 0.0 0.0]
                   :rotation [0.0 0.0 0.0 1.0]
                   :renders {:base {:type :obj
                                    :path "obj/fox/"
                                    :geom "Fox.obj"
                                    :material "Fox.mtl"}}}
                  (behavior/player-movement {"w" :forward "s" :backward})
                  (physics/body 1.0 0.005))
         :camera (-> {:position [0.0 0.0 20.0]
                      :rotation [0.0 0.0 0.0 1.0]
                      :renders {}}
                     (behavior/player-movement {"a" :forward "d" :backward})
                     (physics/body 1.0 0.005))})
  {:inspect-data true})

(defcard-rg load-scene
  (fn [game-state _] [core/reagent-renderer game-state])
  (atom {:include "gltf/fox.gltf"
         :scene {:current-scene :Scene
                 :camera :PlayerCamera}
         :Fox (-> {:position [0.0 0.0 0.0]
                   :rotation [0.0 0.0 0.0 1.0]
                   :renders {}}
                  (behavior/player-movement
                   {"w" :forward
                    "s" :backward
                    "a" :left
                    "d" :right
                    "q" :turn-left
                    "e" :turn-right})
                  (behavior/moveable)
                  (physics/body 1.0 0.005))})
  {:inspect-data true})

(defonce signal-atom
  (let [a (atom 0)]
    (signals/map (fn [bah]
                   (reset! a bah))
                 (signals/tick 1000))
    a))

(defcard signal-watch
  "Signal update an atom"
  (fn [data-atom _]
    @signal-atom)
  signal-atom
  {:inspect-data true})

(defonce backing-atom
  (let [a (atom 0)
        out (signals/signal 0 :backing-atom a)]
    (signals/foldp inc 0 (signals/tick 1000) :out-signal out)
    a))

(defcard signal-watch2
  "Signal atom replaced"
  (fn [data-atom _]
    @backing-atom)
  backing-atom
  {:inspect-data true})

(defonce observed-atom
  (let [a (atom 0)]
    (js/setInterval (fn [] (swap! observed-atom inc)) 1000)
    a))

(defcard atom-observing-card observed-atom)
