package com.rush.rushaicodemother.infrastructure.security;

import com.rush.rushaicodemother.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiModelOutboundDestinationPolicyTest {

    @Test
    void approvedModelDestinationMustRejectCrossOriginRequest() throws Exception {
        AiModelOutboundDestinationPolicy policy = new AiModelOutboundDestinationPolicy(
                host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")});
        var approved = policy.approveBaseUrl("https://models.example.com/v1");

        assertThrows(BusinessException.class, () -> policy.requireAllowedRequest(
                approved,
                URI.create("https://attacker.example/v1/chat/completions")
        ));
    }

    @Test
    void dnsRebindingToPrivateAddressMustBeRejectedAtRequestTime() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        AiModelOutboundDestinationPolicy policy = new AiModelOutboundDestinationPolicy(
                host -> new InetAddress[]{InetAddress.getByName(
                        resolutions.getAndIncrement() == 0 ? "8.8.8.8" : "10.0.0.7")});
        var approved = policy.approveBaseUrl("https://models.example.com/v1");

        assertThrows(BusinessException.class, () -> policy.requireAllowedRequest(
                approved,
                URI.create("https://models.example.com/v1/chat/completions")
        ));
        assertEquals(2, resolutions.get());
    }

    @Test
    void mixedPublicAndPrivateDnsAnswersMustFailClosed() throws Exception {
        AiModelOutboundDestinationPolicy policy = new AiModelOutboundDestinationPolicy(
                host -> new InetAddress[]{
                        InetAddress.getByName("8.8.8.8"),
                        InetAddress.getByName("169.254.169.254")
                });

        assertThrows(BusinessException.class,
                () -> policy.approveBaseUrl("https://models.example.com/v1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1", "10.0.0.1", "100.64.0.1", "169.254.169.254",
            "172.16.0.1", "192.168.0.1", "198.18.0.1", "203.0.113.1",
            "::1", "fc00::1", "fe80::1", "2001:db8::1", "2002:7f00:1::"
    })
    void nonPublicAddressRangesMustBeRejected(String address) {
        AiModelOutboundDestinationPolicy policy = new AiModelOutboundDestinationPolicy(
                host -> new InetAddress[]{InetAddress.getByName(address)});

        assertThrows(BusinessException.class,
                () -> policy.approveBaseUrl("https://models.example.com/v1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://127.1/v1",
            "https://2130706433/v1",
            "https://[::1]/v1",
            "https://[::ffff:127.0.0.1]/v1"
    })
    void alternateLoopbackLiteralFormsMustNotBypassSystemResolution(String baseUrl) {
        AiModelOutboundDestinationPolicy policy = new AiModelOutboundDestinationPolicy(
                new SystemHostAddressResolver());

        assertThrows(BusinessException.class, () -> policy.approveBaseUrl(baseUrl));
    }
}
