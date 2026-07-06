package pl.feelcodes.elevator.api.order;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import pl.feelcodes.elevator.api.config.ElevatorLimits;

public class OrderValidator implements ConstraintValidator<ValidOrder, OrderRequestDto> {

    private final ElevatorLimits limits;

    public OrderValidator(ElevatorLimits limits) {
        this.limits = limits;
    }

    @Override
    public boolean isValid(OrderRequestDto dto, ConstraintValidatorContext ctx) {
        if (dto == null) {
            return true;
        }
        boolean valid = true;
        ctx.disableDefaultConstraintViolation();

        Integer floor = dto.floor();
        if (floor == null || floor < 0 || floor > limits.getMaxFloor()) {
            ctx.buildConstraintViolationWithTemplate("must be between 0 and " + limits.getMaxFloor())
                    .addPropertyNode("floor")
                    .addConstraintViolation();
            valid = false;
        }

        String name = dto.elevatorName();
        if (name == null || !limits.getElevators().contains(name)) {
            ctx.buildConstraintViolationWithTemplate("must be one of " + limits.getElevators())
                    .addPropertyNode("elevatorName")
                    .addConstraintViolation();
            valid = false;
        }
        return valid;
    }
}
