package com.rush.rushaicodemother.architecture;

import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseMutation;
import com.rush.rushaicodemother.infrastructure.persistence.aimodel.MyBatisAiModelSecretMigrationRepository;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceEnvelopeRequest;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceEnvelopeService;
import com.rush.rushaicodemother.service.aimodel.AiModelProtectedSecret;
import com.rush.rushaicodemother.service.aimodel.DefaultAiModelManagementService;
import com.rush.rushaicodemother.service.prompt.PromptReleaseTransactionCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AiReleaseCoordinationTransactionArchitectureTest {

    @Test
    void coordinationLockCallersMustDeclareWritableTransactions() throws Exception {
        List<Method> guardedMethods = List.of(
                PromptReleaseTransactionCoordinator.class.getMethod(
                        "mutate", PromptReleaseMutation.class),
                DefaultAiModelManagementService.class.getMethod("deleteModel", long.class),
                DefaultAiModelManagementService.class.getMethod(
                        "toggleModelEnabled", long.class, String.class, long.class),
                MyBatisAiModelSecretMigrationRepository.class.getMethod(
                        "replaceIfCurrent", long.class, String.class, AiModelProtectedSecret.class),
                MyBatisAiModelSecretMigrationRepository.class.getMethod("clearDeleted", long.class),
                GenerationBenchmarkEvidenceEnvelopeService.class.getMethod(
                        "create", GenerationBenchmarkEvidenceEnvelopeRequest.class)
        );

        for (Method method : guardedMethods) {
            Transactional transactional = method.getAnnotation(Transactional.class);
            assertNotNull(transactional, () -> "发布协调锁调用方法缺少事务: " + method);
            assertFalse(transactional.readOnly(), () -> "发布协调锁不能运行在只读事务中: " + method);
        }
    }
}
