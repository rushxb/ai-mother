package com.rush.rushaicodemother.testsupport;

import com.rush.rushaicodemother.infrastructure.security.AiModelOutboundDestinationPolicy;

import java.net.InetAddress;

/** 为单元测试提供不依赖外部 DNS 的公网目的地址策略。 */
public final class AiModelOutboundSecurityTestFixtures {

    private AiModelOutboundSecurityTestFixtures() {
    }

    public static AiModelOutboundDestinationPolicy publicInternetPolicy() {
        return new AiModelOutboundDestinationPolicy(
                host -> new InetAddress[]{InetAddress.getByAddress(
                        new byte[]{8, 8, 8, 8})});
    }
}
