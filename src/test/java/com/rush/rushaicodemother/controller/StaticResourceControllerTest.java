package com.rush.rushaicodemother.controller;

import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.infrastructure.filesystem.SecurePathResolver;
import com.rush.rushaicodemother.service.artifact.DeploymentArtifactResourceService;
import com.rush.rushaicodemother.service.artifact.DeploymentKeyPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StaticResourceControllerTest {

    private Path tempDirectory;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws IOException {
        Path testRoot = Path.of("target", "test-work", "static-resource-controller")
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(testRoot);
        tempDirectory = Files.createDirectory(testRoot.resolve(UUID.randomUUID().toString()));

        CodeStorageProperties storageProperties = new CodeStorageProperties();
        storageProperties.setOutputRootDir(Files.createDirectory(tempDirectory.resolve("output")));
        storageProperties.setDeployRootDir(Files.createDirectory(tempDirectory.resolve("deploy")));
        DeploymentArtifactResourceService resourceService = new DeploymentArtifactResourceService(
                storageProperties,
                new DeploymentKeyPolicy(),
                new SecurePathResolver()
        );
        mockMvc = MockMvcBuilders.standaloneSetup(new StaticResourceController(resourceService)).build();
    }

    @Test
    void shouldServeCommittedDeploymentInsteadOfGeneratedWorkspace() throws Exception {
        Path outputDeployment = Files.createDirectories(tempDirectory.resolve("output/Deploy123"));
        Files.writeString(outputDeployment.resolve("index.html"), "generated-source", StandardCharsets.UTF_8);
        Path committedDeployment = Files.createDirectories(tempDirectory.resolve("deploy/Deploy123"));
        Files.writeString(committedDeployment.resolve("index.html"), "committed-deployment", StandardCharsets.UTF_8);

        mockMvc.perform(get("/static/Deploy123/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().string("committed-deployment"));
    }

    @Test
    void shouldServeDirectoryIndexAndRedirectMissingTrailingSlash() throws Exception {
        Path nestedDirectory = Files.createDirectories(tempDirectory.resolve("deploy/Deploy123/docs"));
        Files.writeString(nestedDirectory.resolve("index.html"), "documentation", StandardCharsets.UTF_8);

        mockMvc.perform(get("/static/Deploy123"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/static/Deploy123/"));
        mockMvc.perform(get("/static/Deploy123/docs/"))
                .andExpect(status().isOk())
                .andExpect(content().string("documentation"));
    }

    @Test
    void shouldReturnNotFoundWhenResourceExistsOnlyInGeneratedOutput() throws Exception {
        Path generatedDirectory = Files.createDirectories(tempDirectory.resolve("output/Deploy123"));
        Files.writeString(generatedDirectory.resolve("index.html"), "not-deployed", StandardCharsets.UTF_8);

        mockMvc.perform(get("/static/Deploy123/index.html"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deployedArtifactMustBeSandboxedAndForbiddenFromCallingPlatformApi() throws Exception {
        Path deploymentDirectory = Files.createDirectories(tempDirectory.resolve("deploy/Deploy123"));
        Files.writeString(deploymentDirectory.resolve("index.html"),
                "<script>fetch('/api/app/list/my',{credentials:'include'})</script>",
                StandardCharsets.UTF_8);

        String policy = mockMvc.perform(get("/static/Deploy123/index.html"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Cross-Origin-Resource-Policy", "same-origin"))
                .andReturn()
                .getResponse()
                .getHeader("Content-Security-Policy");

        assertNotNull(policy, "生成产物必须下发 CSP");
        // 不授予 allow-same-origin 是隔离生效的关键：产物获得不透明源，平台会话 Cookie 不再随行。
        assertTrue(policy.contains("sandbox "), "生成产物必须启用 sandbox");
        assertFalse(policy.contains("allow-same-origin"), "生成产物不得获得同源权限");
        // 已部署产物是静态文件，不需要 HMR，外发通道应完全切断。
        assertTrue(policy.contains("connect-src 'none'"), "已部署产物必须切断 fetch/XHR/WebSocket");
        assertTrue(policy.contains("form-action 'none'"), "必须阻断表单外发");
    }

    @Test
    void shouldResolveProductionFrontendAssetMediaTypes() throws Exception {
        Path deploymentDirectory = Files.createDirectories(tempDirectory.resolve("deploy/Deploy123"));
        Files.write(deploymentDirectory.resolve("app.wasm"), new byte[]{0});
        Files.write(deploymentDirectory.resolve("font.woff2"), new byte[]{0});
        Files.write(deploymentDirectory.resolve("logo.webp"), new byte[]{0});

        mockMvc.perform(get("/static/Deploy123/app.wasm"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/wasm"));
        mockMvc.perform(get("/static/Deploy123/font.woff2"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("font/woff2"));
        mockMvc.perform(get("/static/Deploy123/logo.webp"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/webp"));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDirectory == null || !Files.exists(tempDirectory)) {
            return;
        }
        try (var paths = Files.walk(tempDirectory)) {
            for (Path current : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(current);
            }
        }
    }
}
