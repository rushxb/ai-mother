package com.rush.rushaicodemother.service.impl;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.orchestration.context.StructureAwareContextCompressor;
import com.rush.rushaicodemother.service.GenerationContextCompressionService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 生成上下文压缩服务实现。
 *
 * <p>本类只负责「规范化 + 分配预算」，具体取舍委托给
 * {@link StructureAwareContextCompressor} 按语法块完成，因此新增结构类别
 * 无需改动本类。</p>
 */
@Service
public class GenerationContextCompressionServiceImpl implements GenerationContextCompressionService {

    private static final int PROJECT_CONTEXT_BUDGET = 7000;
    private static final int FINAL_PROMPT_BUDGET = 18000;
    private static final int LONG_LINE_BUDGET = 900;

    private final StructureAwareContextCompressor compressor;

    public GenerationContextCompressionServiceImpl(StructureAwareContextCompressor compressor) {
        this.compressor = Objects.requireNonNull(compressor, "结构感知压缩器不能为空");
    }

    @Override
    public String compressProjectContext(String context) {
        return compress(context, PROJECT_CONTEXT_BUDGET);
    }

    @Override
    public String compressFinalPrompt(String prompt) {
        return compress(prompt, FINAL_PROMPT_BUDGET);
    }

    /** 规范化后按结构感知策略压缩到字符预算内。 */
    private String compress(String value, int maxChars) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        String normalized = normalize(value);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return compressor.compress(normalized, maxChars);
    }

    /** 规范化生成上下文压缩服务{@code Impl}。 */
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
