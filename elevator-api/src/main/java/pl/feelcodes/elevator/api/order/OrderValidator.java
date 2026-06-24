package pl.feelcodes.elevator.api.order;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import pl.feelcodes.elevator.api.config.ElevatorProperties;

public class OrderValidator implements ConstraintValidator<ValidOrder, OrderRequestDto> {

    private final ElevatorProperties props;

    public OrderValidator(ElevatorProperties props) {
        this.props = props;
    }

    @Override
    public boolean isValid(OrderRequestDto dto, ConstraintValidatorContext ctx) {
        if (dto == null) {
            return true;
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
