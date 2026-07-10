module Api exposing (getConfig, getHealth, getVersion, progress, simulate)

import Http
import Types exposing (Config, Health, SimProgress, SimulateResult)


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


{-| Kick off a run — no body, so the api uses its default count (10000). -}
simulate : (Result Http.Error SimulateResult -> msg) -> Cmd msg
simulate toMsg =
    Http.post
        { url = "/api/simulate"
        , body = Http.emptyBody
        , expect = Http.expectJson toMsg Types.simulateResultDecoder
        }


{-| Poll a run's rolled-up progress — GET /api/simulate/progress?runId=&size=. One request per
tick drives the whole bar. -}
progress : String -> Int -> (Result Http.Error SimProgress -> msg) -> Cmd msg
progress runId size toMsg =
    Http.get
        { url = "/api/simulate/progress?runId=" ++ runId ++ "&size=" ++ String.fromInt size
        , expect = Http.expectJson toMsg Types.simProgressDecoder
        }
