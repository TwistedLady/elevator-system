module Sim exposing (Model, Msg, init, subscriptions, update, view)

{-| The SIM tab: one button runs a 10,000-call simulation, then this tab polls its status once a
second and draws a progress bar split by status (done / progress / pending). Self-contained — its
own Model, Msg, update, view, subscriptions; talks to the api via Api (Http, not ports).
-}

import Api
import Html exposing (Html, button, div, p, span, text)
import Html.Attributes exposing (class, style)
import Html.Events exposing (onClick)
import Http
import Time
import Types exposing (SimStatus, SimulateResult)


type alias Model =
    { runId : Maybe String
    , ids : List String
    , status : Maybe SimStatus
    , active : Bool
    , failed : Bool
    }


init : Model
init =
    { runId = Nothing, ids = [], status = Nothing, active = False, failed = False }


type Msg
    = RunClicked
    | Started (Result Http.Error SimulateResult)
    | Tick Time.Posix
    | Polled (Result Http.Error SimStatus)


update : Msg -> Model -> ( Model, Cmd Msg )
update msg model =
    case msg of
        RunClicked ->
            ( { init | active = True }, Api.simulate Started )

        Started (Ok result) ->
            ( { model | runId = Just result.runId, ids = result.ids, status = Nothing, active = True, failed = False }
            , Api.simulateStatus result.ids Polled
            )

        Started (Err _) ->
            ( { model | active = False, failed = True }, Cmd.none )

        Tick _ ->
            if List.isEmpty model.ids then
                ( model, Cmd.none )

            else
                ( model, Api.simulateStatus model.ids Polled )

        Polled (Ok status) ->
            ( { model | status = Just status, active = not (complete status) }, Cmd.none )

        Polled (Err _) ->
            ( model, Cmd.none )


complete : SimStatus -> Bool
complete status =
    status.progress == 0 && status.pending == 0


subscriptions : Model -> Sub Msg
subscriptions model =
    if model.active then
        Time.every 1000 Tick

    else
        Sub.none



-- VIEW


view : Model -> Html Msg
view model =
    div [ class "sim" ]
        [ button [ class "sim-run", onClick RunClicked ] [ text "Run 10k simulation" ]
        , body model
        ]


body : Model -> Html Msg
body model =
    case model.status of
        Just status ->
            div [ class "sim-status" ]
                [ progressBar status
                , p [ class "sim-line" ] [ text (statusLine model.runId status) ]
                , if complete status then
                    p [ class "sim-done" ] [ text "✓ simulation complete" ]

                  else
                    text ""
                ]

        Nothing ->
            if model.failed then
                p [ class "sim-line bad" ] [ text "simulation could not be started" ]

            else if model.active then
                p [ class "sim-line muted" ] [ text "starting…" ]

            else
                p [ class "sim-line muted" ] [ text "Press the button to run a 10,000-call simulation." ]


progressBar : SimStatus -> Html msg
progressBar status =
    let
        width n =
            style "width" (String.fromFloat (toFloat n / toFloat (max 1 status.total) * 100) ++ "%")
    in
    div [ class "sim-bar" ]
        [ span [ class "seg done", width status.done ] []
        , span [ class "seg progress", width status.progress ] []
        , span [ class "seg pending", width status.pending ] []
        ]


statusLine : Maybe String -> SimStatus -> String
statusLine runId status =
    "run "
        ++ Maybe.withDefault "—" runId
        ++ " · done "
        ++ String.fromInt status.done
        ++ " · progress "
        ++ String.fromInt status.progress
        ++ " · pending "
        ++ String.fromInt status.pending
