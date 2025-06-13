(ns com.blockshipper.util)

(defn vconcat
  "Concatenate VEC1 and VEC2 into a single vector."
  [vec1 vec2]
  (into [] (concat vec1 vec2)))

(defn concat-mapv
  "Concatenate VEC1 with MAP-VEC after running MAP-FUNC against it."
  [vec1 map-func map-vec]
  (vconcat vec1 (mapv map-func map-vec)))
