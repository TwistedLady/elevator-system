module StatsTest exposing (suite)

import Expect
import Stats
import Test exposing (Test, describe, test)


data : List ( String, Int ) -> List ( String, Int ) -> Stats.Data
data mileage served =
    { mileage = List.map (\( name, n ) -> { name = name, floorsTravelled = n }) mileage
    , served = List.map (\( name, n ) -> { name = name, ordersServed = n }) served
    , loaded = True
    , failed = False
    }


suite : Test
suite =
    describe "Stats.merge"
        [ test "merges mileage and served into one row per elevator" <|
            \_ ->
                Stats.merge (data [ ( "e1", 10 ) ] [ ( "e1", 3 ) ])
                    |> Expect.equal [ { name = "e1", mileage = 10, served = 3 } ]
        , test "an elevator present in only one feed keeps 0 for the other" <|
            \_ ->
                Stats.merge (data [ ( "e1", 10 ) ] [ ( "e2", 5 ) ])
                    |> Expect.equal
                        [ { name = "e1", mileage = 10, served = 0 }
                        , { name = "e2", mileage = 0, served = 5 }
                        ]
        , test "sorts by natural name order (e2 before e10)" <|
            \_ ->
                Stats.merge (data [ ( "e10", 1 ), ( "e2", 1 ), ( "e1", 1 ) ] [])
                    |> List.map .name
                    |> Expect.equal [ "e1", "e2", "e10" ]
        ]
