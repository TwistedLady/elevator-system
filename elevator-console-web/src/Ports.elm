port module Ports exposing
    ( connectStream
    , streamErrored
    , streamFrame
    , streamOpened
    , themeChanged
    )

import Json.Decode exposing (Value)


{-| Ask JS to open the SSE stream once (it auto-reconnects afterwards). -}
port connectStream : () -> Cmd msg


{-| One raw elevator-state frame from the SSE stream, decoded in update. -}
port streamFrame : (Value -> msg) -> Sub msg


{-| The stream connected. -}
port streamOpened : (() -> msg) -> Sub msg


{-| The stream dropped (JS EventSource will retry on its own). -}
port streamErrored : (() -> msg) -> Sub msg


{-| The OS colour-scheme flipped (prefers-color-scheme). True = dark. -}
port themeChanged : (Bool -> msg) -> Sub msg
