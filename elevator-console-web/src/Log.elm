port module Log exposing (debug, error, info, warn)

{-| Console logging via a port (Elm is pure; Debug.log is banned under --optimize).
main.js adds the [web-console] prefix and level filtering.
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
