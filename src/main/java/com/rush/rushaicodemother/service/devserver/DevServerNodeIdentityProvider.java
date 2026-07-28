package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.config.DevServerRuntimeProperties;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.UUID;

/** 提供稳定的节点身份和流程独特的租赁所有权围栏。 */
@Component
public class DevServerNodeIdentityProvider {

    private static final int MAX_NODE_ID_LENGTH = 128;
    private static final int MAX_OWNER_ID_LENGTH = 160;
    private static final String SAFE_NODE_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}";

    private final String nodeId;
    private final String ownerId;

    /**
 * 创建开发服务器节点{@code Identity}提供方实例并完成必要的依赖和初始状态设置。
 *
 * @param properties 配置属性
 */
    public DevServerNodeIdentityProvider(DevServerRuntimeProperties properties) {
        String configuredNodeId = normalize(properties == null ? null : properties.getNodeId());
        this.nodeId = truncate(configuredNodeId == null ? resolveHostName() : configuredNodeId,
                MAX_NODE_ID_LENGTH);
        if (!nodeId.matches(SAFE_NODE_ID_PATTERN)) {
            throw new IllegalArgumentException("Dev Server node id is not safe for internal routing");
        }
        String processId = normalize(ManagementFactory.getRuntimeMXBean().getName());
        this.ownerId = truncate(
                nodeId + ":" + (processId == null ? "unknown-process" : processId)
                        + ":" + UUID.randomUUID(),
                MAX_OWNER_ID_LENGTH
        );
    }

    public String nodeId() {
        return nodeId;
    }

    public String ownerId() {
        return ownerId;
    }

    /** 根据当前上下文解析主机名称。 */
    private String resolveHostName() {
        try {
            String hostName = normalize(InetAddress.getLocalHost().getHostName());
            return hostName == null ? "unknown-host" : hostName;
        } catch (Exception ignored) {
            return "unknown-host";
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
