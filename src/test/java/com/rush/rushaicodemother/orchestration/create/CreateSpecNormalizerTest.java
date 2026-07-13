package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.ai.model.CreateSpec;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateSpecNormalizerTest {

    @Test
    void shouldSanitizeAndNormalizeAiSpecBeforeRendering() {
        CreateSpec dirty = new CreateSpec(
                new CreateSpec.Product("admin", "fitness_saas<script>", "FitPilot<script>alert(1)</script>",
                        "健身房运营人员 token=secret", "提升效率"),
                List.of(),
                List.of(new CreateSpec.EntitySpec("select", "课程", List.of(
                        new CreateSpec.FieldSpec("课程名称", "unknown", "课程名称", true, List.of()),
                        new CreateSpec.FieldSpec("type", "decimal", "价格", false, List.of()),
                        new CreateSpec.FieldSpec("type", "decimal", "重复价格", false, List.of())
                ), List.of(), List.of())),
                new CreateSpec.Frontend("sidebar_dashboard", List.of("运营中台", "高级"),
                        "compact", List.of("data_table"), List.of("分页"), List.of("指标卡"),
                        List.of("工作台"), new CreateSpec.Theme("#111111", "bad-color", "http://127.0.0.1", "8px", "light")),
                new CreateSpec.Backend("rest", true, true, true, true, true,
                        List.of("createdAt"), false, true, List.of("required"), "standard_json", "select"),
                null,
                null,
                null
        );

        CreateSpecNormalizer.NormalizedSpec normalized = new CreateSpecNormalizer().normalize(
                dirty,
                "做一个健身房课程管理后台",
                plan(),
                new SlotGroup("admin", "vue-web-admin", "admin", List.of("mock_data"), 0)
        );

        CreateSpec spec = normalized.spec();
        assertEquals("FitPilot", spec.product().brandName());
        assertEquals("Select", spec.entities().getFirst().name());
        assertEquals("课程名称", spec.entities().getFirst().fields().getFirst().label());
        assertEquals("string", spec.entities().getFirst().fields().getFirst().type());
        assertTrue(spec.entities().getFirst().fields().stream().anyMatch(field -> "typeValue".equals(field.name())));
        assertEquals("#f97316", spec.frontend().theme().accent());
        assertFalse(spec.product().audience().contains("secret"));
        assertTrue(spec.frontend().styleKeywords().contains("ops_dashboard"));
        assertTrue(normalized.validation().warnings().stream().anyMatch(item -> item.startsWith("unsupported_field_type")));
    }

    private CreateGenerationPlan plan() {
        return new CreateGenerationPlan(
                CodeGenTypeEnum.VUE_PROJECT,
                new CreateTemplateManifest("vue-web-admin", CodeGenTypeEnum.VUE_PROJECT, "admin"),
                List.of(),
                List.of(),
                0.9,
                "test",
                "test",
                ""
        );
    }
}
