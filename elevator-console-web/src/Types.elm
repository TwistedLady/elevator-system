module Types exposing
    ( BackendVersion(..)
    , Config
    , Direction(..)
    , ElevatorState
    , Health(..)
    , Motion(..)
    , Row
    , SimStatus
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
    , simStatusDecoder
    , simulateResultDecoder
    , versionDecoder
    )

import Json.Decode as Decode exposing (Decoder)


{-| Mirrors the elevator-api elevator-state contract (elevator-common-dto/Dtos.scala,
GET /api/elevator/stream). The console is read-only.
-}
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


{-| A live snapshot paired with its recent floor history (oldest → newest), for the charts. -}
type alias Row =
    { state : ElevatorState
    , history : List Int
    }


{-| /actuator/health status, reflected in the health badge. -}
type Health
    = HealthUnknown
    | HealthUp
    | HealthDown


{-| The backend's reported version vs. this build's — drives the match/mismatch badge. -}
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


{-| Live limits from GET /api/config; maxFloor is 0 until the first fetch (never hardcoded). -}
type alias Config =
    { maxFloor : Int
    , biEnabled : Bool
    }


{-| The 202-ish response from POST /api/simulate — the run id plus the call ids to poll. -}
type alias SimulateResult =
    { runId : String
    , count : Int
    , ids : List String
    }


{-| The rollup from POST /api/simulate/status; done + progress + pending == total. -}
type alias SimStatus =
    { total : Int
    , done : Int
    , progress : Int
    , pending : Int
    }


{-| How many recent floor samples the Trend tab keeps per elevator. -}
historyLen : Int
historyLen =
    48



-- LABELS


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


{-| Sort key for natural elevator order (e1, e2, … e10), matching the other consoles: split the
name into its non-digit prefix and trailing number so "e10" sorts after "e2".
-}
naturalKey : String -> ( String, Int )
naturalKey name =
    ( String.filter (not << Char.isDigit) name
    , String.toInt (String.filter Char.isDigit name) |> Maybe.withDefault 0
    )



-- DECODERS


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


{-| Decoding fails (and the frame is dropped upstream) when elevatorName is missing, so frames
without a name are ignored. -}
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


simStatusDecoder : Decoder SimStatus
simStatusDecoder =
    Decode.map4 SimStatus
        (Decode.field "total" Decode.int)
        (Decode.field "done" Decode.int)
        (Decode.field "progress" Decode.int)
        (Decode.field "pending" Decode.int)
