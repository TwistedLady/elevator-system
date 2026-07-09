module FilterTest exposing (suite)

import Expect
import Filter
import Test exposing (Test, describe, test)


suite : Test
suite =
    describe "Filter.matches"
        [ test "empty query matches everything" <|
            \_ -> Filter.matches "" "e1" |> Expect.equal True
        , test "whitespace-only query matches everything" <|
            \_ -> Filter.matches "   " "e7" |> Expect.equal True
        , test "regex range matches inside it" <|
            \_ -> Filter.matches "e[1-3]" "e2" |> Expect.equal True
        , test "regex range rejects outside it" <|
            \_ -> Filter.matches "e[1-3]" "e9" |> Expect.equal False
        , test "regex is case-insensitive" <|
            \_ -> Filter.matches "E1" "e1" |> Expect.equal True
        , test "invalid regex falls back to a substring match" <|
            \_ -> Filter.matches "e1[" "front-e1[-lobby" |> Expect.equal True
        , test "invalid regex substring fallback is case-insensitive" <|
            \_ -> Filter.matches "E1[" "e1[" |> Expect.equal True
        , test "invalid regex substring fallback can miss" <|
            \_ -> Filter.matches "z9[" "e1" |> Expect.equal False
        ]
