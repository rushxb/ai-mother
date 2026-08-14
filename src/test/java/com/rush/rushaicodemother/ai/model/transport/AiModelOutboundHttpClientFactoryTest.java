package com.rush.rushaicodemother.ai.model.transport;

import com.rush.rushaicodemother.exception.BusinessException;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import org.junit.jupiter.api.Test;

import static com.rush.rushaicodemother.testsupport.AiModelOutboundSecurityTestFixtures.publicInternetPolicy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class AiModelOutboundHttpClientFactoryTest {

    @Test
    void clientMustRejectCrossOriginRequestBeforeSendingProviderSecret() {
        try (AiModelOutboundHttpClientFactory factory = new AiModelOutboundHttpClientFactory(
                publicInternetPolicy(),
                mock(CancellableAiStreamingRequestExecutor.class))) {
            var client = factory.builderFor("https://8.8.8.8/v1").build();
            HttpRequest request = HttpRequest.builder()
                    .method(HttpMethod.POST)
                    .url("https://1.1.1.1/v1/chat/completions")
                    .addHeader("Authorization", "Bearer provider-secret")
                    .body("{}")
                    .build();

            assertThrows(BusinessException.class, () -> client.execute(request));
        }
    }
}
