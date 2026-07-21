package com.rush.rushaicodemother.orchestration.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiContextRelevanceScorerTest {

    private final AiContextRelevanceScorer scorer = new AiContextRelevanceScorer();

    @Test
    void chineseTaskContinuityScoresAboveUnrelatedHistory() {
        String query = "给登录页面增加手机号验证码登录";
        String related = "上一轮已经完成登录页面的手机号验证码表单和倒计时按钮";
        String unrelated = "优化数据报表导出和库存图表颜色";

        double relatedScore = scorer.score(query, related);
        double unrelatedScore = scorer.score(query, unrelated);

        assertTrue(relatedScore > unrelatedScore);
        assertTrue(scorer.related(query, related));
        assertFalse(scorer.related(query, unrelated));
    }

    @Test
    void normalizationMatchesFullWidthLatinAndContainedText() {
        double score = scorer.score("ＦＩＸ Login Button", "please fix login button state now");

        assertTrue(score >= 0.85);
    }
}
