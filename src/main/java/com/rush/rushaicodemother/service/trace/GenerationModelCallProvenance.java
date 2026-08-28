package com.rush.rushaicodemother.service.trace;

import java.util.List;

/**
 * 模型请求的内容寻址、生产安全沿袭。
 *
 * <p> 仅保留哈希值、有界计数和清理后的元数据。提示和存储库
 * 内容故意不复制到模型调用表中。</p>
 */
public record GenerationModelCallProvenance(
        String requestHash,
        String promptTemplateHash,
        String toolSchemaHash,
        String modelConfigHash,
        Integer requestMessageCount,
        Integer toolCount,
        String rawMetadataJson,
        List<GenerationPromptSelectionProvenance> promptSelections
) {

    public GenerationModelCallProvenance {
        promptSelections = GenerationPromptSelectionProvenance.canonicalize(promptSelections);
    }
}
