module Sim exposing (Model, Msg, init, subscriptions, update, view)

{-| The SIM tab: one button runs a simulation (server-side, via POST /api/simulate),
then this tab polls GET /api/simulate/progress by run id every couple of seconds and draws the
rolled-up summary — size, calls, orders, done, first-call / last-done — with a progress bar split
by status. Self-contained — own Model/Msg/update/view/subscriptions; HTTP via Api (elm/http).
-}

import Api
import Html exposing (Html, button, div, p, span, text)
import Html.Attributes exposing (class, style)
import Html.Events exposing (onClick)
import Http
import Time
import Types exposing (SimProgress, SimulateResult)


type alias Model =
    { runId : Maybe String
    , size : Int
    , calls : Int
    , orders : Int
    , doneCalls : Int
    , firstCall : Maybe String
    , lastDone : Maybe String
    , active : Bool
    , started : Bool
    , failed : Bool
    }


init : Model
init =
    { runId = Nothing
    , size = 0
    , calls = 0
    , orders = 0
    , doneCalls = 0
    , firstCall = Nothing
    , lastDone = Nothing
    , active = False
    , started = False
    , failed = False
    }


type Msg
    = RunClicked
    | Started (Result Http.Error SimulateResult)
    | Polled (Result Http.Error SimProgress)
    | Tick Time.Posix


update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
    case msg of
        RunClicked ->
            ( { init | active = True, started = True }, Api.simulate Started )

        Started (Ok result) ->
            ( { init | runId = Just result.runId, size = result.count, active = True, started = True }
            , Api.progress result.runId result.count Polled
            )

        Started (Err _) ->
            ( { model | active = False, failed = True }, Cmd.none )

        Polled (Ok p) ->
            ( { model
                | calls = p.calls
                , orders = p.orders
                , doneCalls = p.doneCalls
                , firstCall = p.firstCall
                , lastDone = p.lastDone
                , active = not (allDone model.size p.doneCalls)
              }
            , Cmd.none
            )

        Polled (Err _) ->
            ( model, Cmd.none )

        Tick _ ->
            case ( model.active, model.runId ) of
                ( True, Just runId ) ->
                    ( model, Api.progress runId model.size Polled )

                _ ->
                    ( model, Cmd.none )


allDone : Int -> Int -> Bool
allDone size doneCalls =
    size > 0 && doneCalls >= size


complete : Model -> Bool
complete model =
    model.started && not model.failed && allDone model.size model.doneCalls


subscriptions : Model -> Sub Msg
subscriptions model =
    if model.active then
        Time.every 2000 Tick

    else
        Sub.none



-- VIEW


view : Model -> Html Msg
view model =
    div [ class "sim" ]
        [ button [ class "sim-run", onClick RunClicked ] [ text "Run simulation" ]
        , body model
        ]


body : Model -> Html Msg
body model =
    if model.failed then
        p [ class "sim-line bad" ] [ text "simulation could not be started" ]

    else if not model.started then
        p [ class "sim-line muted" ] [ text "Press the button to run a simulation." ]

    else if model.size == 0 then
        p [ class "sim-line muted" ] [ text "starting…" ]

    else
        let
            done =
                model.doneCalls

            progress =
                max 0 (model.calls - model.doneCalls)

            pending =
                max 0 (model.size - model.calls)
        in
        div [ class "sim-status" ]
            [ progressBar model.size done progress pending
            , p [ class "sim-line" ] [ text (countsLine model.runId model.size model.calls done progress pending) ]
            , p [ class "sim-meta" ] [ text (metaLine model.orders model.firstCall model.lastDone) ]
            , if complete model then
                p [ class "sim-done" ] [ text "✓ simulation complete" ]

              else
                text ""
            ]


progressBar : Int -> Int -> Int -> Int -> Html msg
progressBar size done progress pending =
    let
        width n =
            style "width" (String.fromFloat (toFloat n / toFloat (max 1 size) * 100) ++ "%")
    in
    div [ class "sim-bar" ]
        [ span [ class "seg done", width done ] []
        , span [ class "seg progress", width progress ] []
        , span [ class "seg pending", width pending ] []
        ]


countsLine : Maybe String -> Int -> Int -> Int -> Int -> Int -> String
countsLine runId size calls done progress pending =
    "run "
        ++ Maybe.withDefault "—" runId
        ++ " · size "
        ++ String.fromInt size
        ++ " · calls "
        ++ String.fromInt calls
        ++ " · done "
        ++ String.fromInt done
        ++ " · progress "
        ++ String.fromInt progress
        ++ " · pending "
        ++ String.fromInt pending


metaLine : Int -> Maybe String -> Maybe String -> String
metaLine orders firstCall lastDone =
    "orders "
        ++ String.fromInt orders
        ++ " · first "
        ++ hms firstCall
        ++ " · last "
        ++ hms lastDone


hms : Maybe String -> String
hms iso =
    case iso |> Maybe.andThen (String.split "T" >> List.drop 1 >> List.head) of
        Just t ->
            String.left 8 t

        Nothing ->
            "—"
