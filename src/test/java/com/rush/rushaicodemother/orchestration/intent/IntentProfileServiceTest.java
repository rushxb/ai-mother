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
        assertTrue(IntentProfile.class.getRecordComponents().length == 9,
                "意图画像不得保存原始提示词或其截断副本");
    }
    private GenerationTaskRequest request(String message) {
        App app = App.builder()
                .id(10L)
                .userId(20L)
                .codeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue())
                .build();
        User user = User.builder().id(20L).build();
        return new GenerationTaskRequest(app, message, user);
    }

    private GenerationWorkspace workspace(boolean exists) {
        Path root = Path.of("target/test-intent-workspace");
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