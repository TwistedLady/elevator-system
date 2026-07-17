module Chart exposing (Palette, carGlyph, floorAxis, palette, view)

{-| The CHART tab: a monospace "BUILDING" grid mirroring the Rust CLI. Rows are floors (top down),
columns are elevators (natural-sorted); each occupied cell is a `[c1c2]` glyph — c1 is motion/suspend
(S / ↑ / ↓ / space), c2 is the door (X closed, space open). Also home to the shared palette + floor
axis reused by the Trend tab (ECharts).
-}

import Dict exposing (Dict)
import Html exposing (Html, div, span, table, tbody, td, text, th, thead, tr)
import Html.Attributes exposing (class)
import Json.Encode as E
import Types exposing (Direction(..), Motion(..), Row, Theme(..), naturalKey)


type alias Palette =
    { text : String
    , subtext : String
    , axis : String
    , split : String
    , moving : String
    , idle : String
    , suspended : String
    , door : String
    , tooltipBg : String
    , tooltipText : String
    , series : List String
    }


palette : Theme -> Palette
palette theme =
    case theme of
        Dark ->
            { text = "#eceff1"
            , subtext = "#b0bec5"
            , axis = "#455a64"
            , split = "#2b3a41"
            , moving = "#64b5f6"
            , idle = "#78909c"
            , suspended = "#ffb74d"
            , door = "#4dd0e1"
            , tooltipBg = "#263238"
            , tooltipText = "#eceff1"
            , series = seriesDark
            }

        Light ->
            { text = "#212121"
            , subtext = "#546e7a"
            , axis = "#b0bec5"
            , split = "#eceff1"
            , moving = "#1976d2"
            , idle = "#90a4ae"
            , suspended = "#fb8c00"
            , door = "#00acc1"
            , tooltipBg = "#37474f"
            , tooltipText = "#ffffff"
            , series = seriesLight
            }


{-| Material categorical hues — enough distinct colours for the fleet (up to ~10 elevators). -}
seriesLight : List String
seriesLight =
    [ "#1976d2", "#e53935", "#43a047", "#fb8c00", "#8e24aa", "#00acc1", "#c0ca33", "#6d4c41", "#3949ab", "#00897b" ]


seriesDark : List String
seriesDark =
    [ "#64b5f6", "#ef5350", "#81c784", "#ffb74d", "#ba68c8", "#4dd0e1", "#dce775", "#a1887f", "#7986cb", "#4db6ac" ]


{-| The BUILDING grid. Floors run from `max(maxFloor, highest car)` down to `min(0, lowest car)`;
one column per elevator (natural-sorted). Colours are semantic CSS classes so both themes work.
-}
view : List Row -> Dict String Bool -> Int -> Theme -> Html msg
view rows doors maxFloor _ =
    let
        sorted =
            List.sortBy (.state >> .name >> naturalKey) rows

        floors =
            List.map (.state >> .floor) sorted

        top =
            max maxFloor (List.maximum floors |> Maybe.withDefault 0)

        bottom =
            min 0 (List.minimum floors |> Maybe.withDefault 0)

        floorRows =
            List.range bottom top
                |> List.reverse
                |> List.map (floorRow sorted doors)
    in
    div [ class "building-wrap" ]
        [ table [ class "building" ]
            [ thead []
                [ tr [] (th [ class "fl" ] [ text "Fl" ] :: List.map headCell sorted) ]
            , tbody [] floorRows
            ]
        , legend
        ]


headCell : Row -> Html msg
headCell row =
    th [ class "car" ] [ text row.state.name ]


floorRow : List Row -> Dict String Bool -> Int -> Html msg
floorRow sorted doors floor =
    tr []
        (td [ class "fl" ] [ text (String.fromInt floor) ]
            :: List.map (carCell doors floor) sorted
        )


carCell : Dict String Bool -> Int -> Row -> Html msg
carCell doors floor row =
    if row.state.floor == floor then
        let
            doorOpen =
                Dict.get row.state.name doors |> Maybe.withDefault False

            glyph =
                carGlyph row.state.suspended doorOpen row.state.motion row.state.direction

            cls =
                carStyle row.state.suspended doorOpen row.state.motion row.state.direction
        in
        td [ class ("cell " ++ cls) ] [ text glyph ]

    else
        td [ class "cell empty" ] [ text "·" ]


{-| The `[c1c2]` glyph for an occupied cell. c1: `S` suspended, else `↑`/`↓` when moving, else space
(stopped). c2: `X` when the door is closed, space when open. -}
carGlyph : Bool -> Bool -> Motion -> Direction -> String
carGlyph suspended doorOpen motion direction =
    let
        c1 =
            if suspended then
                "S"

            else if motion == Moving then
                case direction of
                    Up ->
                        "↑"

                    Down ->
                        "↓"

            else
                " "

        c2 =
            if doorOpen then
                " "

            else
                "X"
    in
    "[" ++ c1 ++ c2 ++ "]"


carStyle : Bool -> Bool -> Motion -> Direction -> String
carStyle suspended doorOpen motion direction =
    if suspended then
        "suspended"

    else if motion == Moving then
        case direction of
            Up ->
                "up"

            Down ->
                "down"

    else if doorOpen then
        "open"

    else
        "idle"


legend : Html msg
legend =
    div [ class "chart-legend" ]
        [ span [ class "key" ] [ span [ class "glyph up" ] [ text "↑" ], text "up" ]
        , span [ class "key" ] [ span [ class "glyph down" ] [ text "↓" ], text "down" ]
        , span [ class "key" ] [ span [ class "glyph suspended" ] [ text "S" ], text "suspended" ]
        , span [ class "key" ] [ text "X closed door" ]
        , span [ class "key" ] [ text "space = open door" ]
        ]


floorAxis : Palette -> Int -> E.Value
floorAxis p maxFloor =
    E.object
        [ ( "type", E.string "value" )
        , ( "name", E.string "floor" )
        , ( "min", E.int 0 )
        , ( "max", E.int maxFloor )
        , ( "interval", E.int 1 )
        , ( "nameTextStyle", E.object [ ( "color", E.string p.subtext ) ] )
        , ( "axisLabel", E.object [ ( "color", E.string p.subtext ) ] )
        , ( "splitLine", E.object [ ( "lineStyle", E.object [ ( "color", E.string p.split ) ] ) ] )
        ]
