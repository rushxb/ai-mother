package com.rush.rushaicodemother.orchestration.context.repository;

import java.util.List;

/** 已完成来源记录、敏感信息编辑、提示注入隔离和预算约束的模型上下文。 */
public record ProtectedRepositoryContextEnvelope(
        String content,
        String workspaceVersion,
        List<SourceEvidence> sources,
        int tokenBudget,
        int estimatedTokens,
        boolean redacted,
        boolean truncated,
        PromptInjectionRisk promptInjectionRisk,
        boolean outboundAllowed
) {

    public ProtectedRepositoryContextEnvelope {
        content = content == null ? "" : content;
        if (workspaceVersion == null || workspaceVersion.isBlank()) {
            throw new IllegalArgumentException("项目上下文版本不能为空");
        }
        sources = sources == null ? List.of() : List.copyOf(sources);
        if (tokenBudget <= 0 || estimatedTokens < 0 || estimatedTokens > tokenBudget) {
            throw new IllegalArgumentException("项目上下文 Token 用量超出冻结预算");
        }
        promptInjectionRisk = promptInjectionRisk == null
                ? PromptInjectionRisk.NONE : promptInjectionRisk;
    }

    public enum PromptInjectionRisk {
        NONE,
        SUSPICIOUS,
        HIGH
    }

    public enum Sensitivity {
        NORMAL,
        SENSITIVE_REDACTED
    }

    /** 单个项目文件在模型边界上的可审计来源描述，不保存原始内容或秘密。 */
    public record SourceEvidence(
            String relativePath,
            String contentFingerprint,
            int sourceChars,
            boolean truncated,
            Sensitivity sensitivity,
            PromptInjectionRisk promptInjectionRisk,
            boolean outboundAllowed
    ) {

        public SourceEvidence {
            if (relativePath == null || relativePath.isBlank()) {
                throw new IllegalArgumentException("项目上下文来源路径不能为空");
            }
            if (contentFingerprint == null || contentFingerprint.isBlank()) {
                throw new IllegalArgumentException("项目上下文来源指纹不能为空");
            }
            if (sourceChars < 0) {
                throw new IllegalArgumentException("项目上下文来源字符数不能为负数");
            }
            sensitivity = sensitivity == null ? Sensitivity.NORMAL : sensitivity;
            promptInjectionRisk = promptInjectionRisk == null
                    ? PromptInjectionRisk.NONE : promptInjectionRisk;
        }
    }
}
