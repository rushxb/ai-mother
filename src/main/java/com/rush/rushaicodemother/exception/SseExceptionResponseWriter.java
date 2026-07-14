package com.rush.rushaicodemother.exception;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Writes controller-layer failures as Server-Sent Events protocol frames.
 *
 * <p>This component owns transport detection and response writing only. The global exception
 * handler remains responsible for selecting the public error code and message.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SseExceptionResponseWriter {

    private static final String CHAT_STREAM_PATH = "/app/chat/gen/code";
    private static final String BUSINESS_ERROR_EVENT = "business-error";
    private static final String DONE_EVENT = "done";

    private final ObjectMapper objectMapper;

    /**
     * Writes an error and terminal event when the current request uses SSE.
     *
     * @return {@code true} when the response was handled as SSE and no JSON body should be written
     */
    public boolean writeIfApplicable(HttpServletRequest request,
                                     HttpServletResponse response,
                                     int errorCode,
                                     String errorMessage) {
        if (!isSseRequest(request)) {
            return false;
        }
        if (response == null) {
            log.warn("SSE exception response is unavailable for {}", request.getRequestURI());
            return false;
        }

        String safeMessage = StringUtils.hasText(errorMessage)
                ? errorMessage.trim()
                : ErrorCode.OPERATION_ERROR.getMessage();
        try {
            configureResponse(response);
            PrintWriter writer = response.getWriter();
            writeEvent(writer, BUSINESS_ERROR_EVENT,
                    objectMapper.writeValueAsString(new SseErrorPayload(true, errorCode, safeMessage)));
            writeEvent(writer, DONE_EVENT, "{}");
            writer.flush();
            return true;
        } catch (IOException | IllegalStateException exception) {
            // A disconnected client cannot receive a fallback JSON body after SSE output starts.
            log.warn("Failed to write SSE exception response for {}: {}",
                    request.getRequestURI(), LogExceptionSanitizer.sanitizeMessage(exception));
            return true;
        }
    }

    private boolean isSseRequest(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String acceptHeader = request.getHeader(HttpHeaders.ACCEPT);
        if (acceptHeader != null
                && acceptHeader.toLowerCase(Locale.ROOT).contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            return true;
        }
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        if (CHAT_STREAM_PATH.equals(request.getServletPath())) {
            return true;
        }
        String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
        return (contextPath + CHAT_STREAM_PATH).equals(request.getRequestURI());
    }

    private void configureResponse(HttpServletResponse response) {
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
    }

    private void writeEvent(PrintWriter writer, String eventName, String data) {
        writer.write("event: ");
        writer.write(eventName);
        writer.write('\n');
        writer.write("data: ");
        writer.write(data);
        writer.write("\n\n");
    }

    private record SseErrorPayload(boolean error, int code, String message) {
    }
}
