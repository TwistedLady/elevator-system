module Types exposing
    ( BackendVersion(..)
    , Config
    , Direction(..)
    , ElevatorState
    , Health(..)
    , Motion(..)
    , Row
    , SimProgress
    , SimulateResult
    , Tab(..)
    , Theme(..)
    , backendLabel
    , configDecoder
    , directionLabel
    , elevatorStateDecoder
    , healthDecoder
    , healthLabel
    , historyLen
    , motionLabel
    , naturalKey
    , simProgressDecoder
    , simulateResultDecoder
    , versionDecoder
    )

{-| Console domain types + JSON decoders. Mirrors the elevator-api elevator-state contract
(elevator-common-dto/Dtos.scala, GET /api/elevator/stream); the console is read-only.
-}

import Json.Decode as Decode exposing (Decoder)


type alias ElevatorState =
    { tag : String
    , name : String
    , direction : Direction
    , motion : Motion
    , floor : Int
    }


type Direction
    = Up
    | Down


type Motion
    = Moving
    | Stopped


type alias Row =
    { state : ElevatorState
    , history : List Int
    }


type Health
    = HealthUnknown
    | HealthUp
    | HealthDown


type BackendVersion
    = VersionUnknown
    | Unreachable
    | Version String


type Tab
    = ChartTab
    | TrendTab
    | SimTab


type Theme
    = Light
    | Dark


type alias Config =
    { maxFloor : Int
    , biEnabled : Bool
    }


type alias SimulateResult =
    { runId : String
    , count : Int
    , ids : List String
    }


type alias SimProgress =
    { calls : Int
    , orders : Int
    , doneCalls : Int
    , firstCall : Maybe String
    , lastDone : Maybe String
    }


historyLen : Int
historyLen =
    48


directionLabel : Direction -> String
directionLabel direction =
    case direction of
        Up ->
            "Up"

        Down ->
            "Down"


motionLabel : Motion -> String
motionLabel motion =
    case motion of
        Moving ->
            "Moving"

        Stopped ->
            "Stopped"


healthLabel : Health -> String
healthLabel health =
    case health of
        HealthUnknown ->
            "…"

        HealthUp ->
            "UP"

        HealthDown ->
            "DOWN"


backendLabel : BackendVersion -> String
backendLabel version =
    case version of
        VersionUnknown ->
            "unknown"

        Unreachable ->
            "unreachable"

        Version v ->
            v


naturalKey : String -> ( String, Int )
naturalKey name =
    ( String.filter (not << Char.isDigit) name
    , String.toInt (String.filter Char.isDigit name) |> Maybe.withDefault 0
    )


directionDecoder : Decoder Direction
directionDecoder =
    Decode.string
        |> Decode.map
            (\s ->
                if String.toUpper s == "DOWN" then
                    Down

                else
                    Up
            )


motionDecoder : Decoder Motion
motionDecoder =
    Decode.string
        |> Decode.map
            (\s ->
                if String.toUpper s == "MOVING" then
                    Moving

                else
                    Stopped
            )


elevatorStateDecoder : Decoder ElevatorState
elevatorStateDecoder =
    Decode.map5 ElevatorState
        (Decode.oneOf [ Decode.field "tag" Decode.string, Decode.succeed "" ])
        (Decode.field "elevatorName" Decode.string)
        (Decode.field "direction" directionDecoder)
        (Decode.field "motion" motionDecoder)
        (Decode.field "floor" Decode.int)


configDecoder : Decoder Config
configDecoder =
    Decode.map2 Config
        (Decode.field "maxFloor" Decode.int)
        (Decode.field "biEnabled" Decode.bool)


healthDecoder : Decoder Health
healthDecoder =
    Decode.field "status" Decode.string
        |> Decode.map
            (\s ->
                if String.toUpper s == "UP" then
                    HealthUp

                else
                    HealthDown
            )


versionDecoder : Decoder String
versionDecoder =
    Decode.field "version" Decode.string


simulateResultDecoder : Decoder SimulateResult
simulateResultDecoder =
    Decode.map3 SimulateResult
        (Decode.field "runId" Decode.string)
        (Decode.field "count" Decode.int)
        (Decode.field "ids" (Decode.list Decode.string))


simProgressDecoder : Decoder SimProgress
simProgressDecoder =
    Decode.map5 SimProgress
        (Decode.field "calls" Decode.int)
        (Decode.field "orders" Decode.int)
        (Decode.field "doneCalls" Decode.int)
        (Decode.maybe (Decode.field "firstCall" Decode.string))
        (Decode.maybe (Decode.field "lastDone" Decode.string))
