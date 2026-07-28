package com.rush.rushaicodemother.infrastructure.process;

import java.util.Map;
import java.util.Set;

/** Go 子进程的确定性环境策略。 */
public final class GoProcessEnvironment {

    private static final Set<String> INHERITED_VARIABLES_TO_REMOVE = Set.of(
            "GOROOT", "goroot",
            "GOPATH", "gopath",
            "GOCACHE", "gocache",
            "GOMODCACHE", "gomodcache",
            "GOTMPDIR", "gotmpdir",
            "GONOPROXY", "gonoproxy",
            "GONOSUMDB", "gonosumdb",
            "GOPRIVATE", "goprivate",
            "CC", "cc",
            "CXX", "cxx"
    );

    private GoProcessEnvironment() {
    }

    /**
 * 返回{@code overrides}。
 *
 * @return {@code Go}进程{@code Environment}集合
 */
    public static Map<String, String> overrides() {
        return Map.of(
                "GOENV", "off",
                "GOFLAGS", "",
                "GOPROXY", "off",
                "GOSUMDB", "off",
                "GOTOOLCHAIN", "local",
                "GOTELEMETRY", "off",
                "GOWORK", "off",
                "CGO_ENABLED", "0"
        );
    }

    public static Set<String> variablesToRemove() {
        return INHERITED_VARIABLES_TO_REMOVE;
    }
}
