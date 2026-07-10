module Header exposing (view, warnbar)

{-| The single header bar: title on the left, a health badge and a version badge on the right.
Health is the point — the SSE "stream" badge is gone.
-}

import Html exposing (Html, button, div, h1, header, i, span, strong, text)
import Html.Attributes exposing (attribute, class, classList, title, type_)
import Html.Events exposing (onClick)
import Types exposing (BackendVersion(..), Health(..))


view :
    { webVersion : String
    , backendVersion : BackendVersion
    , health : Health
    , refreshHealth : msg
    , refreshVersion : msg
    }
    -> Html msg
view cfg =
    header [ class "topbar" ]
        [ h1 [] [ text "🛗 ELEVATOR CONSOLE" ]
        , div [ class "badges" ]
            [ healthBadge cfg.health cfg.refreshHealth
            , versionBadge cfg.webVersion cfg.backendVersion cfg.refreshVersion
            ]
        ]


healthBadge : Health -> msg -> Html msg
healthBadge health onRefresh =
    button
        [ type_ "button"
        , class "badge"
        , classList
            [ ( "ok", health == HealthUp )
            , ( "warn", health == HealthUnknown )
            , ( "bad", health == HealthDown )
            ]
        , onClick onRefresh
        , title "click to refresh"
        ]
        [ i [ class "dot" ] [], text ("API " ++ Types.healthLabel health) ]


versionBadge : String -> BackendVersion -> msg -> Html msg
versionBadge webVersion backendVersion onRefresh =
    button
        [ type_ "button"
        , class "badge"
        , classList
            [ ( "ok", matches webVersion backendVersion )
            , ( "bad", mismatches webVersion backendVersion )
            ]
        , onClick onRefresh
        , title "web console version vs backend version — click to recheck"
        ]
        [ text
            ("v"
                ++ webVersion
                ++ separator webVersion backendVersion
                ++ "api "
                ++ Types.backendLabel backendVersion
            )
        ]


warnbar : String -> BackendVersion -> Html msg
warnbar webVersion backendVersion =
    if mismatches webVersion backendVersion then
        div [ class "warnbar", attribute "role" "alert" ]
            [ text "⚠ Version mismatch — web console "
            , strong [] [ text ("v" ++ webVersion) ]
            , text ", backend "
            , strong [] [ text ("v" ++ Types.backendLabel backendVersion) ]
            , text ". Run matching builds."
            ]

    else
        text ""


separator : String -> BackendVersion -> String
separator webVersion backendVersion =
    if matches webVersion backendVersion then
        " = "

    else if mismatches webVersion backendVersion then
        " ≠ "

    else
        " · "


matches : String -> BackendVersion -> Bool
matches webVersion backendVersion =
    case backendVersion of
        Version v ->
            v == webVersion

        _ ->
            False


mismatches : String -> BackendVersion -> Bool
mismatches webVersion backendVersion =
    case backendVersion of
        Version v ->
            v /= webVersion

        _ ->
            False
