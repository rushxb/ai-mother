package com.rush.rushaicodemother.service.trace;

/**
 * Content-addressed, production-safe lineage for a model request.
 *
 * <p>Only hashes, bounded counts and sanitized metadata are persisted. Prompt and repository
 * contents are deliberately not duplicated into the model-call table.</p>
 */
public record GenerationModelCallProvenance(
        String requestHash,
        String promptTemplateHash,
        String toolSchemaHash,
        String modelConfigHash,
        Integer requestMessageCount,
        Integer toolCount,
        String rawMetadataJson
) {
}
