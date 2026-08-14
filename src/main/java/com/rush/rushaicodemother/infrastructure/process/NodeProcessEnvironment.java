package com.rush.rushaicodemother.infrastructure.process;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
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
    private static final Set<String> SAFE_INHERITED_VARIABLES = Set.of(
            "PATH",
            "PATHEXT",
            "SYSTEMROOT",
            "WINDIR",
            "COMSPEC",
            "SYSTEMDRIVE",
            "HOMEDRIVE",
            "HOMEPATH",
            "TEMP",
            "TMP",
            "HOME",
            "USERPROFILE",
            "LOCALAPPDATA",
            "APPDATA",
            "PROGRAMDATA",
            "PROGRAMFILES",
            "PROGRAMFILES(X86)",
            "COMMONPROGRAMFILES",
            "NUMBER_OF_PROCESSORS",
            "PROCESSOR_ARCHITECTURE",
            "OS",
            "LANG",
            "LC_ALL",
            "TZ",
            "TERM",
            "CI",
            "NO_UPDATE_NOTIFIER",
            "NPM_CONFIG_AUDIT",
            "NPM_CONFIG_FUND"
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
        return variablesToRemove(System.getenv().keySet());
    }

    /**
     * 计算需要从子进程移除的变量，只保留 Node 工具链启动所需的最小主机环境。
     *
     * <p>使用允许列表而不是 secret 名称黑名单，避免新接入的模型、数据库或租户密钥
     * 因命名未知而被生成代码继承。</p>
     */
    static Set<String> variablesToRemove(Set<String> inheritedVariableNames) {
        LinkedHashSet<String> variables = new LinkedHashSet<>(UNSAFE_INHERITED_VARIABLES);
        if (inheritedVariableNames != null) {
            inheritedVariableNames.stream()
                    .filter(name -> name != null && !name.isBlank())
                    .filter(name -> !SAFE_INHERITED_VARIABLES.contains(
                            name.toUpperCase(Locale.ROOT)))
                    .forEach(variables::add);
        }
        return Set.copyOf(variables);
    }
}
