package com.rush.rushaicodemother.service.impl;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.service.GenerationContextCompressionService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 生成上下文压缩服务实现。
 */
@Service
public class GenerationContextCompressionServiceImpl implements GenerationContextCompressionService {

    private static final int MEMORY_CONTEXT_BUDGET = 3200;
    private static final int PROJECT_CONTEXT_BUDGET = 7000;
    private static final int FINAL_PROMPT_BUDGET = 18000;
    private static final int LONG_LINE_BUDGET = 900;

    @Override
    public String compressMemoryContext(String context) {
        return compress(context, MEMORY_CONTEXT_BUDGET);
    }

    @Override
    public String compressProjectContext(String context) {
        return compress(context, PROJECT_CONTEXT_BUDGET);
    }

    @Override
    public String compressFinalPrompt(String prompt) {
        return compress(prompt, FINAL_PROMPT_BUDGET);
    }

    private String compress(String value, int maxChars) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        String normalized = normalize(value);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        int headBudget = Math.max(maxChars * 2 / 3, 1);
        int tailBudget = Math.max(maxChars - headBudget - 160, 0);
        String head = normalized.substring(0, Math.min(headBudget, normalized.length())).trim();
        String tail = tailBudget <= 0
                ? ""
                : normalized.substring(Math.max(headBudget, normalized.length() - tailBudget)).trim();
        if (StrUtil.isBlank(tail)) {
            return head + "\n\n[上下文已自动压缩，省略 " + (normalized.length() - head.length()) + " 个字符]";
        }
        return head
                + "\n\n[上下文已自动压缩，省略中间 "
                + Math.max(0, normalized.length() - head.length() - tail.length())
                + " 个字符]\n\n"
                + tail;
    }

    private String normalize(String value) {
        List<String> lines = new ArrayList<>();
        for (String rawLine : value.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = rawLine.stripTrailing();
            if (StrUtil.isBlank(line)) {
                if (!lines.isEmpty() && StrUtil.isNotBlank(lines.get(lines.size() - 1))) {
                    lines.add("");
                }
                continue;
            }
            lines.add(limitLongLine(line));
        }
        while (!lines.isEmpty() && StrUtil.isBlank(lines.get(lines.size() - 1))) {
            lines.remove(lines.size() - 1);
        }
        return String.join("\n", lines).trim();
    }

    private String limitLongLine(String line) {
        if (line.length() <= LONG_LINE_BUDGET) {
            return line;
        }
        return line.substring(0, LONG_LINE_BUDGET)
                + " ...[单行过长已截断 "
                + (line.length() - LONG_LINE_BUDGET)
                + " 字符]";
    }
}
