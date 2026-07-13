package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.HashSet;
import java.util.List;

/**
 * 项目模板依赖预热配置。
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.template-pre-warm")
public class TemplatePreWarmProperties {

    /** 是否在应用就绪后预热模板依赖。 */
    private boolean enabled = false;

    /** 同时执行的模板依赖安装任务数。 */
    @Min(1)
    @Max(8)
    private int maxConcurrency = 2;

    /** 需要预热的模板 ID。 */
    @NotEmpty
    @Size(max = 32)
    private List<
            @NotBlank
            @Size(max = 64)
            @Pattern(
                    regexp = "[a-z0-9]+(?:-[a-z0-9]+)*",
                    message = "模板 ID 只能包含小写字母、数字和单个连字符分隔符"
            )
                    String> templateIds = List.of(
            "vue-web-basic",
            "vue-web-admin",
            "vue-web-mobile",
            "vue-web-landing"
    );

    @AssertTrue(message = "模板预热列表不能包含重复的模板 ID")
    public boolean isTemplateIdsUnique() {
        return templateIds == null || new HashSet<>(templateIds).size() == templateIds.size();
    }
}
