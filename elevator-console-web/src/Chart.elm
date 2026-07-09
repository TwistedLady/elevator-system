module Chart exposing (positionOption, rightAlign, trendOption)

{-| Pure ECharts option builders for the two chart tabs, emitted as JSON for the <echarts-panel>
custom element. Elm owns the data shaping; the element owns the canvas. The `__kind`/`__replace`
hints are read by main.js (tooltip formatter, series replace) and ignored by ECharts.
-}

import Json.Encode as E
import Types exposing (Direction(..), Motion(..), Row, Theme(..), directionLabel, motionLabel)


type alias Palette =
    { text : String
    , subtext : String
    , axis : String
    , split : String
    , moving : String
    , idle : String
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


{-| CHART tab — each elevator is a cab (rounded rect) parked at its floor; the cab glides when its
floor changes (ECharts animates the position update). Colour = moving/idle.
-}
positionOption : List Row -> Int -> Theme -> E.Value
positionOption rows maxFloor theme =
    let
        p =
            palette theme

        names =
            List.map (.state >> .name) rows

        toDatum index row =
            let
                moving =
                    row.state.motion == Moving

                itemStyle =
                    if moving then
                        -- Solid + glow when moving.
                        E.object
                            [ ( "color", E.string p.moving )
                            , ( "borderRadius", E.int 5 )
                            , ( "borderWidth", E.int 0 )
                            , ( "shadowBlur", E.int 12 )
                            , ( "shadowColor", E.string p.moving )
                            ]

                    else
                        -- Hollow outline when idle — so state reads at a glance.
                        E.object
                            [ ( "color", E.string "transparent" )
                            , ( "borderRadius", E.int 5 )
                            , ( "borderColor", E.string p.idle )
                            , ( "borderWidth", E.int 2 )
                            , ( "shadowBlur", E.int 0 )
                            ]

                labelText =
                    if moving then
                        chevron row.state.direction ++ " " ++ String.fromInt row.state.floor

                    else
                        String.fromInt row.state.floor

                tip =
                    "<b>"
                        ++ row.state.name
                        ++ "</b><br/>floor "
                        ++ String.fromInt row.state.floor
                        ++ "<br/>"
                        ++ directionLabel row.state.direction
                        ++ " · "
                        ++ motionLabel row.state.motion
            in
            E.object
                [ ( "value", E.list E.int [ index, row.state.floor ] )
                , ( "itemStyle", itemStyle )
                , ( "label"
                  , E.object
                        [ ( "formatter", E.string labelText )
                        , ( "color"
                          , E.string
                                (if moving then
                                    "#ffffff"

                                 else
                                    p.subtext
                                )
                          )
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


{-| TREND tab — floor over time, one smooth line per elevator. Series are right-aligned
(front-padded with nulls) so every "now" sample sits at the right edge on a shared x.
-}
trendOption : List Row -> Int -> Theme -> E.Value
trendOption rows maxFloor theme =
    let
        p =
            palette theme

        names =
            List.map (.state >> .name) rows

        categories =
            List.range 0 (Types.historyLen - 1)
                |> List.map (\i -> String.fromInt (i - Types.historyLen + 1))

        toSeries row =
            E.object
                [ ( "id", E.string row.state.name )
                , ( "name", E.string row.state.name )
                , ( "type", E.string "line" )
                , ( "smooth", E.bool True )
                , ( "showSymbol", E.bool False )
                , ( "connectNulls", E.bool False )
                , ( "lineStyle", E.object [ ( "width", E.float 2.5 ) ] )
                , ( "emphasis", E.object [ ( "focus", E.string "series" ) ] )
                , ( "endLabel", E.object [ ( "show", E.bool True ), ( "formatter", E.string "{a}" ), ( "fontSize", E.int 10 ) ] )
                , ( "data", encodeSamples (rightAlign Types.historyLen row.history) )
                ]
    in
    E.object
        [ ( "__kind", E.string "trend" )
        , ( "__replace", E.bool True )
        , ( "animationDurationUpdate", E.int 150 )
        , ( "animationEasingUpdate", E.string "linear" )
        , ( "color", E.list E.string p.series )
        , ( "grid", E.object [ ( "left", E.int 46 ), ( "right", E.int 72 ), ( "top", E.int 30 ), ( "bottom", E.int 24 ) ] )
        , ( "tooltip"
          , E.object
                [ ( "trigger", E.string "axis" )
                , ( "backgroundColor", E.string p.tooltipBg )
                , ( "borderWidth", E.int 0 )
                , ( "textStyle", E.object [ ( "color", E.string p.tooltipText ) ] )
                ]
          )
        , ( "legend"
          , E.object
                [ ( "type", E.string "scroll" )
                , ( "data", E.list E.string names )
                , ( "top", E.int 0 )
                , ( "textStyle", E.object [ ( "color", E.string p.subtext ) ] )
                ]
          )
        , ( "xAxis"
          , E.object
                [ ( "type", E.string "category" )
                , ( "data", E.list E.string categories )
                , ( "boundaryGap", E.bool False )
                , ( "axisTick", E.object [ ( "show", E.bool False ) ] )
                , ( "axisLine", E.object [ ( "lineStyle", E.object [ ( "color", E.string p.axis ) ] ) ] )
                , ( "axisLabel", E.object [ ( "show", E.bool False ) ] )
                ]
          )
        , ( "yAxis", floorAxis p maxFloor )
        , ( "series", E.list identity (List.map toSeries rows) )
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


{-| Right-align a history to `len` points, keeping the newest and front-padding with `Nothing`
(gaps ECharts skips). Longer histories are trimmed to the last `len` samples.
-}
rightAlign : Int -> List Int -> List (Maybe Int)
rightAlign len history =
    let
        tail =
            List.drop (max 0 (List.length history - len)) history

        pad =
            List.repeat (max 0 (len - List.length tail)) Nothing
    in
    pad ++ List.map Just tail


encodeSamples : List (Maybe Int) -> E.Value
encodeSamples =
    E.list (Maybe.map E.int >> Maybe.withDefault E.null)
