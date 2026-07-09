module Main exposing (main)

{-| Elevator web console — a read-only browser monitor for the elevator system. Talks to the
elevator-api only (SSE live stream + REST config/health/version/BI). Three tabs: Chart (cabs at
their floors), Trend (floor over time), Stats (Spark BI outcomes).
-}

import Api
import Browser
import Chart
import Dict exposing (Dict)
import Filter
import History
import Html exposing (Html, button, div, h1, header, i, input, main_, section, span, strong, text)
import Html.Attributes exposing (attribute, class, classList, placeholder, property, title, type_, value)
import Html.Events exposing (onClick, onInput)
import Html.Keyed as Keyed
import Http
import Json.Decode as Decode exposing (Value)
import Ports
import Stats
import Time
import Types
    exposing
        ( BackendVersion(..)
        , Config
        , ElevatorState
        , Health(..)
        , Row
        , Stream(..)
        , Tab(..)
        , Theme(..)
        , naturalKey
        )


main : Program Flags Model Msg
main =
    Browser.element
        { init = init
        , update = update
        , subscriptions = subscriptions
        , view = view
        }



-- MODEL


type alias Flags =
    { version : String
    , dark : Bool
    }


type alias Model =
    { elevators : Dict String ElevatorState
    , histories : Dict String (List Int)
    , stream : Stream
    , config : Config
    , health : Health
    , webVersion : String
    , backendVersion : BackendVersion
    , tab : Tab
    , filter : String
    , theme : Theme
    , stats : Stats.Data
    }


init : Flags -> ( Model, Cmd Msg )
init flags =
    ( { elevators = Dict.empty
      , histories = Dict.empty
      , stream = Connecting
      , config = { maxFloor = 0, biEnabled = True }
      , health = HealthUnknown
      , webVersion = flags.version
      , backendVersion = VersionUnknown
      , tab = ChartTab
      , filter = ""
      , theme =
            if flags.dark then
                Dark

            else
                Light
      , stats = Stats.empty
      }
    , Cmd.batch
        [ Ports.connectStream ()
        , Api.getConfig GotConfig
        , Api.getHealth GotHealth
        , Api.getVersion GotVersion
        ]
    )



-- UPDATE


type Msg
    = StreamFrame Value
    | StreamOpened
    | StreamErrored
    | ThemeChanged Bool
    | GotConfig (Result Http.Error Config)
    | GotHealth (Result Http.Error Health)
    | GotVersion (Result Http.Error String)
    | GotStats (Result Http.Error ( List Types.MileageStat, List Types.OrdersServedStat ))
    | PollHealth Time.Posix
    | PollConfig Time.Posix
    | PollStats Time.Posix
    | SelectTab Tab
    | SetFilter String
    | ClearFilter
    | RefreshHealth
    | RefreshVersion


update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
    case msg of
        StreamFrame value ->
            -- Fold one frame into the live snapshot + rolling history; drop malformed frames.
            case Decode.decodeValue Types.elevatorStateDecoder value of
                Ok state ->
                    ( ingest state model, Cmd.none )

                Err _ ->
                    ( model, Cmd.none )

        StreamOpened ->
            ( { model | stream = Live }, Cmd.none )

        StreamErrored ->
            ( { model | stream = Offline }, Cmd.none )

        ThemeChanged dark ->
            ( { model
                | theme =
                    if dark then
                        Dark

                    else
                        Light
              }
            , Cmd.none
            )

        GotConfig (Ok config) ->
            -- If BI got turned off while the Stats tab is open, fall back to Chart.
            let
                tab =
                    if not config.biEnabled && model.tab == StatsTab then
                        ChartTab

                    else
                        model.tab
            in
            ( { model | config = config, tab = tab }, Cmd.none )

        GotConfig (Err _) ->
            ( model, Cmd.none )

        GotHealth (Ok health) ->
            ( { model | health = health }, Cmd.none )

        GotHealth (Err _) ->
            ( { model | health = HealthDown }, Cmd.none )

        GotVersion (Ok version) ->
            ( { model | backendVersion = Version version }, Cmd.none )

        GotVersion (Err _) ->
            ( { model | backendVersion = Unreachable }, Cmd.none )

        GotStats (Ok ( mileage, served )) ->
            ( { model | stats = { mileage = mileage, served = served, loaded = True, failed = False } }
            , Cmd.none
            )

        GotStats (Err _) ->
            let
                stats =
                    model.stats
            in
            ( { model | stats = { stats | loaded = True, failed = True } }, Cmd.none )

        PollHealth _ ->
            ( model, Api.getHealth GotHealth )

        PollConfig _ ->
            ( model, Api.getConfig GotConfig )

        PollStats _ ->
            ( model, Api.getStats GotStats )

        SelectTab tab ->
            -- Fetch BI immediately on entering Stats; the subscription keeps it fresh after.
            ( { model | tab = tab }
            , if tab == StatsTab then
                Api.getStats GotStats

              else
                Cmd.none
            )

        SetFilter query ->
            ( { model | filter = query }, Cmd.none )

        ClearFilter ->
            ( { model | filter = "" }, Cmd.none )

        RefreshHealth ->
            ( model, Api.getHealth GotHealth )

        RefreshVersion ->
            ( model, Api.getVersion GotVersion )


