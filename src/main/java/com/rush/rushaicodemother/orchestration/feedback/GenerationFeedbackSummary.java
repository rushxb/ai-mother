package com.rush.rushaicodemother.orchestration.feedback;

/** 路由和产品质量策略使用的有界反馈聚合。 */
public record GenerationFeedbackSummary(
        int feedbackCount,
        int lowRatingCount,
        double averageRating
) {

    public static GenerationFeedbackSummary empty() {
        return new GenerationFeedbackSummary(0, 0, 0.0);
    }
}
