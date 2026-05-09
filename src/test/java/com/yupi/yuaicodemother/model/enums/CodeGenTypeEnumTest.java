package com.yupi.yuaicodemother.model.enums;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CodeGenTypeEnumTest {

    @Test
    void canUpgradeToShouldOnlyAllowHigherCapabilityType() {
        Assertions.assertTrue(CodeGenTypeEnum.HTML.canUpgradeTo(CodeGenTypeEnum.MULTI_FILE));
        Assertions.assertTrue(CodeGenTypeEnum.HTML.canUpgradeTo(CodeGenTypeEnum.VUE_PROJECT));
        Assertions.assertTrue(CodeGenTypeEnum.MULTI_FILE.canUpgradeTo(CodeGenTypeEnum.VUE_PROJECT));

        Assertions.assertFalse(CodeGenTypeEnum.VUE_PROJECT.canUpgradeTo(CodeGenTypeEnum.HTML));
        Assertions.assertFalse(CodeGenTypeEnum.VUE_PROJECT.canUpgradeTo(CodeGenTypeEnum.MULTI_FILE));
        Assertions.assertFalse(CodeGenTypeEnum.HTML.canUpgradeTo(CodeGenTypeEnum.HTML));
    }

    @Test
    void maxShouldKeepTheMoreCapableType() {
        Assertions.assertEquals(CodeGenTypeEnum.VUE_PROJECT,
                CodeGenTypeEnum.max(CodeGenTypeEnum.HTML, CodeGenTypeEnum.VUE_PROJECT));
        Assertions.assertEquals(CodeGenTypeEnum.MULTI_FILE,
                CodeGenTypeEnum.max(CodeGenTypeEnum.MULTI_FILE, CodeGenTypeEnum.HTML));
        Assertions.assertEquals(CodeGenTypeEnum.VUE_PROJECT,
                CodeGenTypeEnum.max(CodeGenTypeEnum.VUE_PROJECT, null));
        Assertions.assertEquals(CodeGenTypeEnum.HTML,
                CodeGenTypeEnum.max(null, CodeGenTypeEnum.HTML));
    }
}
