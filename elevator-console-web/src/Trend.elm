module Trend exposing (rightAlign, trendOption, view)

{-| The TREND tab: floor over time, one smooth line per elevator. Series are right-aligned
(front-padded with nulls) so every "now" sample sits at the right edge on a shared x. Reuses the
palette + floor axis from Chart.
-}

import Chart exposing (floorAxis, palette)
import Html exposing (Html, div)
import Html.Attributes exposing (class, property)
import Html.Keyed as Keyed
import Json.Encode as E
import Types exposing (Row, Theme)


view : List Row -> Int -> Theme -> Html msg
view rows maxFloor theme =
    div []
        [ Keyed.node "div"
            [ class "chart-area" ]
            [ ( "trend", Html.node "echarts-panel" [ property "option" (trendOption rows maxFloor theme) ] [] ) ]
        ]


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
