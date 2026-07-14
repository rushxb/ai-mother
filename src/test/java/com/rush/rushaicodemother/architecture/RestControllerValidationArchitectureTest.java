package com.rush.rushaicodemother.architecture;

import jakarta.validation.Valid;
import org.junit.jupiter.api.Test;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REST input-validation architecture gate.
 *
 * <p>Every request DTO crosses one validation seam at the controller boundary. This prevents
 * controller-specific null checks from diverging and ensures constraints added to a DTO later are
 * automatically enforced by every endpoint that accepts it.</p>
 */
class RestControllerValidationArchitectureTest {

    private static final String CONTROLLER_PACKAGE = "com.rush.rushaicodemother.controller";
    private static final String CONSTRAINT_PACKAGE = "jakarta.validation.constraints";

    @Test
    void requestBodiesMustUseBeanValidation() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Class<?> controller : loadRestControllers()) {
            for (Method method : controller.getDeclaredMethods()) {
                Parameter[] parameters = method.getParameters();
                for (int index = 0; index < parameters.length; index++) {
                    Parameter parameter = parameters[index];
                    if (parameter.isAnnotationPresent(RequestBody.class)
                            && !parameter.isAnnotationPresent(Valid.class)) {
                        violations.add(location(controller, method, index)
                                + " accepts @RequestBody without @Valid");
                    }
                }
            }
        }

        assertNoViolations("REST request bodies must use the shared Bean Validation seam", violations);
    }

    @Test
    void controllersWithMethodConstraintsMustEnableMethodValidation() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Class<?> controller : loadRestControllers()) {
            boolean hasMethodConstraint = Stream.of(controller.getDeclaredMethods())
                    .flatMap(method -> Stream.of(method.getParameters()))
                    .flatMap(parameter -> Stream.of(parameter.getAnnotations()))
                    .map(Annotation::annotationType)
                    .map(Class::getPackageName)
                    .anyMatch(CONSTRAINT_PACKAGE::equals);
            if (hasMethodConstraint && !controller.isAnnotationPresent(Validated.class)) {
                violations.add(controller.getName() + " declares method constraints without @Validated");
            }
        }

        assertNoViolations("REST method constraints must be active", violations);
    }

    private List<Class<?>> loadRestControllers() throws Exception {
        return ProductionClassScanner.load(CONTROLLER_PACKAGE).stream()
                .filter(type -> type.isAnnotationPresent(RestController.class))
                .toList();
    }

    private String location(Class<?> controller, Method method, int parameterIndex) {
        return controller.getName() + "#" + method.getName() + " parameter[" + parameterIndex + "]";
    }

    private void assertNoViolations(String rule, List<String> violations) {
        violations.sort(Comparator.naturalOrder());
        assertTrue(violations.isEmpty(), () -> rule + ":\n - " + String.join("\n - ", violations));
    }
}
