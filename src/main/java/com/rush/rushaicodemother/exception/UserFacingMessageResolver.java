package com.rush.rushaicodemother.exception;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

/**
 * 统一收敛返回给用户的异常与校验文案。
 *
 * <p>内部异常允许保留供应商、协议或基础设施的英文诊断信息，但这些信息不得直接穿透
 * HTTP/SSE 边界。候选文案不包含中文时，统一退回调用方提供的稳定中文消息。</p>
 */
@Component
public final class UserFacingMessageResolver {

    private static final Pattern CHINESE_CHARACTER = Pattern.compile("[\\u3400-\\u9FFF]");
    private static final String DEFAULT_FALLBACK = "操作失败，请稍后重试";

    /**
     * 解析可安全返回给用户的中文文案。
     *
     * @param candidate 内部候选文案
     * @param fallback 稳定的业务兜底文案
     * @return 包含中文的用户可见文案
     */
    public String resolve(String candidate, String fallback) {
        String normalizedFallback = normalizeFallback(fallback);
        if (!StringUtils.hasText(candidate)) {
            return normalizedFallback;
        }
        String normalizedCandidate = candidate.trim();
        return containsChinese(normalizedCandidate) ? normalizedCandidate : normalizedFallback;
    }

    /** 判断文案是否至少包含一个中文字符。 */
    public boolean containsChinese(String message) {
        return StringUtils.hasText(message)
                && CHINESE_CHARACTER.matcher(message).find();
    }

    private String normalizeFallback(String fallback) {
        if (!StringUtils.hasText(fallback)) {
            return DEFAULT_FALLBACK;
        }
        String normalized = fallback.trim();
        return containsChinese(normalized) ? normalized : DEFAULT_FALLBACK;
    }
}