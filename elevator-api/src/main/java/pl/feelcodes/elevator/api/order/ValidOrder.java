package pl.feelcodes.elevator.api.order;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level Jakarta Bean Validation constraint on an {@link OrderRequestDto}: the floor must be
 * within 0..max-floor and the elevator must be in the configured fleet. The bounds come from
 * {@code ElevatorProperties} (config), so {@link OrderValidator} is config-driven rather than using
 * constant annotations like {@code @Max(15)}.
 */
@Documented
@Constraint(validatedBy = OrderValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidOrder {
    String message() default "invalid order";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
