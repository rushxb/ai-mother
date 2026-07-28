package com.rush.rushaicodemother.infrastructure.process;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Node.js 子进程的统一、可审计环境策略。 */
public final class NodeProcessEnvironment {

    private static final Set<String> UNSAFE_INHERITED_VARIABLES = Set.of(
            "NODE_OPTIONS",
            "node_options",
            "NODE_PATH",
            "node_path",
            "NPM_CONFIG_PREFIX",
            "npm_config_prefix",
            "PNPM_HOME",
            "pnpm_home"
    );

    private NodeProcessEnvironment() {
    }

    /**
 * 返回{@code overrides}。
 *
 * @param continuousIntegration {@code continuousIntegration} 对应的调用参数
 * @return 节点进程{@code Environment}集合
 */
    public static Map<String, String> overrides(boolean continuousIntegration) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("NO_UPDATE_NOTIFIER", "1");
        environment.put("NPM_CONFIG_AUDIT", "false");
        environment.put("NPM_CONFIG_FUND", "false");
        if (continuousIntegration) {
            environment.put("CI", "true");
        }
        return Map.copyOf(environment);
    }

    public static Set<String> variablesToRemove() {
        return UNSAFE_INHERITED_VARIABLES;
    }
}
