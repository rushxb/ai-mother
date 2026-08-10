package com.rush.rushaicodemother.service;

/**
 * 在向 AI 模型发送请求之前控制提示上下文预算。
 *
 * <p>记忆上下文不在本契约内：它由 {@code AiContextPackBudgeter} 在装配期按 token 预算收口，
 * 若在此处再按字符预算压一遍，等于让同一段文本受两套互不知晓的预算约束。</p>
 */
public interface GenerationContextCompressionService {

    String compressProjectContext(String context);

    String compressFinalPrompt(String prompt);
}
