package com.yupi.yuaicodemother.service.impl;

import com.yupi.yuaicodemother.constant.AppConstant;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationRepairPolicyTest {

    @Test
    void shouldNotAutoRepairCreateStage() {
        assertFalse(GenerationRepairPolicy.allowAutoRepair(
                AppConstant.GENERATING_STAGE_CREATE,
                CodeGenTypeEnum.VUE_PROJECT,
                1
        ));
    }

    @Test
    void shouldAllowAutoRepairOnlyForExistingVueProjectUpdates() {
        assertTrue(GenerationRepairPolicy.allowAutoRepair(
                AppConstant.GENERATING_STAGE_UPDATE,
                CodeGenTypeEnum.VUE_PROJECT,
                1
        ));
        assertFalse(GenerationRepairPolicy.allowAutoRepair(
                AppConstant.GENERATING_STAGE_UPDATE,
                CodeGenTypeEnum.MULTI_FILE,
                1
        ));
        assertFalse(GenerationRepairPolicy.allowAutoRepair(
                AppConstant.GENERATING_STAGE_UPDATE,
                CodeGenTypeEnum.VUE_PROJECT,
                0
        ));
    }
}
