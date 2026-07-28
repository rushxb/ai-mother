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
 * 将控制器层故障写入服务器发送事件协议帧。
 *
 * <p>该组件仅拥有传输检测和响应写入。全局异常
 * 处理程序仍然负责选择公共错误代码和消息。</p>
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
     * 当前请求使用 SSE 时写入错误和终止事件。
     *
     * @return {@code true} 当响应作为 SSE 处理且不应写入 JSON 主体时
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
            // SSE 输出开始后，断开连接的客户端无法接收后备 JSON 正文。
            log.warn("Failed to write SSE exception response for {}: {}",
                    request.getRequestURI(), LogExceptionSanitizer.sanitizeMessage(exception));
            return true;
        }
    }

    /** 判断 SSE 请求是否满足约束。 */
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
