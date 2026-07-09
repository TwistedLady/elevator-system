module HistoryTest exposing (suite)

import Expect
import History
import Test exposing (Test, describe, test)


suite : Test
suite =
    describe "History.push"
        [ test "appends the newest floor to the end" <|
            \_ -> History.push 48 5 [ 1, 2, 3 ] |> Expect.equal [ 1, 2, 3, 5 ]
        , test "keeps everything while under the cap" <|
            \_ -> History.push 3 4 [ 1, 2 ] |> Expect.equal [ 1, 2, 4 ]
        , test "drops the oldest once over the cap, keeping the newest" <|
            \_ -> History.push 3 4 [ 1, 2, 3 ] |> Expect.equal [ 2, 3, 4 ]
        , test "starts a fresh series from empty" <|
            \_ -> History.push 3 7 [] |> Expect.equal [ 7 ]
        ]
