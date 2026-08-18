package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateTemplatePlannerRegistryTest {

    @Test
    void registeredAdapterExtendsCreatePlanningWithoutChangingPlanner() {
        CreateGenerationPlan expected = new CreateGenerationPlan(
                CodeGenTypeEnum.HTML,
                new CreateTemplateManifest("html-marketing-page", CodeGenTypeEnum.HTML, "测试模板"),
                List.of(),
                List.of(),
                1.0,
                "测试 adapter",
                "test",
                ""
        );
        CreateTemplatePlanningAdapter htmlAdapter = new CreateTemplatePlanningAdapter() {
            @Override
            public CodeGenTypeEnum codeGenType() {
                return CodeGenTypeEnum.HTML;
            }

            @Override
            public CreateGenerationPlan plan(String userMessage) {
                return expected;
            }
        };
        CreateTemplatePlanner planner = new CreateTemplatePlanner(List.of(htmlAdapter));

        CreateGenerationPlan actual = planner.plan(CodeGenTypeEnum.HTML, "生成营销页");

        assertEquals(expected, actual);
    }

    @Test
    void duplicateTypeAdaptersFailDuringRegistryConstruction() {
        CreateTemplatePlanningAdapter first = adapter(CodeGenTypeEnum.HTML);
        CreateTemplatePlanningAdapter duplicate = adapter(CodeGenTypeEnum.HTML);

        assertThrows(
                IllegalStateException.class,
                () -> new CreateTemplatePlanner(List.of(first, duplicate))
        );
    }

    @Test
    void emptyAdapterRegistryFailsBeforeCreateTrafficIsAccepted() {
        assertThrows(
                IllegalStateException.class,
                () -> new CreateTemplatePlanner(List.of())
        );
    }

    @Test
    void unregisteredTypeReturnsDiagnosticUnsupportedPlan() {
        CreateTemplatePlanner planner = new CreateTemplatePlanner(
                List.of(adapter(CodeGenTypeEnum.BACKEND_PROJECT))
        );

        CreateGenerationPlan plan = planner.plan(CodeGenTypeEnum.MULTI_FILE, "生成多文件页面");

        assertEquals(CodeGenTypeEnum.MULTI_FILE, plan.codeGenType());
        assertNull(plan.baseTemplate());
        assertTrue(plan.modules().isEmpty());
        assertEquals("template_coverage_missing", plan.fallbackReason());
    }

    @Test
    void adapterReturningAnotherTypeViolatesRegistryContract() {
        CreateTemplatePlanningAdapter invalid = new CreateTemplatePlanningAdapter() {
            @Override
            public CodeGenTypeEnum codeGenType() {
                return CodeGenTypeEnum.HTML;
            }

            @Override
            public CreateGenerationPlan plan(String userMessage) {
                return new CreateGenerationPlan(
                        CodeGenTypeEnum.MULTI_FILE,
                        null,
                        List.of(),
                        List.of(),
                        1.0,
                        "wrong type",
                        "test",
                        ""
                );
            }
        };
        CreateTemplatePlanner planner = new CreateTemplatePlanner(List.of(invalid));

        assertThrows(
                IllegalStateException.class,
                () -> planner.plan(CodeGenTypeEnum.HTML, "生成页面")
        );
    }

    private CreateTemplatePlanningAdapter adapter(CodeGenTypeEnum codeGenType) {
        return new CreateTemplatePlanningAdapter() {
            @Override
            public CodeGenTypeEnum codeGenType() {
                return codeGenType;
            }

            @Override
            public CreateGenerationPlan plan(String userMessage) {
                return new CreateGenerationPlan(
                        codeGenType, null, List.of(), List.of(), 1.0,
                        "test", "test", ""
                );
            }
        };
    }
}
