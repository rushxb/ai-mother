package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplatePreWarmPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptDefaultResourceLimitsAndTemplateList() {
        assertTrue(validator.validate(new TemplatePreWarmProperties()).isEmpty());
    }

    @Test
    void shouldRejectConcurrencyOutsideSupportedRange() {
        TemplatePreWarmProperties tooSmall = new TemplatePreWarmProperties();
        tooSmall.setMaxConcurrency(0);
        TemplatePreWarmProperties tooLarge = new TemplatePreWarmProperties();
        tooLarge.setMaxConcurrency(9);

        assertFalse(validator.validate(tooSmall).isEmpty());
        assertFalse(validator.validate(tooLarge).isEmpty());
    }

    @Test
    void shouldRejectEmptyTemplateList() {
        TemplatePreWarmProperties properties = new TemplatePreWarmProperties();
        properties.setTemplateIds(List.of());

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectNullTemplateList() {
        TemplatePreWarmProperties properties = new TemplatePreWarmProperties();
        properties.setTemplateIds(null);

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectInvalidTemplateId() {
        TemplatePreWarmProperties properties = new TemplatePreWarmProperties();
        properties.setTemplateIds(List.of("../vue-web-basic"));

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectDuplicateTemplateIds() {
        TemplatePreWarmProperties properties = new TemplatePreWarmProperties();
        properties.setTemplateIds(List.of("vue-web-basic", "vue-web-basic"));

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectSyntacticallyValidButUnknownTemplateId() {
        TemplatePreWarmProperties properties = new TemplatePreWarmProperties();
        properties.setTemplateIds(List.of("unknown-template"));

        assertFalse(validator.validate(properties).isEmpty());
    }}
