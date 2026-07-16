package com.rush.rushaicodemother.orchestration.runtime.task;

import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.UUID;

/** Creates a process-unique lease owner identity without hard-coding deployment topology. */
@Component
public class GenerationTaskLeaseOwnerProvider {

    private static final int MAX_OWNER_LENGTH = 128;

    private final String ownerId;

    public GenerationTaskLeaseOwnerProvider(GenerationTaskLeaseProperties properties) {
        String configuredPrefix = normalize(properties == null ? null : properties.getOwnerId());
        String host = resolveHostName();
        String process = normalize(ManagementFactory.getRuntimeMXBean().getName());
        String prefix = configuredPrefix == null ? host : configuredPrefix;
        this.ownerId = truncate(prefix + ":" + process + ":" + UUID.randomUUID(), MAX_OWNER_LENGTH);
    }

    public String ownerId() {
        return ownerId;
    }

    private String resolveHostName() {
        try {
            return normalize(InetAddress.getLocalHost().getHostName());
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
