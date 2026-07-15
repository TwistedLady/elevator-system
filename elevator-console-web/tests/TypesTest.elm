module TypesTest exposing (suite)

import Expect
import Json.Decode as Decode
import Test exposing (Test, describe, test)
import Types exposing (Direction(..), Motion(..))


suite : Test
suite =
    describe "Types"
        [ describe "naturalKey"
            [ test "orders numeric suffixes as numbers, not strings" <|
                \_ ->
                    Expect.lessThan (Types.naturalKey "e10") (Types.naturalKey "e2")
            , test "splits name into non-digit prefix and trailing number" <|
                \_ ->
                    Types.naturalKey "e12" |> Expect.equal ( "e", 12 )
            ]
        , describe "elevatorStateDecoder"
            [ test "decodes a well-formed frame with Up/Moving" <|
                \_ ->
                    Decode.decodeString Types.elevatorStateDecoder
                        """{"tag":"E","elevatorName":"e1","direction":"Up","motion":"Moving","floor":4}"""
                        |> Expect.equal
                            (Ok { tag = "E", name = "e1", direction = Up, motion = Moving, floor = 4, suspended = False })
            , test "maps DOWN/STOPPED case-insensitively" <|
                \_ ->
                    Decode.decodeString Types.elevatorStateDecoder
                        """{"elevatorName":"e1","direction":"down","motion":"stopped","floor":0}"""
                        |> Result.map (\s -> ( s.direction, s.motion ))
                        |> Expect.equal (Ok ( Down, Stopped ))
            , test "defaults suspended to False when absent" <|
                \_ ->
                    Decode.decodeString Types.elevatorStateDecoder
                        """{"elevatorName":"e1","direction":"Up","motion":"Moving","floor":4}"""
                        |> Result.map .suspended
                        |> Expect.equal (Ok False)
            , test "reads suspended when present" <|
                \_ ->
                    Decode.decodeString Types.elevatorStateDecoder
                        """{"elevatorName":"e1","direction":"Up","motion":"Stopped","floor":4,"suspended":true}"""
                        |> Result.map .suspended
                        |> Expect.equal (Ok True)
            , test "fails when elevatorName is missing" <|
                \_ ->
                    Decode.decodeString Types.elevatorStateDecoder
                        """{"direction":"Up","motion":"Moving","floor":4}"""
                        |> resultIsErr
                        |> Expect.equal True
            ]
        ]


resultIsErr : Result e a -> Bool
resultIsErr result =
    case result of
        Err _ ->
            True

        Ok _ ->
            False
