module Filter exposing (matches)

import Regex


{-| Name filter shared by all tabs (same behaviour as the Rust console): try the query as a
case-insensitive regex, fall back to a plain substring match if it doesn't compile. An
empty/whitespace query matches everything.
-}
matches : String -> String -> Bool
matches query name =
    case String.trim query of
        "" ->
            True

        trimmed ->
            case Regex.fromStringWith { caseInsensitive = True, multiline = False } trimmed of
                Just re ->
                    Regex.contains re name

                Nothing ->
                    String.contains (String.toLower trimmed) (String.toLower name)
