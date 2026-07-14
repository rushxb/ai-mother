package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.AiCreateSpecService;
import com.rush.rushaicodemother.ai.AiCreateSpecServiceFactory;
import com.rush.rushaicodemother.ai.model.CreateSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Generates and normalizes compact CREATE specs.
 */
@Slf4j
@Service
public class CreateSpecService {

    private final AiCreateSpecServiceFactory serviceFactory;
    private final CreateSpecDefaults defaults = new CreateSpecDefaults();
    private final CreateSpecNormalizer normalizer;

    public CreateSpecService(AiCreateSpecServiceFactory serviceFactory) {
        this(serviceFactory, new CreateSpecNormalizer());
    }

    @Autowired
    public CreateSpecService(AiCreateSpecServiceFactory serviceFactory,
                             CreateSpecNormalizer normalizer) {
        this.serviceFactory = serviceFactory;
        this.normalizer = normalizer;
    }

    public SpecResult generate(String userMessage, CreateGenerationPlan plan) {
        if (plan == null || plan.slotGroups().isEmpty()) {
            return generate(userMessage, plan, null);
        }
        SlotGroup group = aggregateGroup(plan);
        return generate(userMessage, plan, group);
    }

    public SpecResult generate(String userMessage, CreateGenerationPlan plan, SlotGroup group) {
        if (plan == null || group == null) {
            return SpecResult.available(normalizer.normalize(
                    defaults.fromRequest(userMessage, plan, group, "create_spec_invalid_context"),
                    userMessage,
                    plan,
                    group
            ).spec(), "local_spec_invalid_context", CreateSpecValidationResult.ok(List.of("create_spec_invalid_context")));
        }
        try {
            AiCreateSpecService service = serviceFactory.createService();
            CreateSpec spec = service.generateSpec(
                    StrUtil.blankToDefault(userMessage, ""),
                    plan.codeGenType() == null ? "" : plan.codeGenType().getValue(),
                    group.templateId(),
                    plannedModules(plan, group)
            );
            CreateSpecNormalizer.NormalizedSpec normalized = normalizer.normalize(spec, userMessage, plan, group);
            return SpecResult.available(normalized.spec(), "ai_spec", normalized.validation());
        } catch (Exception e) {
            String reason = "create_spec_exception";
            log.warn("CREATE 规格生成失败，已回退到本地规格", LogExceptionSanitizer.sanitize(e));
            CreateSpecNormalizer.NormalizedSpec normalized = normalizer.normalize(
                    defaults.fromRequest(userMessage, plan, group, reason),
                    userMessage,
                    plan,
                    group
            );
            return SpecResult.available(normalized.spec(), "local_spec_fallback:" + reason, normalized.validation());
        }
    }

    private String plannedModules(CreateGenerationPlan plan, SlotGroup group) {
        List<String> modules = plan.modules().stream()
                .filter(module -> module != null && group.templateId().equals(module.templateId()))
                .map(module -> module.moduleId() + ":" + module.slotIds())
                .toList();
        if (modules.isEmpty()) {
            return group.groupId() + ":" + group.slotIds();
        }
        return String.join("; ", modules);
    }

    private SlotGroup aggregateGroup(CreateGenerationPlan plan) {
        List<String> templateIds = plan.slotGroups().stream()
                .filter(group -> group != null && StrUtil.isNotBlank(group.templateId()))
                .map(SlotGroup::templateId)
                .distinct()
                .toList();
        List<String> slotIds = plan.slotGroups().stream()
                .filter(group -> group != null)
                .flatMap(group -> group.slotIds().stream())
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
        String templateId = StrUtil.blankToDefault(plan.baseTemplateId(), String.join("+", templateIds));
        return new SlotGroup("create-request-spec", templateId, "create-request", slotIds, 0);
    }

    public record SpecResult(
            boolean available,
            CreateSpec spec,
            String reason,
            CreateSpecValidationResult validation
    ) {
        public SpecResult(boolean available, CreateSpec spec, String reason) {
            this(available, spec, reason, CreateSpecValidationResult.ok(List.of()));
        }

        private static SpecResult available(CreateSpec spec, String reason, CreateSpecValidationResult validation) {
            return new SpecResult(true, spec, StrUtil.blankToDefault(reason, "ai_spec"), validation);
        }
    }
}
