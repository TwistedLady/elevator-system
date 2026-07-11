module Chart exposing (Palette, floorAxis, palette, positionOption, view)

{-| The CHART tab: each elevator is a cab parked at its floor (ECharts scatter). Also home to the
shared palette + floor axis reused by the Trend tab. The `__kind` hint is read by main.js (tooltip
formatter) and ignored by ECharts.
-}

import Dict exposing (Dict)
import Html exposing (Html, div, i, span, text)
import Html.Attributes exposing (class, property)
import Html.Keyed as Keyed
import Json.Encode as E
import Types exposing (Direction(..), Motion(..), Row, Theme(..), directionLabel, motionLabel)


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


chevron : Direction -> String
chevron direction =
    case direction of
        Up ->
            "▲"

        Down ->
            "▼"


view : List Row -> Dict String Bool -> Int -> Theme -> Html msg
view rows doors maxFloor theme =
    div []
        [ Keyed.node "div"
            [ class "chart-area" ]
            [ ( "chart", Html.node "echarts-panel" [ property "option" (positionOption rows doors maxFloor theme) ] [] ) ]
        , legend
        ]


legend : Html msg
legend =
    div [ class "chart-legend" ]
        [ span [ class "key" ] [ i [ class "swatch moving" ] [], text "moving" ]
        , span [ class "key" ] [ i [ class "swatch idle" ] [], text "idle" ]
        , span [ class "key" ] [ i [ class "swatch suspended" ] [], text "suspended" ]
        , span [ class "key" ] [ i [ class "swatch door" ] [], text "door open" ]
        , span [ class "key" ] [ text "▲ up" ]
        , span [ class "key" ] [ text "▼ down" ]
        ]


{-| Each elevator is a cab (rounded rect) parked at its floor; the cab glides when its floor changes
(ECharts animates the position update). Colour = moving/idle.
-}
positionOption : List Row -> Dict String Bool -> Int -> Theme -> E.Value
positionOption rows doors maxFloor theme =
    let
        p =
            palette theme

        names =
            List.map (.state >> .name) rows

        toDatum index row =
            let
                moving =
                    row.state.motion == Moving

                suspended =
                    row.state.suspended

                doorOpen =
                    Dict.get row.state.name doors |> Maybe.withDefault False

                fill =
                    if suspended then
                        p.suspended

                    else if moving then
                        p.moving

                    else
                        "transparent"

                borderColor =
                    if doorOpen then
                        p.door

                    else if suspended then
                        p.suspended

                    else
                        p.idle

                borderWidth =
                    if doorOpen then
                        3

                    else if moving || suspended then
                        0

                    else
                        2

                shadowBlur =
                    if moving || suspended then
                        12

                    else
                        0

                shadowColor =
                    if suspended then
                        p.suspended

                    else
                        p.moving

                itemStyle =
                    E.object
                        [ ( "color", E.string fill )
                        , ( "borderRadius", E.int 5 )
                        , ( "borderColor", E.string borderColor )
                        , ( "borderWidth", E.int borderWidth )
                        , ( "shadowBlur", E.int shadowBlur )
                        , ( "shadowColor", E.string shadowColor )
                        ]

                labelText =
                    if suspended then
                        "S " ++ String.fromInt row.state.floor

                    else if moving then
                        chevron row.state.direction ++ " " ++ String.fromInt row.state.floor

                    else
                        String.fromInt row.state.floor

                labelColor =
                    if moving || suspended then
                        "#ffffff"

                    else
                        p.subtext

                tip =
                    "<b>"
                        ++ row.state.name
                        ++ "</b><br/>floor "
                        ++ String.fromInt row.state.floor
                        ++ "<br/>"
                        ++ directionLabel row.state.direction
                        ++ " · "
                        ++ motionLabel row.state.motion
                        ++ (if suspended then
                                " · suspended"

                            else
                                ""
                           )
                        ++ (if doorOpen then
                                " · door open"

                            else
                                ""
                           )
            in
            E.object
                [ ( "value", E.list E.int [ index, row.state.floor ] )
                , ( "itemStyle", itemStyle )
                , ( "label"
                  , E.object
                        [ ( "formatter", E.string labelText )
                        , ( "color", E.string labelColor )
                        ]
                  )
                , ( "tip", E.string tip )
                ]

        data =
            List.indexedMap toDatum rows
    in
    E.object
        [ ( "__kind", E.string "position" )
        , ( "animationDurationUpdate", E.int 100 )
        , ( "animationEasingUpdate", E.string "linear" )
        , ( "grid", E.object [ ( "left", E.int 46 ), ( "right", E.int 24 ), ( "top", E.int 24 ), ( "bottom", E.int 40 ) ] )
        , ( "tooltip"
          , E.object
                [ ( "trigger", E.string "item" )
                , ( "backgroundColor", E.string p.tooltipBg )
                , ( "borderWidth", E.int 0 )
                , ( "textStyle", E.object [ ( "color", E.string p.tooltipText ) ] )
                ]
          )
        , ( "xAxis"
          , E.object
                [ ( "type", E.string "category" )
                , ( "data", E.list E.string names )
                , ( "axisTick", E.object [ ( "show", E.bool False ) ] )
                , ( "axisLine", E.object [ ( "lineStyle", E.object [ ( "color", E.string p.axis ) ] ) ] )
                , ( "axisLabel", E.object [ ( "color", E.string p.text ), ( "fontWeight", E.string "bold" ) ] )
                ]
          )
        , ( "yAxis", floorAxis p maxFloor )
        , ( "series"
          , E.list identity
                [ E.object
                    [ ( "id", E.string "cabs" )
                    , ( "type", E.string "scatter" )
                    , ( "symbol", E.string "roundRect" )
                    , ( "symbolSize", E.list E.int [ 46, 24 ] )
                    , ( "data", E.list identity data )
                    , ( "label"
                      , E.object
                            [ ( "show", E.bool True )
                            , ( "color", E.string "#ffffff" )
                            , ( "fontSize", E.int 11 )
                            , ( "fontWeight", E.string "bold" )
                            ]
                      )
                    , ( "emphasis", E.object [ ( "scale", E.float 1.15 ) ] )
                    , ( "z", E.int 3 )
                    ]
                ]
          )
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
