package com.rush.rushaicodemother.orchestration.benchmark.rule;

import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkWorkspaceInspector;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;

/**
 * Vue 基准测试规则支持组件。
 */
final class VueBenchmarkRuleSupport {

    private VueBenchmarkRuleSupport() {
    }

    static void mountProbe(GenerationBenchmarkWorkspaceInspector inspector,
                           GenerationWorkspace workspace,
                           String componentName,
                           String componentContent) {
        String relativeComponentPath = "src/benchmark/" + componentName + ".vue";
        inspector.writeUtf8(workspace.frontendRootPath(), relativeComponentPath, componentContent);

        String app = inspector.readUtf8(workspace.frontendRootPath(), "src/App.vue");
        if (app.isBlank()) {
            throw new IllegalStateException("Vue benchmark App.vue is missing");
        }
        String componentTag = "<" + componentName + " />";
        if (!app.contains(componentTag)) {
            app = app.replace("<router-view />", componentTag + "\n  <router-view />");
        }
        String importLine = "import " + componentName + " from './benchmark/" + componentName + ".vue'";
        if (!app.contains(importLine)) {
            int scriptStart = app.indexOf("<script setup");
            int openingEnd = scriptStart < 0 ? -1 : app.indexOf('>', scriptStart);
            if (openingEnd < 0) {
                throw new IllegalStateException("Vue benchmark App.vue has no script setup block");
            }
            app = app.substring(0, openingEnd + 1)
                    + "\n" + importLine
                    + app.substring(openingEnd + 1);
        }
        inspector.writeUtf8(workspace.frontendRootPath(), "src/App.vue", app);
    }
}
