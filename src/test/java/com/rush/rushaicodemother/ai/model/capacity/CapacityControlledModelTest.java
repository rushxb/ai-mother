package com.rush.rushaicodemother.ai.model.capacity;

import com.rush.rushaicodemother.core.handler.GenerationCancellationAwareStreamingHandler;
import com.rush.rushaicodemother.core.handler.GenerationCancellationHandle;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CapacityControlledModelTest {

    @Test
    void synchronousModelMustReleaseCapacityOnSuccessAndFailure() {
        CountingGuard guard = new CountingGuard();
        CapacityControlledChatModel success = new CapacityControlledChatModel(
                "openai", "gpt-test", 4096,
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest request) {
                        return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
                    }
                },
                guard
        );

        assertEquals("ok", success.chat(request()).aiMessage().text());
        assertEquals(1, guard.acquired.get());
        assertEquals(1, guard.released.get());

        CapacityControlledChatModel failure = new CapacityControlledChatModel(
                "openai", "gpt-test", 4096,
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest request) {
                        throw new IllegalStateException("provider failed");
                    }
                },
                guard
        );

        assertThrows(IllegalStateException.class, () -> failure.chat(request()));
        assertEquals(2, guard.acquired.get());
        assertEquals(2, guard.released.get());
    }

    @Test
    void durableInvocationStartFailureMustPreventThePhysicalProviderCall() {
        CountingGuard guard = new CountingGuard();
        AtomicInteger providerRequests = new AtomicInteger();
        ChatModel provider = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                providerRequests.incrementAndGet();
                return ChatResponse.builder().aiMessage(AiMessage.from("unexpected")).build();
            }
        };
        ChatModelListener failingLedger = new ChatModelListener() {
            @Override
            public void onRequest(ChatModelRequestContext requestContext) {
                throw new IllegalStateException("ledger unavailable");
            }
        };
        CapacityControlledChatModel model = new CapacityControlledChatModel(
                "openai", "gpt-test", 4096, provider, guard,
                Duration.ofSeconds(30), failingLedger);

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> model.chat(request())
        );

        assertEquals("ledger unavailable", failure.getMessage());
        assertEquals(0, providerRequests.get());
        assertEquals(1, guard.released.get());
    }

    @Test
    void streamingModelMustHoldCapacityUntilTerminalCallback() {
        CountingGuard guard = new CountingGuard();
        AtomicReference<StreamingChatResponseHandler> providerHandler = new AtomicReference<>();
        StreamingChatModel delegate = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                providerHandler.set(handler);
                handler.onPartialResponse("partial");
            }
        };
        CapacityControlledStreamingChatModel model = new CapacityControlledStreamingChatModel(
                "openai", "gpt-stream", 4096, delegate, guard);
        AtomicReference<String> completed = new AtomicReference<>();

        model.chat(request(), new StreamingChatResponseHandler() {
            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                completed.set(completeResponse.aiMessage().text());
            }

            @Override
            public void onError(Throwable error) {
                throw new AssertionError(error);
            }
        });

        assertEquals(1, guard.acquired.get());
        assertEquals(0, guard.released.get());
        providerHandler.get().onCompleteResponse(
                ChatResponse.builder().aiMessage(AiMessage.from("done")).build());
        assertEquals("done", completed.get());
        assertEquals(1, guard.released.get());
    }

    @Test
    void synchronousStreamingFailureMustReleaseCapacity() {
        CountingGuard guard = new CountingGuard();
        StreamingChatModel delegate = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                throw new IllegalStateException("provider failed");
            }
        };
        CapacityControlledStreamingChatModel model = new CapacityControlledStreamingChatModel(
                "openai", "gpt-stream", 4096, delegate, guard);

        assertThrows(IllegalStateException.class, () -> model.chat(
                request(), new NoopStreamingHandler()));
        assertEquals(1, guard.acquired.get());
        assertEquals(1, guard.released.get());
    }

    @Test
    void cancellingForwardedStreamingHandleMustReleaseCapacityImmediately() {
        CountingGuard guard = new CountingGuard();
        AtomicInteger providerCancellations = new AtomicInteger();
        StreamingHandle providerHandle = new StreamingHandle() {
            @Override
            public void cancel() {
                providerCancellations.incrementAndGet();
            }

            @Override
            public boolean isCancelled() {
                return providerCancellations.get() > 0;
            }
        };
        StreamingChatModel delegate = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onPartialResponse(
                        new PartialResponse("partial"),
                        new PartialResponseContext(providerHandle));
            }
        };
        CapacityControlledStreamingChatModel model = new CapacityControlledStreamingChatModel(
                "openai", "gpt-stream", 4096, delegate, guard);
        AtomicReference<StreamingHandle> forwardedHandle = new AtomicReference<>();

        model.chat(request(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(PartialResponse partialResponse,
                                          PartialResponseContext context) {
                forwardedHandle.set(context.streamingHandle());
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
            }

            @Override
            public void onError(Throwable error) {
                throw new AssertionError(error);
            }
        });
        forwardedHandle.get().cancel();

        assertEquals(1, providerCancellations.get());
        assertEquals(1, guard.released.get());
    }

    @Test
    void cancellingForwardedStreamingHandleMustClosePhysicalInvocationLedger() {
        CountingGuard guard = new CountingGuard();
        AtomicReference<StreamingHandle> forwardedHandle = new AtomicReference<>();
        AtomicReference<Throwable> ledgerFailure = new AtomicReference<>();
        StreamingHandle providerHandle = new StreamingHandle() {
            private final AtomicBoolean cancelled = new AtomicBoolean();

            @Override
            public void cancel() {
                cancelled.set(true);
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        };
        StreamingChatModel delegate = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onPartialResponse(
                        new PartialResponse("partial"),
                        new PartialResponseContext(providerHandle));
            }
        };
        ChatModelListener invocationLedger = new ChatModelListener() {
            @Override
            public void onError(ChatModelErrorContext errorContext) {
                ledgerFailure.set(errorContext.error());
            }
        };
        CapacityControlledStreamingChatModel model = new CapacityControlledStreamingChatModel(
                "openai", "gpt-stream", 4096, delegate, guard,
                Duration.ofSeconds(30), null, null, null, null, invocationLedger);

        model.chat(request(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(PartialResponse partialResponse,
                                          PartialResponseContext context) {
                forwardedHandle.set(context.streamingHandle());
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
            }

            @Override
            public void onError(Throwable error) {
                throw new AssertionError(error);
            }
        });
        forwardedHandle.get().cancel();

        assertInstanceOf(CancellationException.class, ledgerFailure.get());
        assertEquals(1, guard.released.get());
    }

    @Test
    void logicalCancellationBeforeFirstProviderHandleMustReleaseCapacity() {
        CountingGuard guard = new CountingGuard();
        AtomicReference<StreamingChatResponseHandler> providerHandler = new AtomicReference<>();
        StreamingChatModel delegate = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                providerHandler.set(handler);
            }
        };
        CapacityControlledStreamingChatModel model = new CapacityControlledStreamingChatModel(
                "openai", "gpt-stream", 4096, delegate, guard);
        CancellationCapturingHandler downstream = new CancellationCapturingHandler();

        model.chat(request(), downstream);

        assertNotNull(providerHandler.get());
        assertNotNull(downstream.cancellation.get());
        assertEquals(0, guard.released.get());
        downstream.cancellation.get().cancel();
        assertEquals(1, guard.released.get());
    }

    @Test
    void cancellationDuringCapacityAcquisitionMustSkipTheProviderRequest() {
        CancellationCapturingHandler downstream = new CancellationCapturingHandler();
        AtomicInteger released = new AtomicInteger();
        AtomicInteger providerRequests = new AtomicInteger();
        AiModelCapacityGuard guard = (provider, modelId, maxTokens, request) -> {
            downstream.cancellation.get().cancel();
            return released::incrementAndGet;
        };
        StreamingChatModel delegate = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                providerRequests.incrementAndGet();
            }
        };
        CapacityControlledStreamingChatModel model = new CapacityControlledStreamingChatModel(
                "openai", "gpt-stream", 4096, delegate, guard);

        model.chat(request(), downstream);

        assertEquals(1, released.get());
        assertEquals(0, providerRequests.get());
    }

    @Test
    void lostStreamingLeaseMustCancelProviderAndEmitOnlyOneTerminalError() {
        LosingGuard guard = new LosingGuard();
        AtomicInteger providerCancellations = new AtomicInteger();
        AtomicReference<StreamingChatResponseHandler> providerHandler = new AtomicReference<>();
        StreamingHandle providerHandle = new StreamingHandle() {
            @Override
            public void cancel() {
                providerCancellations.incrementAndGet();
            }

            @Override
            public boolean isCancelled() {
                return providerCancellations.get() > 0;
            }
        };
        StreamingChatModel delegate = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                providerHandler.set(handler);
                handler.onPartialResponse(
                        new PartialResponse("partial"),
                        new PartialResponseContext(providerHandle));
            }
        };
        CapacityControlledStreamingChatModel model = new CapacityControlledStreamingChatModel(
                "openai", "gpt-stream", 4096, delegate, guard, Duration.ofSeconds(10));
        AtomicReference<Throwable> terminalError = new AtomicReference<>();
        AtomicInteger completions = new AtomicInteger();

        model.chat(request(), new StreamingChatResponseHandler() {
            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                completions.incrementAndGet();
            }

            @Override
            public void onError(Throwable error) {
                terminalError.set(error);
            }
        });
        guard.lose();
        providerHandler.get().onCompleteResponse(
                ChatResponse.builder().aiMessage(AiMessage.from("late")).build());

        assertEquals(Duration.ofSeconds(10), guard.upstreamTimeout.get());
        assertEquals(1, providerCancellations.get());
        assertEquals(1, guard.released.get());
        assertEquals(0, completions.get());
        assertEquals(AiModelCapacityException.class, terminalError.get().getClass());
    }

    @Test
    void synchronousResultMustBeRejectedIfItsCapacityLeaseWasLost() {
        LosingGuard guard = new LosingGuard();
        CapacityControlledChatModel model = new CapacityControlledChatModel(
                "openai",
                "gpt-test",
                4096,
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest request) {
                        guard.lose();
                        return ChatResponse.builder().aiMessage(AiMessage.from("stale")).build();
                    }
                },
                guard,
                Duration.ofSeconds(30)
        );

        assertThrows(AiModelCapacityException.class, () -> model.chat(request()));
        assertEquals(Duration.ofSeconds(30), guard.upstreamTimeout.get());
        assertEquals(1, guard.released.get());
    }

    private ChatRequest request() {
        return ChatRequest.builder().messages(UserMessage.from("hello")).build();
    }

    private static final class CountingGuard implements AiModelCapacityGuard {
        private final AtomicInteger acquired = new AtomicInteger();
        private final AtomicInteger released = new AtomicInteger();

        @Override
        public Lease acquire(String provider,
                             String modelId,
                             int configuredMaxOutputTokens,
                             ChatRequest request) {
            acquired.incrementAndGet();
            return released::incrementAndGet;
        }
    }

    private static final class LosingGuard implements AiModelCapacityGuard {
        private final AtomicReference<Duration> upstreamTimeout = new AtomicReference<>();
        private final AtomicInteger released = new AtomicInteger();
        private final AtomicReference<Runnable> lossListener = new AtomicReference<>();
        private final AtomicBoolean valid = new AtomicBoolean(true);
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public Lease acquire(String provider,
                             String modelId,
                             int configuredMaxOutputTokens,
                             ChatRequest request) {
            return lease();
        }

        @Override
        public Lease acquire(String provider,
                             String modelId,
                             int configuredMaxOutputTokens,
                             ChatRequest request,
                             Duration timeout) {
            upstreamTimeout.set(timeout);
            return lease();
        }

        private Lease lease() {
            return new Lease() {
                @Override
                public boolean isValid() {
                    return valid.get();
                }

                @Override
                public void onLost(Runnable listener) {
                    lossListener.set(listener);
                    if (!valid.get()) {
                        listener.run();
                    }
                }

                @Override
                public void close() {
                    if (closed.compareAndSet(false, true)) {
                        released.incrementAndGet();
                    }
                }
            };
        }

        private void lose() {
            if (valid.compareAndSet(true, false)) {
                Runnable listener = lossListener.get();
                if (listener != null) {
                    listener.run();
                }
            }
        }
    }

    private static final class NoopStreamingHandler implements StreamingChatResponseHandler {
        @Override
        public void onCompleteResponse(ChatResponse completeResponse) {
        }

        @Override
        public void onError(Throwable error) {
        }
    }

    private static final class CancellationCapturingHandler
            implements GenerationCancellationAwareStreamingHandler {
        private final AtomicReference<GenerationCancellationHandle> cancellation = new AtomicReference<>();

        @Override
        public void registerCancellationHandle(GenerationCancellationHandle cancellationHandle) {
            cancellation.set(cancellationHandle);
        }

        @Override
        public void onCompleteResponse(ChatResponse completeResponse) {
        }

        @Override
        public void onError(Throwable error) {
        }
    }
}
