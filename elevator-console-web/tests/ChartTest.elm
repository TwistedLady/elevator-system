module ChartTest exposing (suite)

import Chart
import Expect
import Test exposing (Test, describe, test)
import Types exposing (Direction(..), Motion(..))


suite : Test
suite =
    describe "Chart.carGlyph"
        [ test "suspended overrides motion, door open" <|
            \_ ->
                Chart.carGlyph True True Stopped Up |> Expect.equal "[S ]"
        , test "suspended, door closed" <|
            \_ ->
                Chart.carGlyph True False Stopped Up |> Expect.equal "[SX]"
        , test "moving up, door closed" <|
            \_ ->
                Chart.carGlyph False False Moving Up |> Expect.equal "[↑X]"
        , test "moving down, door closed" <|
            \_ ->
                Chart.carGlyph False False Moving Down |> Expect.equal "[↓X]"
        , test "stopped, door closed" <|
            \_ ->
                Chart.carGlyph False False Stopped Up |> Expect.equal "[ X]"
        , test "stopped, door open" <|
            \_ ->
                Chart.carGlyph False True Stopped Up |> Expect.equal "[  ]"
        ]
