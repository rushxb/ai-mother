package com.rush.rushaicodemother.orchestration.template;

import com.rush.rushaicodemother.ai.AiSlotFillService;
import com.rush.rushaicodemother.ai.AiSlotFillServiceFactory;
import com.rush.rushaicodemother.ai.model.SlotFillOutput;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemplateSlotFillServiceTest {

    @Test
    void shouldGenerateBackendModuleImportDeterministically() {
        TemplateManifestService manifestService = new TemplateManifestService();
        AiSlotFillService aiSlotFillService = mock(AiSlotFillService.class);
        AiSlotFillServiceFactory factory = mock(AiSlotFillServiceFactory.class);
        when(factory.createAiSlotFillService()).thenReturn(aiSlotFillService);
        when(aiSlotFillService.fillSlots(anyString(), anyString(), anyString()))
                .thenReturn(new SlotFillOutput(
                        "生成服务装配",
                        List.of(
                                new SlotFillOutput.SlotContent(
                                        "module_import",
                                        "\"backend-template/internal/modules/wrong\"",
                                        "模型可能带引号或路径错误"
                                ),
                                new SlotFillOutput.SlotContent(
                                        "server_wiring",
                                        """
                                                productRepo := product.NewRepository(db)
                                                productService := product.NewService(productRepo)
                                                productHandler := product.NewHandler(productService)
                                                productHandler.RegisterRoutes(mux)
                                                """,
                                        "注册商品模块"
                                )
                        ),
                        false
                ));

        TemplateSlotFillService service = new TemplateSlotFillService(manifestService, factory);

        SlotFillResult result = service.fillSlots(
                "go-sqlite-backend-basic",
                1L,
                "做一个商品 CRUD 后端",
                List.of("module_import", "server_wiring")
        );

        assertNotNull(result);
        assertTrue(result.filledSlots().contains("module_import"));
        PatchOperation importOperation = result.patchOperations().stream()
                .filter(operation -> PatchOperation.ACTION_GO_ADD_IMPORT.equals(operation.action()))
                .findFirst()
                .orElseThrow();
        assertEquals("backend-template/internal/modules/product", importOperation.newContent());
    }

    @Test
    void shouldExposeAiSlotFillFailureReason() {
        TemplateManifestService manifestService = new TemplateManifestService();
        AiSlotFillService aiSlotFillService = mock(AiSlotFillService.class);
        AiSlotFillServiceFactory factory = mock(AiSlotFillServiceFactory.class);
        when(factory.createAiSlotFillService()).thenReturn(aiSlotFillService);
        when(aiSlotFillService.fillSlots(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Read timed out"));

        TemplateSlotFillService service = new TemplateSlotFillService(manifestService, factory);

        SlotFillResult result = service.fillSlots(
                "vue-web-landing",
                1L,
                "做一个企业官网",
                List.of("landing_core_data")
        );

        assertNull(result);
        assertTrue(service.consumeLastFailureReason().contains("Read timed out"));
    }
}
