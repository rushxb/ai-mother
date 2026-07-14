package com.rush.rushaicodemother.core.builder;

import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueProjectScriptResolverTest {

    private final VueProjectScriptResolver resolver = new VueProjectScriptResolver();

    @Test
    void shouldSelectScriptsByStablePriorityAndIgnoreBlankDefinitions() {
        VueProjectScripts scripts = resolver.resolve(JSONUtil.parseObj("""
                {
                  "scripts": {
                    "build": "vite build",
                    "pure-build": "vite build --emptyOutDir",
                    "build-only": "vite build",
                    "type-check": " ",
                    "typecheck": "vue-tsc --noEmit",
                    "check": "eslint ."
                  }
                }
                """));

        assertEquals("build", scripts.fullBuildScript());
        assertEquals("pure-build", scripts.lightBuildScript());
        assertEquals("typecheck", scripts.lightValidationScript());
        assertTrue(scripts.supportsLightBuild());
        assertTrue(scripts.supportsLightValidation());
    }

    @Test
    void shouldUseLightBuildAsFullBuildFallback() {
        VueProjectScripts scripts = resolver.resolve(JSONUtil.parseObj("""
                {"scripts":{"build-only":"vite build"}}
                """));

        assertEquals("build-only", scripts.fullBuildScript());
        assertEquals("build-only", scripts.lightBuildScript());
        assertNull(scripts.lightValidationScript());
        assertFalse(scripts.supportsLightValidation());
    }
}
