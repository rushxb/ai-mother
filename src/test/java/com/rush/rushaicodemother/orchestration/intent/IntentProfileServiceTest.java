package com.rush.rushaicodemother.orchestration.intent;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.learning.IntentProfileRoutingDecisionEngine;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentProfileServiceTest {

    private final IntentProfileService service = new IntentProfileService();

    @Test
    void smallFrontendCopyEditShouldProduceLowRiskProfile() {
        IntentProfile profile = service.analyze(
                request("Please 修改首页 title 文案和按钮 color"),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace(true)
        );

        assertEquals(IntentOperationType.EDIT, profile.operationType());
        assertEquals(IntentSemanticComplexity.LOW, profile.semanticComplexity());
        assertTrue(profile.affectedScopes().contains(IntentAffectedScope.FRONTEND));
        assertFalse(profile.requiresBackend());
        assertFalse(profile.requiresDatabase());
        assertEquals(IntentDestructiveRisk.LOW, profile.destructiveRisk());
        assertTrue(profile.expectedFileCount() <= 2);
        assertEquals(IntentValidationRisk.LOW, profile.validationRisk());
        assertTrue(profile.confidence() >= 0.8);
    }

    @Test
    void ambiguousEditInExistingBackendProjectMustRetainBackendValidationFloor() {
        IntentProfile profile = service.analyze(
                request("把标题改成订单中心", CodeGenTypeEnum.BACKEND_PROJECT),
                CodeGenTypeEnum.BACKEND_PROJECT,
                workspace(CodeGenTypeEnum.BACKEND_PROJECT, true)
        );

        assertEquals(IntentOperationType.EDIT, profile.operationType());
        assertTrue(profile.affectedScopes().contains(IntentAffectedScope.BACKEND));
        assertFalse(profile.affectedScopes().contains(IntentAffectedScope.FRONTEND));
        assertTrue(profile.requiresBackend());
        assertEquals(IntentValidationRisk.MEDIUM, profile.validationRisk());
        assertBuildValidatedAgentRoute(profile);
    }

    @Test
    void ambiguousEditInExistingFullStackProjectMustCoverBothProjectSides() {
        IntentProfile profile = service.analyze(
                request("把标题改成订单中心", CodeGenTypeEnum.FULL_STACK_PROJECT),
                CodeGenTypeEnum.FULL_STACK_PROJECT,
                workspace(CodeGenTypeEnum.FULL_STACK_PROJECT, true)
        );

        assertTrue(profile.affectedScopes().containsAll(Set.of(
                IntentAffectedScope.FRONTEND,
                IntentAffectedScope.BACKEND
        )));
        assertTrue(profile.requiresBackend());
        assertEquals(IntentValidationRisk.MEDIUM, profile.validationRisk());
        assertBuildValidatedAgentRoute(profile);
    }

    @Test
    void crossLayerAuthenticationChangeShouldProduceHighRiskProfile() {
        IntentProfile profile = service.analyze(
                request("新增用户权限管理，前后端 API、数据库表和登录鉴权都要同步"),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace(true)
        );

        assertEquals(IntentOperationType.EDIT, profile.operationType());
        assertEquals(IntentSemanticComplexity.HIGH, profile.semanticComplexity());
        assertTrue(profile.affectedScopes().containsAll(Set.of(
                IntentAffectedScope.FRONTEND,
                IntentAffectedScope.API,
                IntentAffectedScope.DATABASE,
                IntentAffectedScope.AUTHENTICATION
        )));
        assertTrue(profile.requiresBackend());
        assertTrue(profile.requiresDatabase());
        assertTrue(profile.expectedFileCount() >= 6);
        assertEquals(IntentValidationRisk.HIGH, profile.validationRisk());
    }

    @Test
    void workspaceStateAndRepairKeywordsShouldDetermineOperationType() {
        IntentProfile createProfile = service.analyze(
                request("创建一个商品展示网站"),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace(false)
        );
        IntentProfile repairProfile = service.analyze(
                request("Fix 登录接口报错并补充回归测试"),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace(true)
        );
        IntentProfile explainProfile = service.analyze(
                request("请解释 why 这个组件会重复渲染"),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace(true)
        );

        assertEquals(IntentOperationType.CREATE, createProfile.operationType());
        assertEquals(IntentOperationType.REPAIR, repairProfile.operationType());
        assertEquals(IntentOperationType.EXPLAIN, explainProfile.operationType());
        assertTrue(repairProfile.affectedScopes().contains(IntentAffectedScope.TESTING));
    }

    @Test
    void explicitExplanationShouldTakePriorityOverMentionedFailureSymptoms() {
        IntentProfile profile = service.analyze(
                request("Please explain why this component has a render error"),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace(true)
        );

        assertEquals(IntentOperationType.EXPLAIN, profile.operationType());
    }

    @Test
    void explicitAuditAndPlanRequestsMustRemainReadOnlyOperations() {
        IntentProfile auditProfile = service.analyze(
                request("审计当前鉴权链路的安全风险，不要修改代码"),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace(true)
        );
        IntentProfile planProfile = service.analyze(
                request("先给出数据库迁移方案，不要实现"),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace(true)
        );

        assertEquals(IntentOperationType.AUDIT, auditProfile.operationType());
        assertEquals(IntentOperationType.PLAN, planProfile.operationType());
    }

    @Test
    void negatedActionsAndResourcesShouldNotExpandTheExecutionIntent() {
        IntentProfile profile = service.analyze(
                request("不要修改数据库，也不要删除数据，只解释 why 页面报错"),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace(true)
        );

        assertEquals(IntentOperationType.EXPLAIN, profile.operationType());
        assertFalse(profile.requiresDatabase());
        assertFalse(profile.requiresBackend());
        assertEquals(IntentDestructiveRisk.LOW, profile.destructiveRisk());
        assertTrue(profile.affectedScopes().contains(IntentAffectedScope.FRONTEND));
    }

    @Test
    void englishFeatureKeywordsShouldRespectWordBoundaries() {
        IntentProfile profile = service.analyze(
                request("Please explain the rapid repaint behavior"),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace(true)
        );

        assertEquals(IntentOperationType.EXPLAIN, profile.operationType());
        assertFalse(profile.affectedScopes().contains(IntentAffectedScope.API));
        assertFalse(profile.requiresBackend());
    }

    @Test
    void englishNegationShouldExcludeForbiddenResourcesAndActions() {
        IntentProfile profile = service.analyze(
                request("Do not modify the database; explain the page behavior"),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace(true)
        );

        assertEquals(IntentOperationType.EXPLAIN, profile.operationType());
        assertFalse(profile.requiresDatabase());
        assertFalse(profile.requiresBackend());
        assertEquals(IntentDestructiveRisk.LOW, profile.destructiveRisk());
    }

    @Test
    void uncertaintyExpressionsShouldNotBeMisreadAsResourceNegation() {
        IntentProfile englishProfile = service.analyze(
                request("I am not sure why the database error happens"),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace(true)
        );
        IntentProfile chineseProfile = service.analyze(
                request("我不确定数据库报错的原因，请解释"),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace(true)
        );

        assertTrue(englishProfile.requiresDatabase());
        assertTrue(chineseProfile.requiresDatabase());
        assertEquals(IntentOperationType.EXPLAIN, englishProfile.operationType());
        assertEquals(IntentOperationType.EXPLAIN, chineseProfile.operationType());
    }

    @Test
    void lexicalRuleVersionShouldBeStableAndRecordable() {
        assertEquals("intent-lexical/1.1.0", service.lexicalRuleVersion());
    }

    @Test
    void blankAndOversizedPromptsShouldRemainBoundedAndSafe() {
        IntentProfile blankProfile = service.analyze(
                request("   "),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace(true)
        );
        String oversizedPrompt = "普通描述".repeat(8_000) + " database API 登录权限";
        IntentProfile oversizedProfile = service.analyze(
                request(oversizedPrompt),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace(true)
        );

        assertEquals(IntentProfile.unknown(), blankProfile);
        assertTrue(oversizedProfile.requiresBackend());
        assertTrue(oversizedProfile.requiresDatabase());
        assertTrue(oversizedProfile.affectedScopes().contains(IntentAffectedScope.AUTHENTICATION));
        // 直接断言"没有任何文本类型字段"，而不是钉住字段数量：
        // 数量断言会被任何合法新增字段误伤，却挡不住把某个字段悄悄换成提示词副本。
        List<String> textComponents = Arrays.stream(IntentProfile.class.getRecordComponents())
                .filter(component -> CharSequence.class.isAssignableFrom(component.getType()))
                .map(RecordComponent::getName)
                .toList();
        assertTrue(textComponents.isEmpty(),
                "意图画像不得保存原始提示词或其截断副本，违规字段: " + textComponents);
    }

    private GenerationTaskRequest request(String message) {
        return request(message, CodeGenTypeEnum.VUE_PROJECT);
    }

    private GenerationTaskRequest request(String message, CodeGenTypeEnum codeGenType) {
        App app = App.builder()
                .id(10L)
                .userId(20L)
                .codeGenType(codeGenType.getValue())
                .build();
        User user = User.builder().id(20L).build();
        return new GenerationTaskRequest(app, message, user);
    }

    private GenerationWorkspace workspace(boolean exists) {
        return workspace(CodeGenTypeEnum.VUE_PROJECT, exists);
    }

    private GenerationWorkspace workspace(CodeGenTypeEnum codeGenType, boolean exists) {
        Path root = Path.of("target/test-intent-workspace");
        return new GenerationWorkspace(
                10L,
                codeGenType,
                root,
                root,
                exists,
                root,
                root,
                Set.of(),
                Set.of()
        );
    }

    private void assertBuildValidatedAgentRoute(IntentProfile profile) {
        GenerationModeDecision decision = new IntentProfileRoutingDecisionEngine().decide(profile);
        assertEquals(GenerationMode.AGENT_EDIT, decision.mode());
        assertEquals(ExpectedValidationLevel.BUILD, decision.expectedValidationLevel());
    }
}
