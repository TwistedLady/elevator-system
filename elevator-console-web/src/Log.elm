port module Log exposing (debug, error, info, warn)

{-| Structured logging to the browser console via a port (Elm is pure, and Debug.log is banned
under --optimize). main.js prefixes with [web-console] and filters by a level threshold, mirroring
the old logger.ts.
-}

import Json.Encode as E


port logMessage : E.Value -> Cmd msg


emit : String -> String -> Cmd msg
emit level message =
    logMessage (E.object [ ( "level", E.string level ), ( "message", E.string message ) ])


debug : String -> Cmd msg
debug =
    emit "debug"


info : String -> Cmd msg
info =
    emit "info"


warn : String -> Cmd msg
warn =
    emit "warn"


error : String -> Cmd msg
error =
    emit "error"
