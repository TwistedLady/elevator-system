module Stats exposing (Data, StatRow, empty, merge, view)

{-| STATS tab — Spark BI outcomes per elevator, polled from the api:
mileage (floors travelled) + orders served (reached ordered floors), merged into one row per
elevator and drawn as a small horizontal bar chart.
-}

import Dict exposing (Dict)
import Filter
import Html exposing (Html, div, p, small, span, text)
import Html.Attributes exposing (attribute, class, style, title)
import Types exposing (MileageStat, OrdersServedStat, naturalKey)


{-| The polled state the Stats tab renders. `loaded` distinguishes "empty" from "still loading";
`failed` flags that the last poll errored (last-known values are kept). -}
type alias Data =
    { mileage : List MileageStat
    , served : List OrdersServedStat
    , loaded : Bool
    , failed : Bool
    }


type alias StatRow =
    { name : String
    , mileage : Int
    , served : Int
    }


empty : Data
empty =
    { mileage = [], served = [], loaded = False, failed = False }


{-| One row per elevator, mileage + served merged, sorted by natural name order (e1, e2, … e10). -}
merge : Data -> List StatRow
merge data =
    let
        withMileage =
            List.foldl
                (\m acc -> upsert m.name (\r -> { r | mileage = m.floorsTravelled }) acc)
                Dict.empty
                data.mileage

        merged =
            List.foldl
                (\s acc -> upsert s.name (\r -> { r | served = s.ordersServed }) acc)
                withMileage
                data.served
    in
    Dict.values merged
        |> List.sortBy (.name >> naturalKey)


upsert : String -> (StatRow -> StatRow) -> Dict String StatRow -> Dict String StatRow
upsert name update dict =
    let
        current =
            Dict.get name dict |> Maybe.withDefault { name = name, mileage = 0, served = 0 }
    in
    Dict.insert name (update current) dict


view : String -> Data -> Html msg
view filter data =
    let
        rows =
            merge data

        visible =
            List.filter (\r -> Filter.matches filter r.name) rows
    in
    div [ class "stats" ]
        [ legend data.failed
        , body filter rows visible
        ]


legend : Bool -> Html msg
legend failed =
    div [ class "stats-legend" ]
        (span [ class "key" ] [ span [ class "swatch mileage" ] [], text "mileage — floors travelled" ]
            :: span [ class "key" ] [ span [ class "swatch served" ] [], text "orders served" ]
            :: (if failed then
                    [ span [ class "key stale", title "last poll failed — showing last known values" ] [ text "stale" ] ]

                else
                    []
               )
        )


body : String -> List StatRow -> List StatRow -> Html msg
body filter rows visible =
    if List.isEmpty rows then
        -- Not loaded yet vs. loaded-but-empty: caller only renders once biEnabled, so treat an
        -- empty merged set as "no outcomes yet".
        p [ class "muted" ] [ text "No Spark BI outcomes yet — run the elevator-bi jobs." ]

    else if List.isEmpty visible then
        p [ class "muted" ] [ text ("No elevator matches “" ++ filter ++ "”.") ]

    else
        let
            maxMileage =
                List.map .mileage visible |> List.maximum |> Maybe.withDefault 1 |> max 1

            maxServed =
                List.map .served visible |> List.maximum |> Maybe.withDefault 1 |> max 1
        in
        div [ class "scroll" ]
            [ div [ class "grid", attribute "role" "table", attribute "aria-label" "Spark BI outcomes per elevator" ]
                (headRow :: List.map (dataRow maxMileage maxServed) visible)
            ]


headRow : Html msg
headRow =
    div [ class "row head", attribute "role" "row" ]
        [ span [ class "name", attribute "role" "columnheader" ] [ text "Elevator" ]
        , span [ class "col", attribute "role" "columnheader" ]
            [ text "Mileage ", small [] [ text "(floors)" ] ]
        , span [ class "col", attribute "role" "columnheader" ] [ text "Orders served" ]
        ]


dataRow : Int -> Int -> StatRow -> Html msg
dataRow maxMileage maxServed row =
    div [ class "row", attribute "role" "row" ]
        [ span [ class "name", attribute "role" "cell" ] [ text row.name ]
        , metricCell "mileage" row.mileage maxMileage
        , metricCell "served" row.served maxServed
        ]


metricCell : String -> Int -> Int -> Html msg
metricCell kind value maxValue =
    span [ class "col", attribute "role" "cell" ]
        [ span [ class "bartrack" ]
            [ span
                [ class ("bar " ++ kind)
                , style "width" (String.fromFloat (pct value maxValue) ++ "%")
                ]
                []
            ]
        , span [ class "val" ] [ text (String.fromInt value) ]
        ]


pct : Int -> Int -> Float
pct value maxValue =
    if maxValue > 0 then
        toFloat value / toFloat maxValue * 100

    else
        0
