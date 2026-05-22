package dev.langchain4j.model.openai.internal;

import dev.langchain4j.http.client.CancellableHttpClient;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

class StreamingRequestExecutor<Response> {

    private final HttpClient httpClient;
    private final HttpRequest streamingHttpRequest;
    private final Class<Response> responseClass;

    StreamingRequestExecutor(HttpClient httpClient, HttpRequest streamingHttpRequest, Class<Response> responseClass) {
        this.httpClient = httpClient;
        this.streamingHttpRequest = streamingHttpRequest;
        this.responseClass = responseClass;
    }

    StreamingResponseHandling onPartialResponse(Consumer<Response> partialResponseHandler) {

        return new StreamingResponseHandling() {

            @Override
            public StreamingCompletionHandling onComplete(Runnable streamingCompletionCallback) {
                return new StreamingCompletionHandling() {

                    @Override
                    public ErrorHandling onError(Consumer<Throwable> errorHandler) {
                        return () -> stream(partialResponseHandler, streamingCompletionCallback, errorHandler);
                    }

                    @Override
                    public ErrorHandling ignoreErrors() {
                        return () -> stream(partialResponseHandler, streamingCompletionCallback, ignored -> {
                        });
                    }
                };
            }

            @Override
            public ErrorHandling onError(Consumer<Throwable> errorHandler) {
                return () -> stream(partialResponseHandler, () -> {
                }, errorHandler);
            }

            @Override
            public ErrorHandling ignoreErrors() {
                return () -> stream(partialResponseHandler, () -> {
                }, ignored -> {
                });
            }
        };
    }

    private ResponseHandle stream(Consumer<Response> partialResponseHandler,
                                  Runnable streamingCompletionCallback,
                                  Consumer<Throwable> errorHandler) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicBoolean closed = new AtomicBoolean(false);
        ServerSentEventListener listener = new ServerSentEventListener() {

            @Override
            public void onEvent(ServerSentEvent event) {
                if (cancelled.get()) {
                    return;
                }
                if ("[DONE]".equals(event.data())) {
                    return;
                }
                try {
                    if ("error".equals(event.event())) {
                        errorHandler.accept(new RuntimeException(event.data()));
                        return;
                    }
                    Response response = Json.fromJson(event.data(), responseClass);
                    if (response != null && !cancelled.get()) {
                        partialResponseHandler.accept(response);
                    }
                } catch (Exception e) {
                    if (!cancelled.get()) {
                        errorHandler.accept(e);
                    }
                }
            }

            @Override
            public void onClose() {
                if (!cancelled.get() && closed.compareAndSet(false, true)) {
                    streamingCompletionCallback.run();
                }
            }

            @Override
            public void onError(Throwable t) {
                if (!cancelled.get()) {
                    errorHandler.accept(t);
                }
            }
        };

        ResponseHandle delegateHandle;
        if (httpClient instanceof CancellableHttpClient cancellableHttpClient) {
            delegateHandle = cancellableHttpClient.executeCancellable(
                    streamingHttpRequest,
                    new CancellableServerSentEventParser(cancelled),
                    listener);
        } else {
            httpClient.execute(streamingHttpRequest, new CancellableServerSentEventParser(cancelled), listener);
            delegateHandle = new ResponseHandle();
        }

        return new ResponseHandle(() -> {
            cancelled.set(true);
            delegateHandle.cancel();
        });
    }

    private static final class CancellableServerSentEventParser implements ServerSentEventParser {

        private final AtomicBoolean cancelled;

        private CancellableServerSentEventParser(AtomicBoolean cancelled) {
            this.cancelled = cancelled;
        }

        @Override
        public void parse(java.io.InputStream httpResponseBody, ServerSentEventListener listener) {
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(httpResponseBody, StandardCharsets.UTF_8))) {
                String event = null;
                StringBuilder data = new StringBuilder();
                String line;
                while (!cancelled.get() && (line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (!data.isEmpty()) {
                            listener.onEvent(new ServerSentEvent(event, data.toString()));
                            event = null;
                            data.setLength(0);
                        }
                        continue;
                    }
                    if (line.startsWith("event:")) {
                        event = line.substring("event:".length()).trim();
                    } else if (line.startsWith("data:")) {
                        String content = line.substring("data:".length());
                        if (!data.isEmpty()) {
                            data.append("\n");
                        }
                        data.append(content.trim());
                    }
                }
                if (!cancelled.get() && !data.isEmpty()) {
                    listener.onEvent(new ServerSentEvent(event, data.toString()));
                }
            } catch (java.io.IOException e) {
                if (!cancelled.get()) {
                    listener.onError(e);
                }
            }
        }
    }
}
