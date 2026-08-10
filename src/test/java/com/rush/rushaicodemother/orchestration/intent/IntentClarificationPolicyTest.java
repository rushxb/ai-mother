package com.rush.rushaicodemother.orchestration.intent;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 澄清门禁回归。
 *
 * <p>门禁的价值全在"什么时候不调模型"：只要它对大多数请求返回 true，
 * 澄清就退化成每请求固定成本。用真实提示词固定住这条边界。</p>
 */
class IntentClarificationPolicyTest {

    private final IntentProfileService service = new IntentProfileService();

    @Test
    void multiDimensionAmbiguousEditShouldRequireClarification() {
        // 本地把"做得好看点"误判成鉴权改造，操作类型与复杂度都只有兜底值，值得澄清。
        IntentProfile profile = analyze("把登录页做得好看点", true);

        assertTrue(profile.ambiguitySignal().unresolved(IntentResolutionDimension.OPERATION_TYPE));
        assertTrue(profile.ambiguitySignal().unresolved(IntentResolutionDimension.SEMANTIC_COMPLEXITY));
        assertTrue(IntentClarificationPolicy.requiresClarification(profile));
    }

    @Test
    void keywordResolvedEditShouldNotRequireClarification() {
        IntentProfile profile = analyze("帮我把首页的按钮颜色改成蓝色", true);

        assertFalse(profile.ambiguitySignal().ambiguous());
        assertFalse(IntentClarificationPolicy.requiresClarification(profile));
    }

    @Test
    void tooShortPromptShouldNotRequireClarification() {
        // 提示词本身没有信息，模型澄清同样是猜测，不值得付费。
        IntentProfile profile = analyze("优化一下", true);

        assertTrue(profile.ambiguitySignal().ambiguous());
        assertTrue(profile.ambiguitySignal().shortPrompt());
        assertFalse(IntentClarificationPolicy.requiresClarification(profile));
    }

    @Test
    void firstGenerationShouldNotRequireClarification() {
        // CREATE 由工作区状态唯一决定，澄清改变不了路由结果。
        IntentProfile profile = analyze("做一个不错的网站", false);

        assertEquals(IntentOperationType.CREATE, profile.operationType());
        assertFalse(IntentClarificationPolicy.requiresClarification(profile));
    }

    @Test
    void singleUnresolvedDimensionShouldNotRequireClarification() {
        IntentProfile profile = new IntentProfile(
                IntentOperationType.EDIT,
                Set.of(IntentAffectedScope.FRONTEND),
                IntentSemanticComplexity.MEDIUM,
                false,
                false,
                IntentDestructiveRisk.LOW,
                3,
                IntentValidationRisk.LOW,
                0.8,
                new IntentAmbiguitySignal(
                        Set.of(IntentResolutionDimension.EXPECTED_FILE_COUNT), false, false)
        );

        assertTrue(profile.ambiguitySignal().ambiguous());
        assertFalse(IntentClarificationPolicy.requiresClarification(profile),
                "单一维度兜底不足以支撑澄清成本");
    }

    @Test
    void nullProfileShouldNotRequireClarification() {
        assertFalse(IntentClarificationPolicy.requiresClarification(null));
    }

    private IntentProfile analyze(String message, boolean workspaceExists) {
        App app = App.builder()
                .id(10L)
                .userId(20L)
                .codeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue())
                .build();
        User user = User.builder().id(20L).build();
        return service.analyze(
                new GenerationTaskRequest(app, message, user),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace(workspaceExists));
    }

    private GenerationWorkspace workspace(boolean exists) {
        Path root = Path.of("target/test-clarification-workspace");
        return new GenerationWorkspace(
                10L,
                CodeGenTypeEnum.VUE_PROJECT,
                root,
                root,
                exists,
                root,
                root,
                Set.of(),
                Set.of()
        );
    }
}
