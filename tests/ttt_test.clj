(ns ttt-test
  (:require [com.tictactoe :as ttt]
            [clojure.test :refer [deftest is testing]]))

(deftest move-test
  (testing "Testing making moves with tic-tac-toe."
    (let* [board (ttt/create-board 3 3)
           board-move-1 (ttt/make-move 'x board (ttt/pos 0 0))]
      (is (not= board board-move-1) "The move should be legal as it is a blank board."))))

(deftest board-state-test
  (testing "Testing each board state outcome."
    (let [stalemate-test [['x 'o 'x]
                          ['o 'x 'x]
                          ['o 'x 'o]]
          win-test       [['x 'o 'x]
                          ['o 'x 'x]
                          ['o 'x 'x]]
          con-test       [['x 'o 'x]
                          ['o 'x 'x]
                          ['o 'x 'blank]]
          position       (ttt/pos 2 2)]
      (is (= 'stalemate (ttt/get-state stalemate-test position)))
      (is (= 'win       (ttt/get-state win-test position)))
      (is (= nil        (ttt/get-state con-test position))))))
