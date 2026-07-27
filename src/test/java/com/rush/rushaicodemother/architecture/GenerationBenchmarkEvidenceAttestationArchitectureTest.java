package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationBenchmarkEvidenceAttestationArchitectureTest {

    @Test
    void schemaAndMigrationMustPersistTheSignedCandidateInvocationAttestation()
            throws Exception {
        String schema = normalize(Files.readString(Path.of("sql/create_table.sql")));
        String migration = normalize(Files.readString(Path.of(
                "sql/migrations/"
                        + "V20260723_1__generation_benchmark_candidate_invocation_attestation.sql")));

        for (String contract : new String[]{
                "signatureversion",
                "candidatephysicalrequestcount",
                "signatureversion = 1 and candidatephysicalrequestcount = 0",
                "subjecttype = 'ai_model_enable' and candidatephysicalrequestcount > 0",
                "subjecttype = 'prompt_release' and candidatephysicalrequestcount = 0"
        }) {
            assertTrue(schema.contains(contract), "建表脚本缺少执行证明约束: " + contract);
            assertTrue(migration.contains(contract), "升级迁移缺少执行证明约束: " + contract);
        }
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
