module Api exposing (getConfig, getHealth, getStats, getVersion)

import Http
import Json.Decode as Decode
import Task exposing (Task)
import Types exposing (Config, Health, MileageStat, OrdersServedStat)


{-| All URLs are relative so the same build works behind the dev proxy (vite → :8080) and when the
compiled bundle is served from the same origin as the api (nginx in-cluster).
-}
getConfig : (Result Http.Error Config -> msg) -> Cmd msg
getConfig toMsg =
    Http.get
        { url = "/api/config"
        , expect = Http.expectJson toMsg Types.configDecoder
        }


getHealth : (Result Http.Error Health -> msg) -> Cmd msg
getHealth toMsg =
    Http.get
        { url = "/actuator/health"
        , expect = Http.expectJson toMsg Types.healthDecoder
        }


getVersion : (Result Http.Error String -> msg) -> Cmd msg
getVersion toMsg =
    Http.get
        { url = "/api/version"
        , expect = Http.expectJson toMsg Types.versionDecoder
        }


{-| Fetch both Spark BI feeds together; a failed poll keeps the last good data (handled in update)
and the timer keeps running. Uses Task so the two requests combine into one Result.
-}
getStats : (Result Http.Error ( List MileageStat, List OrdersServedStat ) -> msg) -> Cmd msg
getStats toMsg =
    Task.map2 Tuple.pair
        (getJson "/api/mileage" (Decode.list Types.mileageDecoder))
        (getJson "/api/served" (Decode.list Types.servedDecoder))
        |> Task.attempt toMsg


getJson : String -> Decode.Decoder a -> Task Http.Error a
getJson url decoder =
    Http.task
        { method = "GET"
        , headers = []
        , url = url
        , body = Http.emptyBody
        , resolver = Http.stringResolver (jsonResolver decoder)
        , timeout = Nothing
        }


jsonResolver : Decode.Decoder a -> Http.Response String -> Result Http.Error a
jsonResolver decoder response =
    case response of
        Http.GoodStatus_ _ body ->
            Decode.decodeString decoder body
                |> Result.mapError (Decode.errorToString >> Http.BadBody)

        Http.BadStatus_ metadata _ ->
            Err (Http.BadStatus metadata.statusCode)

        Http.NetworkError_ ->
            Err Http.NetworkError

        Http.Timeout_ ->
            Err Http.Timeout

        Http.BadUrl_ url ->
            Err (Http.BadUrl url)
