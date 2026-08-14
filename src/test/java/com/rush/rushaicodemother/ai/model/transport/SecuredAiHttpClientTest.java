package com.rush.rushaicodemother.ai.model.transport;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.infrastructure.security.AiModelOutboundDestinationPolicy;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.Header;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.AsyncTaskExecutor;

import java.time.Duration;

import static com.rush.rushaicodemother.testsupport.AiModelOutboundSecurityTestFixtures.publicInternetPolicy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SecuredAiHttpClientTest {

    @Test
    void crossOriginRequestMustBeRejectedBeforeAuthorizationHeaderCanLeave() {
        CloseableHttpClient apacheClient = mock(CloseableHttpClient.class);
        SecuredAiHttpClient client = client(apacheClient);
        HttpRequest request = request("https://1.1.1.1/v1/chat/completions");

        assertThrows(BusinessException.class, () -> client.execute(request));

        verifyNoInteractions(apacheClient);
    }

    @Test
    void redirectResponseMustBeReturnedAsFailureWithoutASecondRequest() throws Exception {
        CloseableHttpClient apacheClient = mock(CloseableHttpClient.class);
        CloseableHttpResponse redirect = mock(CloseableHttpResponse.class);
        when(redirect.getCode()).thenReturn(307);
        when(apacheClient.execute(any(HttpUriRequestBase.class))).thenReturn(redirect);
        SecuredAiHttpClient client = client(apacheClient);
        HttpRequest request = request("https://8.8.8.8/v1/chat/completions");

        HttpException failure = assertThrows(HttpException.class, () -> client.execute(request));

        assertEquals(307, failure.statusCode());
        verify(apacheClient).execute(any(HttpUriRequestBase.class));
    }

    @Test
    void approvedRequestMustKeepAuthorizationButIgnoreCallerSuppliedHostHeader() throws Exception {
        CloseableHttpClient apacheClient = mock(CloseableHttpClient.class);
        CloseableHttpResponse response = mock(CloseableHttpResponse.class);
        when(response.getCode()).thenReturn(200);
        when(response.getHeaders()).thenReturn(new Header[0]);
        when(apacheClient.execute(any(HttpUriRequestBase.class))).thenReturn(response);
        SecuredAiHttpClient client = client(apacheClient);
        HttpRequest request = HttpRequest.builder()
                .method(HttpMethod.POST)
                .url("https://8.8.8.8/v1/chat/completions")
                .addHeader("Authorization", "Bearer provider-secret")
                .addHeader("Host", "attacker.example")
                .body("{}")
                .build();

        client.execute(request);

        ArgumentCaptor<HttpUriRequestBase> requestCaptor =
                ArgumentCaptor.forClass(HttpUriRequestBase.class);
        verify(apacheClient).execute(requestCaptor.capture());
        assertEquals("Bearer provider-secret",
                requestCaptor.getValue().getFirstHeader("Authorization").getValue());
        assertNull(requestCaptor.getValue().getFirstHeader("Host"));
    }

    private SecuredAiHttpClient client(CloseableHttpClient apacheClient) {
        AiModelOutboundDestinationPolicy policy = publicInternetPolicy();
        return new SecuredAiHttpClient(
                apacheClient,
                policy,
                policy.approveBaseUrl("https://8.8.8.8/v1"),
                mock(AsyncTaskExecutor.class),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5)
        );
    }

    private HttpRequest request(String url) {
        return HttpRequest.builder()
                .method(HttpMethod.POST)
                .url(url)
                .addHeader("Authorization", "Bearer provider-secret")
                .body("{}")
                .build();
    }
}
