package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkCatalog;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkDataset;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkFixtureFile;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkSourceAssertion;
import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkTask;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

class GenerationBenchmarkDatasetFingerprintServiceTest {

    @Test
    void fingerprintMustCoverVersionMetadataFixturesAndAssertions() {
        GenerationBenchmarkCatalog catalog = new GenerationBenchmarkCatalog(new ObjectMapper());
        GenerationBenchmarkDatasetFingerprintService service =
                new GenerationBenchmarkDatasetFingerprintService(catalog);
        GenerationBenchmarkDataset original = catalog.dataset();
        String fingerprint = service.fingerprint(original);

        assertNotEquals(fingerprint, service.fingerprint(new GenerationBenchmarkDataset(
                original.schemaVersion(), original.datasetId(), "2.0.1", original.tasks())));

        GenerationBenchmarkTask first = original.tasks().getFirst();
        GenerationBenchmarkTask changedMetadata = copy(
                first,
                first.scenario() + "_changed",
                first.fixtureFiles(),
                first.sourceAssertions()
        );
        assertNotEquals(fingerprint, service.fingerprint(replace(original, 0, changedMetadata)));

        int declaredIndex = indexOfDeclaredTask(original.tasks());
        GenerationBenchmarkTask declared = original.tasks().get(declaredIndex);
        List<GenerationBenchmarkFixtureFile> fixtures = new ArrayList<>(declared.fixtureFiles());
        GenerationBenchmarkFixtureFile fixture = fixtures.getFirst();
        fixtures.set(0, new GenerationBenchmarkFixtureFile(
                fixture.root(), fixture.path(), fixture.content() + "// 指纹变化\n"));
        assertNotEquals(fingerprint, service.fingerprint(replace(
                original,
                declaredIndex,
                copy(declared, declared.scenario(), fixtures, declared.sourceAssertions())
        )));

        List<GenerationBenchmarkSourceAssertion> assertions = new ArrayList<>(declared.sourceAssertions());
        GenerationBenchmarkSourceAssertion assertion = assertions.getFirst();
        assertions.set(0, new GenerationBenchmarkSourceAssertion(
                assertion.id(),
                assertion.root(),
                assertion.paths(),
                append(assertion.allOf(), "新增断言"),
                assertion.anyOf(),
                assertion.noneOf()
        ));
        assertNotEquals(fingerprint, service.fingerprint(replace(
                original,
                declaredIndex,
                copy(declared, declared.scenario(), declared.fixtureFiles(), assertions)
        )));
    }

    private int indexOfDeclaredTask(List<GenerationBenchmarkTask> tasks) {
        for (int index = 0; index < tasks.size(); index++) {
            if (!tasks.get(index).fixtureFiles().isEmpty()) {
                return index;
            }
        }
        throw new IllegalStateException("测试数据集缺少声明式任务");
    }

    private GenerationBenchmarkDataset replace(GenerationBenchmarkDataset dataset,
                                               int index,
                                               GenerationBenchmarkTask replacement) {
        List<GenerationBenchmarkTask> tasks = new ArrayList<>(dataset.tasks());
        tasks.set(index, replacement);
        return new GenerationBenchmarkDataset(
                dataset.schemaVersion(), dataset.datasetId(), dataset.version(), tasks);
    }

    private GenerationBenchmarkTask copy(GenerationBenchmarkTask task,
                                         String scenario,
                                         List<GenerationBenchmarkFixtureFile> fixtures,
                                         List<GenerationBenchmarkSourceAssertion> assertions) {
        return new GenerationBenchmarkTask(
                task.id(),
                task.mode(),
                task.codeGenType(),
                task.prompt(),
                task.expectedValidation(),
                scenario,
                task.difficulty(),
                task.capabilities(),
                task.requiredQualityDimensions(),
                fixtures,
                assertions
        );
    }

    private List<String> append(List<String> values, String value) {
        List<String> changed = new ArrayList<>(values);
        changed.add(value);
        return List.copyOf(changed);
    }
}
