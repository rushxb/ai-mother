package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkCatalog;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkDataset;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkFixtureFile;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkSourceAssertion;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 对完整评测定义做内容寻址，任何元数据、夹具或断言变化都会使旧证据失效。 */
@Component
@RequiredArgsConstructor
public class GenerationBenchmarkDatasetFingerprintService {

    private final GenerationBenchmarkCatalog catalog;

    public String currentFingerprint() {
        return fingerprint(catalog.dataset());
    }

    public String fingerprint(GenerationBenchmarkDataset dataset) {
        if (dataset == null) {
            throw new IllegalArgumentException("评测数据集不能为空");
        }
        StringBuilder canonical = new StringBuilder("generation-benchmark-dataset-v2|");
        ReleaseCandidateFingerprint.appendField(canonical, Integer.toString(dataset.schemaVersion()));
        ReleaseCandidateFingerprint.appendField(canonical, dataset.datasetId());
        ReleaseCandidateFingerprint.appendField(canonical, dataset.version());
        ReleaseCandidateFingerprint.appendField(canonical, Integer.toString(dataset.tasks().size()));
        for (GenerationBenchmarkTask task : dataset.tasks()) {
            ReleaseCandidateFingerprint.appendField(canonical, task.id());
            ReleaseCandidateFingerprint.appendField(canonical, task.mode());
            ReleaseCandidateFingerprint.appendField(canonical, task.codeGenType());
            ReleaseCandidateFingerprint.appendField(canonical, task.prompt());
            ReleaseCandidateFingerprint.appendField(canonical, task.expectedValidation());
            ReleaseCandidateFingerprint.appendField(canonical, task.scenario());
            ReleaseCandidateFingerprint.appendField(
                    canonical,
                    task.difficulty() == null ? null : task.difficulty().name()
            );
            appendStrings(canonical, task.capabilities());
            appendStrings(canonical, task.requiredQualityDimensions().stream()
                    .map(dimension -> dimension == null ? "" : dimension.name())
                    .toList());
            ReleaseCandidateFingerprint.appendField(canonical, Integer.toString(task.fixtureFiles().size()));
            for (GenerationBenchmarkFixtureFile fixture : task.fixtureFiles()) {
                ReleaseCandidateFingerprint.appendField(
                        canonical,
                        fixture.root() == null ? null : fixture.root().name()
                );
                ReleaseCandidateFingerprint.appendField(canonical, fixture.path());
                ReleaseCandidateFingerprint.appendField(canonical, fixture.content());
            }
            ReleaseCandidateFingerprint.appendField(canonical, Integer.toString(task.sourceAssertions().size()));
            for (GenerationBenchmarkSourceAssertion assertion : task.sourceAssertions()) {
                ReleaseCandidateFingerprint.appendField(canonical, assertion.id());
                ReleaseCandidateFingerprint.appendField(
                        canonical,
                        assertion.root() == null ? null : assertion.root().name()
                );
                appendStrings(canonical, assertion.paths());
                appendStrings(canonical, assertion.allOf());
                appendStrings(canonical, assertion.anyOf());
                appendStrings(canonical, assertion.noneOf());
            }
        }
        return ReleaseCandidateFingerprint.sha256(canonical.toString());
    }

    private void appendStrings(StringBuilder canonical, List<String> values) {
        ReleaseCandidateFingerprint.appendField(canonical, Integer.toString(values.size()));
        values.forEach(value -> ReleaseCandidateFingerprint.appendField(canonical, value));
    }
}
