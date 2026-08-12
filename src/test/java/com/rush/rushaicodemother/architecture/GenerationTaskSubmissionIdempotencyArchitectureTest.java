package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationTaskSubmissionIdempotencyArchitectureTest {

    @Test
    void schemaAndMigrationMustEnforceScopedHashedSubmissionIdentity() throws Exception {
        String schema = normalize(Files.readString(Path.of("sql", "create_table.sql")));
        String baseline = normalize(Files.readString(Path.of(
                "sql", "migrations", "B20260716_5__production_schema_baseline.sql")));
        String migration = normalize(Files.readString(Path.of(
                "sql", "migrations", "V20260718_7__generation_task_submission_idempotency.sql")));

        for (String contract : new String[]{"idempotencykeyhash", "requestfingerprint"}) {
            assertTrue(schema.contains(contract));
            assertTrue(baseline.contains(contract));
            assertTrue(migration.contains(contract));
        }
        assertTrue(schema.contains("unique key uk_generation_task_submission_idempotency"));
        assertTrue(schema.contains("tenantid, userid, appid, idempotencykeyhash"));
        assertTrue(schema.contains("chk_generation_task_idempotency_pair"));
        assertTrue(migration.contains("information_schema.columns"));
        assertTrue(migration.contains("information_schema.statistics"));
        assertTrue(migration.contains("information_schema.table_constraints"));
        assertFalse(schema.contains("idempotencykey varchar"));
    }

    @Test
    void admissionMustResolveReplayBeforeMutablePreconditionsAndDispatch() throws Exception {
        String admission = normalize(Files.readString(Path.of(
                "src", "main", "java", "com", "rush", "rushaicodemother", "orchestration",
                "runtime", "task", "GenerationTaskAdmissionService.java")));
        String submission = normalize(Files.readString(Path.of(
                "src", "main", "java", "com", "rush", "rushaicodemother", "orchestration",
                "runtime", "task", "GenerationTaskSubmissionService.java")));

        assertOrdered(admission,
                "lockscopeandmeasure",
                "findbyidempotencykey",
                "ensuregenerationmodelsconfigured",
                "assertmayadmit",
                "reservegenerationtask",
                "runtimelifecycleservice.submit");
        assertTrue(submission.contains("if (admission.created())"));
        assertTrue(submission.contains("taskdispatcher.dispatch(admission.taskid())"));
    }

    private void assertOrdered(String source, String... tokens) {
        int previous = -1;
        for (String token : tokens) {
            int current = source.indexOf(token);
            assertTrue(current > previous, "missing or out-of-order contract: " + token);
            previous = current;
        }
    }

    private String normalize(String value) {
        return value.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
