module Main exposing (main)

{-| Read-only web console over elevator-api (SSE stream + REST config/health/version/simulate).
A thin orchestrator: owns the model, tabs and subscriptions; delegates each tab (Chart / Trend /
Sim) and the header to its own module.
-}

import Api
import Browser
import Chart
import Dict exposing (Dict)
import Filter
import Header
import History
import Html exposing (Html, button, div, input, main_, p, section, text)
import Html.Attributes exposing (attribute, class, classList, placeholder, type_, value)
import Html.Events exposing (onClick, onInput)
import Http
import Json.Decode as Decode exposing (Value)
import Log
import Ports
import Sim
import Time
import Trend
import Types
    exposing
        ( BackendVersion(..)
        , Config
        , ElevatorState
        , Health(..)
        , Row
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


type alias Flags =
    { version : String
    , dark : Bool
    }


type alias Model =
    { elevators : Dict String ElevatorState
    , histories : Dict String (List Int)
    , config : Config
    , health : Health
    , webVersion : String
    , backendVersion : BackendVersion
    , tab : Tab
    , filter : String
    , theme : Theme
    , sim : Sim.Model
    }


init : Flags -> ( Model, Cmd Msg )
init flags =
    ( { elevators = Dict.empty
      , histories = Dict.empty
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
      , sim = Sim.init
      }
    , Cmd.batch
        [ Ports.connectStream ()
        , Api.getConfig GotConfig
        , Api.getHealth GotHealth
        , Api.getVersion GotVersion
        , Log.info "connecting to SSE stream /api/elevator/stream"
        ]
    )


type Msg
    = StreamFrame Value
    | StreamOpened
    | StreamErrored
    | ThemeChanged Bool
    | GotConfig (Result Http.Error Config)
    | GotHealth (Result Http.Error Health)
    | GotVersion (Result Http.Error String)
    | PollHealth Time.Posix
    | PollConfig Time.Posix
    | SelectTab Tab
    | SetFilter String
    | ClearFilter
    | RefreshHealth
    | RefreshVersion
    | SimMsg Sim.Msg


update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
    case msg of
        StreamFrame value ->
            case Decode.decodeValue Types.elevatorStateDecoder value of
                Ok state ->
                    ( ingest state model, Cmd.none )

                Err error ->
                    ( model, Log.warn ("dropped an unparseable SSE frame: " ++ Decode.errorToString error) )

        StreamOpened ->
            ( model, Log.info "SSE stream open" )

        StreamErrored ->
            ( model, Log.warn "SSE stream dropped — awaiting auto-reconnect" )

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
            ( { model | config = config }, Cmd.none )

        GotConfig (Err _) ->
            ( model, Log.debug "config fetch failed — keeping last known limits" )

        GotHealth (Ok health) ->
            ( { model | health = health }, Cmd.none )

        GotHealth (Err _) ->
            ( { model | health = HealthDown }, Log.warn "health check failed" )

        GotVersion (Ok version) ->
            ( { model | backendVersion = Version version }, Cmd.none )

        GotVersion (Err _) ->
            ( { model | backendVersion = Unreachable }, Log.warn "backend version unreachable" )

        PollHealth _ ->
            ( model, Api.getHealth GotHealth )

        PollConfig _ ->
            ( model, Api.getConfig GotConfig )

        SelectTab tab ->
            ( { model | tab = tab }, Cmd.none )

        SetFilter query ->
            ( { model | filter = query }, Cmd.none )

        ClearFilter ->
            ( { model | filter = "" }, Cmd.none )

        RefreshHealth ->
            ( model, Api.getHealth GotHealth )

        RefreshVersion ->
            ( model, Api.getVersion GotVersion )

        SimMsg subMsg ->
            let
                ( sim, cmd ) =
                    Sim.update subMsg model.sim
            in
            ( { model | sim = sim }, Cmd.map SimMsg cmd )


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


subscriptions : Model -> Sub Msg
subscriptions model =
    Sub.batch
        [ Ports.streamFrame StreamFrame
        , Ports.streamOpened (\_ -> StreamOpened)
        , Ports.streamErrored (\_ -> StreamErrored)
        , Ports.themeChanged ThemeChanged
        , Time.every 15000 PollHealth
        , Time.every 10000 PollConfig
        , Sub.map SimMsg (Sim.subscriptions model.sim)
        ]


view : Model -> Html Msg
view model =
    div [ class "app" ]
        [ Header.view
            { webVersion = model.webVersion
            , backendVersion = model.backendVersion
            , health = model.health
            , refreshHealth = RefreshHealth
            , refreshVersion = RefreshVersion
            }
        , Header.warnbar model.webVersion model.backendVersion
        , main_ []
            [ tabbar model
            , section [ class "panel" ] [ panel model ]
            ]
        ]


tabbar : Model -> Html Msg
tabbar model =
    div [ class "tabbar" ]
        [ div [ class "tabs", attribute "role" "tablist" ]
            [ tabButton model ChartTab "CHART"
            , tabButton model TrendTab "TREND"
            , tabButton model SimTab "SIM"
            ]
        , if model.tab == SimTab then
            text ""

          else
            filterBox model.filter
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
    case model.tab of
        SimTab ->
            Html.map SimMsg (Sim.view model.sim)

        ChartTab ->
            chartPanel model Chart.view

        TrendTab ->
            chartPanel model Trend.view


chartPanel : Model -> (List Row -> Int -> Theme -> Html Msg) -> Html Msg
chartPanel model render =
    if model.config.maxFloor == 0 then
        emptyMsg "Loading configuration…"

    else if List.isEmpty (rows model) then
        emptyMsg "Waiting for elevator state…"

    else if List.isEmpty (filteredRows model) then
        emptyMsg ("No elevator matches “" ++ model.filter ++ "”.")

    else
        render (filteredRows model) model.config.maxFloor model.theme


emptyMsg : String -> Html Msg
emptyMsg message =
    p [ class "empty" ] [ text message ]


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
