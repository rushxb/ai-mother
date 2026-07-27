package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.config.DevServerInternalRoutingProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Objects;

/** 配置支持的节点解析器；部署可以用服务发现替换此端口。 */
@Component
public class TemplateDevServerNodeRouteResolver implements DevServerNodeRouteResolver {

    private static final String NODE_ID_PLACEHOLDER = "{nodeId}";
    private static final String SAFE_NODE_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}";

    private final String baseUrlTemplate;

    public TemplateDevServerNodeRouteResolver(DevServerInternalRoutingProperties properties) {
        Objects.requireNonNull(properties, "properties");
        if (!properties.isBaseUrlTemplateValid()) {
            throw new IllegalArgumentException("Dev Server internal base URL template is invalid");
        }
        this.baseUrlTemplate = properties.getBaseUrlTemplate().trim();
    }

    @Override
    public URI resolve(String nodeId) {
        String normalizedNodeId = nodeId == null ? "" : nodeId.trim();
        if (!normalizedNodeId.matches(SAFE_NODE_ID_PATTERN)) {
            throw new IllegalArgumentException("Dev Server node id is not routable");
        }
        URI uri = URI.create(baseUrlTemplate.replace(NODE_ID_PLACEHOLDER, normalizedNodeId)).normalize();
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Resolved Dev Server node endpoint is invalid");
        }
        String value = uri.toString();
        return URI.create(value.endsWith("/") ? value.substring(0, value.length() - 1) : value);
    }
}
