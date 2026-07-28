package com.rush.rushaicodemother.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局跨域访问配置。
 */
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(CorsProperties.class)
public class CorsConfig implements WebMvcConfigurer {

    private final CorsProperties corsProperties;

    /**
 * 添加跨域映射。
 *
 * @param registry 目标注册器
 */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(corsProperties.getAllowedOrigins().toArray(String[]::new))
                .allowedMethods(corsProperties.getAllowedMethods().toArray(String[]::new))
                .allowedHeaders(corsProperties.getAllowedHeaders().toArray(String[]::new))
                .exposedHeaders(corsProperties.getExposedHeaders().toArray(String[]::new))
                .allowCredentials(corsProperties.isAllowCredentials())
                .maxAge(corsProperties.getMaxAge().toSeconds());
    }
}