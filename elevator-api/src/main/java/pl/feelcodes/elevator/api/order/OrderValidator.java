package pl.feelcodes.elevator.api.order;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import pl.feelcodes.elevator.api.config.ElevatorProperties;

/**
 * Validates an {@link OrderRequestDto} against the project params (floor range + fleet). Spring
 * constructs constraint validators through its own factory, so {@link ElevatorProperties} can be
 * injected here. Violations are attached to the {@code floor} / {@code elevatorName} fields, so the
 * error response points at the offending field.
 */
public class OrderValidator implements ConstraintValidator<ValidOrder, OrderRequestDto> {

    private final ElevatorProperties props;

    public OrderValidator(ElevatorProperties props) {
        this.props = props;
    }

    @Override
    public boolean isValid(OrderRequestDto dto, ConstraintValidatorContext ctx) {
        if (dto == null) {
            return true; // a null body is a separate concern (missing request body -> 400 already)
        }
        boolean valid = true;
        ctx.disableDefaultConstraintViolation();

        Integer floor = dto.floor();
        if (floor == null || floor < 0 || floor > props.getMaxFloor()) {
            ctx.buildConstraintViolationWithTemplate("must be between 0 and " + props.getMaxFloor())
                    .addPropertyNode("floor")
                    .addConstraintViolation();
            valid = false;
        }

        String name = dto.elevatorName();
        if (name == null || !props.getElevators().contains(name)) {
            ctx.buildConstraintViolationWithTemplate("must be one of " + props.getElevators())
                    .addPropertyNode("elevatorName")
                    .addConstraintViolation();
            valid = false;
        }
        return valid;
    }
}
