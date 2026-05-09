package dev.langchain4j.http.client;

import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import dev.langchain4j.model.openai.internal.ResponseHandle;

/**
 * Extension for HTTP clients that can cancel an active streaming request.
 */
public interface CancellableHttpClient extends HttpClient {

    ResponseHandle executeCancellable(HttpRequest request,
                                      ServerSentEventParser parser,
                                      ServerSentEventListener listener);
}
