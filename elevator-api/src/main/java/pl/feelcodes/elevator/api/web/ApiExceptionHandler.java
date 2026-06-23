package pl.feelcodes.elevator.api.web;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;

/**
 * Turns a failed {@code @Valid} on a request body into a clean 400 with the field messages, e.g.
 * <pre>{"error":"validation failed","details":["floor: must be between 0 and 15"]}</pre>
 * (WebFlux raises {@link WebExchangeBindException} for bean-validation failures on {@code @RequestBody}).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<Map<String, Object>> onValidation(WebExchangeBindException ex) {
        List<String> details = ex.getFieldErrors().stream()
                .map(fe -> fieldName(fe) + ": " + fe.getDefaultMessage())
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "validation failed", "details", details));
    }

    private static String fieldName(FieldError fe) {
        return fe.getField();
    }
}
