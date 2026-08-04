package com.rush.rushaicodemother.orchestration.learning;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 生成模式影子路由配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "app.generation-routing.shadow")
public class GenerationShadowRoutingProperties {

    /** 默认关闭，避免未经 Benchmark 验证的新路由影响生产请求成本。 */
    private boolean enabled = false;
}
