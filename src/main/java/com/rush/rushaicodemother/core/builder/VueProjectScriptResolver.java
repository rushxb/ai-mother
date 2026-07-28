package com.rush.rushaicodemother.core.builder;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import org.springframework.stereotype.Component;

/**
 * 解析 package.json 的校验与构建脚本，并按约定选择稳定的优先级。
 */
@Component
public class VueProjectScriptResolver {

    /** 根据当前上下文解析{@code Vue}项目{@code Script}。 */
    VueProjectScripts resolve(JSONObject packageJson) {
        JSONObject scripts = packageJson == null ? null : packageJson.getJSONObject("scripts");
        String lightBuildScript = firstAvailableScript(scripts, "pure-build", "build-only");
        String fullBuildScript = firstAvailableScript(scripts, "build");
        if (fullBuildScript == null) {
            fullBuildScript = lightBuildScript;
        }
        return new VueProjectScripts(
                fullBuildScript,
                lightBuildScript,
                firstAvailableScript(scripts, "type-check", "typecheck", "check")
        );
    }

    /** 返回首次可用{@code Script}。 */
    private String firstAvailableScript(JSONObject scripts, String... names) {
        if (scripts == null) {
            return null;
        }
        for (String name : names) {
            Object value = scripts.get(name);
            if (value instanceof CharSequence && StrUtil.isNotBlank(value.toString())) {
                return name;
            }
        }
        return null;
    }
}
