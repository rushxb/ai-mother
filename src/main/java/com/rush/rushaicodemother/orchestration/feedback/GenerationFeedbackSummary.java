package com.rush.rushaicodemother.orchestration.feedback;

/** Bounded feedback aggregate used by routing and product-quality policies. */
public record GenerationFeedbackSummary(
        int feedbackCount,
        int lowRatingCount,
        double averageRating
) {

    public static GenerationFeedbackSummary empty() {
        return new GenerationFeedbackSummary(0, 0, 0.0);
    }
}
