package com.yupi.yuaicodemother.orchestration.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueSecurityReviewServiceTest {

    private final VueSecurityReviewService service = new VueSecurityReviewService();

    @Test
    void reviewShouldBlockUnsanitizedMarkdownHtmlRendering() {
        String content = "const renderedContent = marked.parse(markdown); <div v-html=\"renderedContent\"></div>";

        VueSecurityReviewService.SecurityReviewResult result = service.review(content);

        assertFalse(result.passed());
        assertTrue(result.blockers().getFirst().contains("sanitize"));
    }

    @Test
    void reviewShouldAllowSanitizedMarkdownHtmlRendering() {
        String content = "const renderedContent = DOMPurify.sanitize(marked.parse(markdown)); <div v-html=\"renderedContent\"></div>";

        VueSecurityReviewService.SecurityReviewResult result = service.review(content);

        assertTrue(result.passed());
    }

    @Test
    void reviewShouldWarnTargetBlankWithoutRel() {
        String content = "<a href=\"https://example.com\" target=\"_blank\">link</a>";

        VueSecurityReviewService.SecurityReviewResult result = service.review(content);

        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("target")));
    }
}
