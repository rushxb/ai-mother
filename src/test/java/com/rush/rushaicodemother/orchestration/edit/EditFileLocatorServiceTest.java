package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.index.WorkspaceSemanticIndexService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EditFileLocatorServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPrioritizeSelectedElementComponentOverUnrelatedFallbackFiles() throws Exception {
        Files.createDirectories(tempDir.resolve("src/components"));
        Files.createDirectories(tempDir.resolve("src/pages"));
        Files.createDirectories(tempDir.resolve("src/components/tres/particles"));
        Files.createDirectories(tempDir.resolve(".ai-code-index"));
        Files.createDirectories(tempDir.resolve("dist/assets"));
        Files.createDirectories(tempDir.resolve("node_modle/.vite"));
        Files.writeString(tempDir.resolve("src/components/ProductCard.vue"), """
                <template>
                  <article class="product-card">
                    <h3>{{ product.name }}</h3>
                    <button class="cart-button">加入购物车</button>
                  </article>
                </template>
                """);
        Files.writeString(tempDir.resolve("src/pages/MobileHomePage.vue"), """
                <template>
                  <div class="product-list">
                    <ProductCard />
                  </div>
                </template>
                """);
        Files.writeString(tempDir.resolve("src/components/tres/particles/ParticleSwarm.vue"), "<template><div /></template>");
        Files.writeString(tempDir.resolve(".ai-code-index/semantic-index.json"), "{\"product-card\":\"加入购物车\"}");
        Files.writeString(tempDir.resolve("dist/assets/ProductCard-CJZ0qB1T.js"), "console.log('product-card 加入购物车')");
        Files.writeString(tempDir.resolve("node_modle/.vite/ProductCard.js"), "console.log('product-card 加入购物车')");
        Files.writeString(tempDir.resolve("src/App.vue"), "<template><RouterView /></template>");
        Files.writeString(tempDir.resolve("src/main.ts"), "import './styles/mobile.css'\n");

        WorkspaceSemanticIndexService semanticIndexService = mock(WorkspaceSemanticIndexService.class);
        when(semanticIndexService.suggestFiles(any(), any(), anyInt()))
                .thenReturn(List.of(
                        ".ai-code-index/semantic-index.json",
                        "dist/assets/ProductCard-CJZ0qB1T.js",
                        "node_modle/.vite/ProductCard.js",
                        "src/components/tres/particles/ParticleSwarm.vue"
                ));
        EditStatePersistenceService editStatePersistenceService = mock(EditStatePersistenceService.class);
        when(editStatePersistenceService.getRelevantRecentFiles(any(), any(), anyInt()))
                .thenReturn(List.of());

        EditFileLocatorService service = new EditFileLocatorService(
                semanticIndexService,
                null,
                editStatePersistenceService
        );

        List<EditFileCandidate> candidates = service.locate(workspace(), """
                加入购物车按钮样式异常，帮我调整

                选中元素信息：
                - 页面路径: #/
                - 标签: article
                - 选择器: div#app > div.site-wrapper:nth-child(1) > main.site-main:nth-child(2) > div.mobile-page:nth-child(1) > section.mobile-section:nth-child(6) > div.product-list:nth-child(2) > article.product-card:nth-child(2)
                - 当前内容: 新品香草鸡胸能量餐¥36.8加入购物车
                """, CodeGenTypeEnum.VUE_PROJECT);

        assertEquals("src/components/ProductCard.vue", candidates.getFirst().relativePath());
        assertEquals("selected_element", candidates.getFirst().matchType());
        assertTrue(candidates.stream().anyMatch(candidate -> "src/pages/MobileHomePage.vue".equals(candidate.relativePath())));
        assertTrue(candidates.stream().noneMatch(candidate -> candidate.relativePath().startsWith("dist/")));
        assertTrue(candidates.stream().noneMatch(candidate -> candidate.relativePath().startsWith(".ai-code-index/")));
        assertTrue(candidates.stream().noneMatch(candidate -> candidate.relativePath().startsWith("node_modle/")));
    }

    private GenerationWorkspace workspace() {
        Path root = tempDir.toAbsolutePath().normalize();
        return new GenerationWorkspace(
                1L,
                CodeGenTypeEnum.VUE_PROJECT,
                root,
                root,
                true,
                root,
                null,
                Set.of(),
                Set.of("vue", "js", "ts", "css")
        );
    }
}
