package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;
import com.rush.rushaicodemother.service.credit.GenerationCreditReservationQuote;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppGenerationControlAdmissionPolicyTest {

    private final AppGenerationControlAdmissionPolicy policy =
            new AppGenerationControlAdmissionPolicy();

    @Test
    void pausedEmergencyAndApplicationCapacityMustBlockBeforeReservation() {
        assertThrows(BusinessException.class, () -> policy.assertMayAdmit(
                context(snapshot(true, false, 1, 0L, null), 1L)));
        assertThrows(BusinessException.class, () -> policy.assertMayAdmit(
                context(snapshot(false, true, 1, 0L, null), 1L)));
        assertThrows(BusinessException.class, () -> policy.assertMayAdmit(
                context(snapshot(false, false, 1, 0L, null, 1), 1L)));
        assertDoesNotThrow(() -> policy.assertMayAdmit(
                context(snapshot(false, false, 1, 0L, null), 1L)));
    }

    @Test
    void appBudgetMustIncludeTheNewWorstCaseReservationForAdmitAndPreflight() {
        GenerationTaskAdmissionSnapshot exhausted = snapshot(false, false, 1, 9L, 10L);

        assertThrows(BusinessException.class,
                () -> policy.assertMayAdmit(context(exhausted, 2L)));
        assertThrows(BusinessException.class,
                () -> policy.assertMayPreflight(preflightContext(exhausted, 2L)));
        assertDoesNotThrow(() -> policy.assertMayAdmit(
                context(snapshot(false, false, 1, 8L, 10L), 2L)));
    }

    private GenerationTaskAdmissionSnapshot snapshot(boolean paused,
                                                     boolean emergencyStopped,
                                                     int maxConcurrentTasks,
                                                     long appUsage,
                                                     Long appLimit) {
        return snapshot(paused, emergencyStopped, maxConcurrentTasks, appUsage, appLimit, 0);
    }

    private GenerationTaskAdmissionSnapshot snapshot(boolean paused,
                                                     boolean emergencyStopped,
                                                     int maxConcurrentTasks,
                                                     long appUsage,
                                                     Long appLimit,
                                                     int appTasks) {
        return new GenerationTaskAdmissionSnapshot(
                0, appTasks, 0, 0, 0L,
                paused, emergencyStopped, maxConcurrentTasks, appUsage, appLimit);
    }

    private GenerationTaskAdmissionContext context(GenerationTaskAdmissionSnapshot snapshot,
                                                   long reservedCredit) {
        Instant now = Instant.parse("2026-08-28T00:00:00Z");
        GenerationTaskCommand command = new GenerationTaskCommand(
                GenerationTaskCommand.CURRENT_SCHEMA_VERSION,
                "task-1", 11L, 7L, 100L, "修改项目", CodeGenTypeEnum.VUE_PROJECT,
                GenerationMode.AGENT_EDIT, 0.9, "测试", FallbackPolicy.NONE,
                ExpectedValidationLevel.BUILD, "", now, now.plusSeconds(600));
        return new GenerationTaskAdmissionContext(command, snapshot,
                new GenerationCreditReservationQuote(100_000L, reservedCredit, "v1"));
    }

    private GenerationTaskPreflightAdmissionContext preflightContext(
            GenerationTaskAdmissionSnapshot snapshot,
            long reservedCredit) {
        return new GenerationTaskPreflightAdmissionContext(
                100L, 7L, CodeGenTypeEnum.VUE_PROJECT, IntentProfile.unknown(), snapshot,
                new GenerationCreditReservationQuote(100_000L, reservedCredit, "v1"));
    }
}
