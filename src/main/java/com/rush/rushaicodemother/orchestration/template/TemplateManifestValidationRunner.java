package com.rush.rushaicodemother.orchestration.template;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 启动时校验模板 manifest，提前发现 manifest 与实际模板文件漂移。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TemplateManifestValidationRunner implements ApplicationRunner {

    private static final String TEMPLATE_ROOT = "project-templates";

    private final TemplateManifestService templateManifestService;

    @Override
    public void run(ApplicationArguments args) {
        for (String templateId : discoverTemplateIds()) {
            TemplateManifestService.ManifestValidationResult result = templateManifestService.validateManifest(templateId);
            if (!result.valid()) {
                log.warn("模板 manifest 启动校验失败: templateId={}, errors={}, warnings={}",
                        templateId, result.errors(), result.warnings());
            } else if (!result.warnings().isEmpty()) {
                log.info("模板 manifest 启动校验通过但存在提醒: templateId={}, warnings={}",
                        templateId, result.warnings());
            } else {
                log.debug("模板 manifest 启动校验通过: {}", templateId);
            }
        }
    }

    private List<String> discoverTemplateIds() {
        try {
            Resource[] manifests = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:" + TEMPLATE_ROOT + "/*/template-manifest.json");
            return Arrays.stream(manifests)
                    .map(this::templateIdFromResource)
                    .filter(id -> id != null && !id.isBlank())
                    .distinct()
                    .toList();
        } catch (Exception e) {
            log.warn("扫描模板 manifest 失败，使用内置模板列表: {}", LogExceptionSanitizer.sanitizeMessage(e));
            return List.of("vue-web-basic", "vue-web-admin", "vue-web-landing", "vue-web-mobile", "go-sqlite-backend-basic");
        }
    }

    private String templateIdFromResource(Resource resource) {
        try {
            String url = resource.getURL().toString().replace("\\", "/");
            int rootIndex = url.indexOf(TEMPLATE_ROOT + "/");
            if (rootIndex < 0) {
                return "";
            }
            String suffix = url.substring(rootIndex + TEMPLATE_ROOT.length() + 1);
            int slashIndex = suffix.indexOf('/');
            return slashIndex < 0 ? "" : suffix.substring(0, slashIndex);
        } catch (Exception e) {
            return "";
        }
    }
}
