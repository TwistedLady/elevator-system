module ChartTest exposing (suite)

import Chart
import Expect
import Test exposing (Test, describe, test)


suite : Test
suite =
    describe "Chart.rightAlign"
        [ test "front-pads a short history with Nothing, newest at the right edge" <|
            \_ ->
                Chart.rightAlign 4 [ 2, 3 ]
                    |> Expect.equal [ Nothing, Nothing, Just 2, Just 3 ]
        , test "keeps an exactly-length history unchanged" <|
            \_ ->
                Chart.rightAlign 3 [ 1, 2, 3 ]
                    |> Expect.equal [ Just 1, Just 2, Just 3 ]
        , test "trims a long history to the last len samples" <|
            \_ ->
                Chart.rightAlign 3 [ 1, 2, 3, 4, 5 ]
                    |> Expect.equal [ Just 3, Just 4, Just 5 ]
        , test "an empty history is all gaps" <|
            \_ ->
                Chart.rightAlign 2 []
                    |> Expect.equal [ Nothing, Nothing ]
        ]
