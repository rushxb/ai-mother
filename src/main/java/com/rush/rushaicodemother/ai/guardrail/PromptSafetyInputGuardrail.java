package com.rush.rushaicodemother.ai.guardrail;

import com.rush.rushaicodemother.constant.AppConstant;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailResult;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Prompt 安全审查护轨
 */
public class PromptSafetyInputGuardrail implements InputGuardrail {

    // 敏感词列表
    private static final List<String> SENSITIVE_WORDS = Arrays.asList(
            "忽略之前的指令", "ignore previous instructions", "ignore above",
            "破解", "hack", "绕过", "bypass", "越狱", "jailbreak"
    );

    private static final List<String> TRUSTED_INTERNAL_PREFIXES = Arrays.asList(
            "【自动修复任务】",
            "【CREATE 构建修复任务】"
    );

    // 注入攻击模式
    private static final List<Pattern> INJECTION_PATTERNS = Arrays.asList(
            Pattern.compile("(?i)ignore\\s+(?:previous|above|all)\\s+(?:instructions?|commands?|prompts?)"),
            Pattern.compile("(?i)(?:forget|disregard)\\s+(?:everything|all)\\s+(?:above|before)"),
            Pattern.compile("(?i)(?:pretend|act|behave)\\s+(?:as|like)\\s+(?:if|you\\s+are)"),
            Pattern.compile("(?i)system\\s*:\\s*you\\s+are"),
            Pattern.compile("(?i)new\\s+(?:instructions?|commands?|prompts?)\\s*:")
    );

    /**
 * 校验{@code ate}是否有效。
 *
 * @param userMessage 用户消息
 * @return {@code ate}
 */
    @Override
    public InputGuardrailResult validate(UserMessage userMessage) {
        String rawInput = userMessage.singleText();
        String input = extractOriginalUserInput(rawInput);
        // 检查输入长度
        if (!isTrustedInternalPrompt(rawInput) && input.length() > 1000) {
            return fatal("输入内容过长，不要超过 1000 字");
        }
        // 检查是否为空
        if (input.trim().isEmpty()) {
            return fatal("输入内容不能为空");
        }
        // 检查敏感词
        String lowerInput = rawInput == null ? "" : rawInput.toLowerCase();
        for (String sensitiveWord : SENSITIVE_WORDS) {
            if (lowerInput.contains(sensitiveWord.toLowerCase())) {
                return fatal("输入包含不当内容，请修改后重试");
            }
        }
        // 检查注入攻击模式
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(rawInput == null ? "" : rawInput).find()) {
                return fatal("检测到恶意输入，请求被拒绝");
            }
        }
        return success();
    }

    /** 从输入中提取{@code Original}用户输入。 */
    private String extractOriginalUserInput(String input) {
        if (input == null) {
            return "";
        }
        int contextMarkerIndex = input.indexOf(AppConstant.PROJECT_CONTEXT_MARKER);
        if (contextMarkerIndex < 0) {
            return input;
        }
        return input.substring(0, contextMarkerIndex).trim();
    }

    private boolean isTrustedInternalPrompt(String input) {
        if (input == null) {
            return false;
        }
        return TRUSTED_INTERNAL_PREFIXES.stream().anyMatch(input::startsWith);
    }
} 