ingest : ElevatorState -> Model -> Model
ingest state model =
    let
        previous =
            Dict.get state.name model.histories |> Maybe.withDefault []

        series =
            History.push Types.historyLen state.floor previous
    in
    { model
        | elevators = Dict.insert state.name state model.elevators
        , histories = Dict.insert state.name series model.histories
    }



-- SUBSCRIPTIONS


subscriptions : Model -> Sub Msg
subscriptions model =
    Sub.batch
        [ Ports.streamFrame StreamFrame
        , Ports.streamOpened (\_ -> StreamOpened)
        , Ports.streamErrored (\_ -> StreamErrored)
        , Ports.themeChanged ThemeChanged
        , Time.every 15000 PollHealth
        , Time.every 10000 PollConfig
        , if model.config.biEnabled && model.tab == StatsTab then
            Time.every 4000 PollStats

          else
            Sub.none
        ]



-- VIEW


view : Model -> Html Msg
view model =
    div [ class "app" ]
        [ topbar model
        , warnbar model
        , main_ []
            [ tabbar model
            , section [ class "panel" ] [ panel model ]
            ]
        ]


topbar : Model -> Html Msg
topbar model =
    header [ class "topbar" ]
        [ h1 [] [ text "🛗 Elevator web console" ]
        , div [ class "badges" ]
            [ streamBadge model.stream
            , healthBadge model.health
            , versionBadge model
            ]
        ]


streamBadge : Stream -> Html Msg
streamBadge stream =
    let
        live =
            stream == Live
    in
    span [ class "badge", classList [ ( "ok", live ), ( "bad", not live ) ] ]
        [ i [ class "dot" ] []
        , text
            ("stream "
                ++ (if live then
                        "live"

                    else
                        "offline"
                   )
            )
        ]


healthBadge : Health -> Html Msg
healthBadge health =
    let
        up =
            health == HealthUp
    in
    button
        [ type_ "button"
        , class "badge"
        , classList [ ( "ok", up ), ( "bad", not up ) ]
        , onClick RefreshHealth
        , title "click to refresh"
        ]
        [ i [ class "dot" ] [], text ("api " ++ Types.healthLabel health) ]


versionBadge : Model -> Html Msg
versionBadge model =
    button
        [ type_ "button"
        , class "badge"
        , classList [ ( "ok", versionMatch model ), ( "bad", versionMismatch model ) ]
        , onClick RefreshVersion
        , title "web console version vs backend version — click to recheck"
        ]
        [ text
            ("v"
                ++ model.webVersion
                ++ (if versionMatch model then
                        " = "

                    else
                        " ≠ "
                   )
                ++ "api "
                ++ Types.backendLabel model.backendVersion
            )
        ]


