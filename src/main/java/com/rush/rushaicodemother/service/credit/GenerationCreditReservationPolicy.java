package com.rush.rushaicodemother.service.credit;

import com.rush.rushaicodemother.config.GenerationCreditReservationProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** 生成保守的路线/类型报价，而不将计费与特定模型供应商耦合。 */
@Component
@RequiredArgsConstructor
public class GenerationCreditReservationPolicy {

    private final GenerationCreditReservationProperties properties;
    private final UserCreditCostCalculator costCalculator;

    /**
 * 返回{@code quote}。
 *
 * @param command 命令
 * @return 生成额度{@code Reservation}策略
 */
    public GenerationCreditReservationQuote quote(GenerationTaskCommand command) {
        Objects.requireNonNull(command, "command");
        long baseTokens = estimatedTokens(command.mode());
        long estimatedTokens = multiplyAndRoundUp(baseTokens, multiplierPercent(command.codeGenType()));
        long reservedCredit = costCalculator.calculate(estimatedTokens);
        if (reservedCredit <= 0) {
            throw new IllegalStateException("generation credit reservation must be positive");
        }
        String profile = command.slaEnvelope() == null
                ? "legacy-default"
                : command.slaEnvelope().profile();
        String pricingReference = String.join(":",
                properties.getPolicyVersion().trim(),
                command.mode().name(),
                command.codeGenType().name(),
                profile,
                Long.toString(estimatedTokens));
        return new GenerationCreditReservationQuote(
                estimatedTokens,
                reservedCredit,
                pricingReference
        );
    }

    /** 模型澄清可能升级路由时使用的保守任务成本上限。 */
    public GenerationCreditReservationQuote quoteUpperBound(CodeGenTypeEnum codeGenType) {
        Objects.requireNonNull(codeGenType, "codeGenType");
        long maximumRouteTokens = java.util.Arrays.stream(GenerationMode.values())
                .mapToLong(this::estimatedTokens)
                .max()
                .orElseThrow(() -> new IllegalStateException("没有可用的生成路由报价"));
        long estimatedTokens = multiplyAndRoundUp(
                maximumRouteTokens, multiplierPercent(codeGenType));
        long reservedCredit = costCalculator.calculate(estimatedTokens);
        if (reservedCredit <= 0) {
            throw new IllegalStateException("preflight credit upper bound must be positive");
        }
        return new GenerationCreditReservationQuote(
                estimatedTokens,
                reservedCredit,
                String.join(":",
                        properties.getPolicyVersion().trim(),
                        "PREFLIGHT_MAX",
                        codeGenType.name(),
                        Long.toString(estimatedTokens)));
    }

    private long estimatedTokens(GenerationMode mode) {
        return switch (Objects.requireNonNull(mode, "mode")) {
            case CREATE -> properties.getCreateEstimatedTokens();
            case READ_ONLY -> properties.getReadOnlyEstimatedTokens();
            case LIGHT_EDIT -> properties.getLightEditEstimatedTokens();
            case AGENT_EDIT -> properties.getAgentEditEstimatedTokens();
            case HEAVY_EXPERT -> properties.getHeavyExpertEstimatedTokens();
        };
    }

    private int multiplierPercent(CodeGenTypeEnum type) {
        return switch (Objects.requireNonNull(type, "codeGenType")) {
            case HTML -> properties.getHtmlMultiplierPercent();
            case MULTI_FILE -> properties.getMultiFileMultiplierPercent();
            case VUE_PROJECT -> properties.getVueProjectMultiplierPercent();
            case BACKEND_PROJECT -> properties.getBackendProjectMultiplierPercent();
            case FULL_STACK_PROJECT -> properties.getFullStackProjectMultiplierPercent();
        };
    }

    /** 返回{@code multiply}{@code And}{@code Round}{@code Up}。 */
    private long multiplyAndRoundUp(long value, int percent) {
        try {
            long product = Math.multiplyExact(value, percent);
            return Math.addExact(product, 99L) / 100L;
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException("generation credit reservation estimate overflow", overflow);
        }
    }
}
