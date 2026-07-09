module History exposing (push)

{-| Rolling per-elevator floor history for the Trend tab: append the newest floor and keep at most
`maxLen` samples (oldest → newest), dropping from the front.
-}
push : Int -> Int -> List Int -> List Int
push maxLen floor previous =
    let
        appended =
            previous ++ [ floor ]
    in
    List.drop (max 0 (List.length appended - maxLen)) appended