warnbar : Model -> Html Msg
warnbar model =
    if versionMismatch model then
        div [ class "warnbar", attribute "role" "alert" ]
            [ text "⚠ Version mismatch — web console "
            , strong [] [ text ("v" ++ model.webVersion) ]
            , text ", backend "
            , strong [] [ text ("v" ++ Types.backendLabel model.backendVersion) ]
            , text ". Run matching builds."
            ]

    else
        text ""


tabbar : Model -> Html Msg
tabbar model =
    div [ class "tabbar" ]
        [ div [ class "tabs", attribute "role" "tablist" ]
            (tabButton model ChartTab "Chart"
                :: tabButton model TrendTab "Trend"
                :: (if model.config.biEnabled then
                        [ tabButton model StatsTab "Stats" ]

                    else
                        []
                   )
            )
        , filterBox model.filter
        ]


tabButton : Model -> Tab -> String -> Html Msg
tabButton model tab label =
    let
        selected =
            model.tab == tab
    in
    button
        [ attribute "role" "tab"
        , attribute "aria-selected"
            (if selected then
                "true"

             else
                "false"
            )
        , classList [ ( "active", selected ) ]
        , onClick (SelectTab tab)
        ]
        [ text label ]


filterBox : String -> Html Msg
filterBox filter =
    div [ class "filterbox" ]
        (input
            [ type_ "text"
            , value filter
            , onInput SetFilter
            , placeholder "filter name — regex, e.g. e[1-3]"
            , attribute "aria-label" "filter elevators by name"
            ]
            []
            :: (if String.isEmpty filter then
                    []

                else
                    [ button [ class "clear", onClick ClearFilter, attribute "aria-label" "clear filter" ] [ text "✕" ] ]
               )
        )


panel : Model -> Html Msg
panel model =
    if model.tab == StatsTab then
        -- Stats owns its own polling + empty/filter states; it does not depend on SSE state.
        Stats.view model.filter model.stats

    else if model.config.maxFloor == 0 then
        emptyMsg "Loading configuration…"

    else if List.isEmpty (rows model) then
        emptyMsg "Waiting for elevator state…"

    else if List.isEmpty (filteredRows model) then
        emptyMsg ("No elevator matches “" ++ model.filter ++ "”.")

    else
        chartView model


chartView : Model -> Html Msg
chartView model =
    let
        option =
            case model.tab of
                TrendTab ->
                    Chart.trendOption (filteredRows model) model.config.maxFloor model.theme

                _ ->
                    Chart.positionOption (filteredRows model) model.config.maxFloor model.theme
    in
    div []
        [ -- Keyed by tab so switching Chart↔Trend remounts a fresh ECharts instance (scatter vs line).
          Keyed.node "div"
            [ class "chart-area" ]
            [ ( Types.tabId model.tab, Html.node "echarts-panel" [ property "option" option ] [] ) ]
        , if model.tab == ChartTab then
            chartLegend

          else
            text ""
        ]


chartLegend : Html Msg
chartLegend =
    div [ class "chart-legend" ]
        [ span [ class "key" ] [ i [ class "swatch moving" ] [], text "moving" ]
        , span [ class "key" ] [ i [ class "swatch idle" ] [], text "idle" ]
        , span [ class "key" ] [ text "▲ up" ]
        , span [ class "key" ] [ text "▼ down" ]
        ]


emptyMsg : String -> Html Msg
emptyMsg message =
    Html.p [ class "empty" ] [ text message ]



-- DERIVED


rows : Model -> List Row
rows model =
    Dict.values model.elevators
        |> List.sortBy (.name >> naturalKey)
        |> List.map
            (\state ->
                { state = state
                , history = Dict.get state.name model.histories |> Maybe.withDefault []
                }
            )


filteredRows : Model -> List Row
filteredRows model =
    List.filter (\row -> Filter.matches model.filter row.state.name) (rows model)


versionMatch : Model -> Bool
versionMatch model =
    case model.backendVersion of
        Version v ->
            v == model.webVersion

        _ ->
            False


versionMismatch : Model -> Bool
versionMismatch model =
    case model.backendVersion of
        Version v ->
            v /= model.webVersion

        _ ->
            False
