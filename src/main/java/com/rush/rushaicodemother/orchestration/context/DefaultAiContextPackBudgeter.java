package com.rush.rushaicodemother.orchestration.context;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Value-first context selection with semantic-memory trust decay and a hard rendered-size cap. */
@Component
public class DefaultAiContextPackBudgeter implements AiContextPackBudgeter {

    private static final String TRUNCATION_MARKER = "...";
    private static final Pattern UNTRUSTED_BOUNDARY_SUFFIX = Pattern.compile(
            "(?s)(?:\\R)?(\\[END_UNTRUSTED_(?:REPOSITORY_CONTEXT|HISTORICAL_MEMORY|HISTORICAL_EVIDENCE)"
                    + " id=[^\\]\\r\\n]+\\])\\s*$"
    );

    private final AiContextPackBudgetProperties properties;
    private final AiContextTokenEstimator tokenEstimator;
    private final Clock clock;

    @Autowired
    public DefaultAiContextPackBudgeter(AiContextPackBudgetProperties properties,
                                        AiContextTokenEstimator tokenEstimator) {
        this(properties, tokenEstimator, Clock.systemUTC());
    }

    DefaultAiContextPackBudgeter(AiContextPackBudgetProperties properties) {
        this(properties, new OpenAiCompatibleContextTokenEstimator(properties), Clock.systemUTC());
    }

    DefaultAiContextPackBudgeter(AiContextPackBudgetProperties properties, Clock clock) {
        this(properties, new OpenAiCompatibleContextTokenEstimator(properties), clock);
    }

    DefaultAiContextPackBudgeter(AiContextPackBudgetProperties properties,
                                 AiContextTokenEstimator tokenEstimator,
                                 Clock clock) {
        this.properties = properties;
        this.tokenEstimator = tokenEstimator;
        this.clock = clock;
    }

    @Override
    public AiContextPack apply(AiContextPack contextPack) {
        if (contextPack == null || contextPack.empty()) {
            return contextPack;
        }
        int maxTokens = properties.maxTokens(contextPack.targetType());
        List<AiContextPackSection> normalized = contextPack.sections().stream()
                .map(this::normalizeSection)
                .filter(section -> !section.blank())
                .toList();
        List<AiContextPackSection> selected = new ArrayList<>();

        normalized.stream()
                .filter(this::mandatory)
                .sorted(Comparator.comparingInt(AiContextPackSection::priority))
                .forEach(section -> addIfFits(contextPack, selected, section, maxTokens, true));

        int semanticCount = 0;
        List<AiContextPackSection> candidates = normalized.stream()
                .filter(section -> !mandatory(section))
                .sorted(Comparator.comparingDouble(this::selectionScore).reversed()
                        .thenComparingInt(AiContextPackSection::priority))
                .toList();
        for (AiContextPackSection candidate : candidates) {
            if (candidate.type() == AiContextPackSectionType.SEMANTIC_MEMORY
                    && semanticCount >= properties.getMaxSemanticMemorySections()) {
                continue;
            }
            if (addIfFits(contextPack, selected, candidate, maxTokens, false)
                    && candidate.type() == AiContextPackSectionType.SEMANTIC_MEMORY) {
                semanticCount++;
            }
        }
        return copy(contextPack, selected);
    }

    private AiContextPackSection normalizeSection(AiContextPackSection section) {
        Map<String, Object> metadata = new LinkedHashMap<>(section.metadata());
        if (section.type() == AiContextPackSectionType.SEMANTIC_MEMORY) {
            double rawScore = Math.max(0.0, Math.min(1.0, number(metadata.get("score"), 0.0)));
            double ageDays = ageDays(metadata.get("createdAt"));
            double decay = Math.pow(0.5, ageDays / halfLifeDays());
            double effectiveScore = rawScore * Math.max(properties.getMinimumSemanticTrust(), decay);
            metadata.put("ageDays", Math.round(ageDays * 10.0) / 10.0);
            metadata.put("effectiveScore", effectiveScore);
        }
        int sourceTokens = tokenEstimator.estimate(section.content());
        String content = truncate(section.content(), properties.getMaxSectionTokens());
        if (!content.equals(section.content())) {
            metadata.put("truncated", true);
            metadata.put("sourceChars", section.content().length());
            metadata.put("sourceTokens", sourceTokens);
        }
        metadata.put("estimatedTokens", tokenEstimator.estimate(content));
        return new AiContextPackSection(
                section.type(), section.title(), content, section.priority(), Map.copyOf(metadata));
    }

    private boolean addIfFits(AiContextPack source,
                              List<AiContextPackSection> selected,
                              AiContextPackSection candidate,
                              int maxTokens,
                              boolean mandatory) {
        List<AiContextPackSection> trial = new ArrayList<>(selected);
        trial.add(candidate);
        if (fits(source, trial, maxTokens)) {
            selected.add(candidate);
            return true;
        }
        int minimum = mandatory
                ? 1
                : Math.max(
                        properties.getMinimumSectionTokens(),
                        minimumBoundaryTokens(candidate.content())
                );
        int low = minimum;
        int high = Math.min(
                tokenEstimator.estimate(candidate.content()),
                properties.getMaxSectionTokens()
        );
        AiContextPackSection best = null;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            AiContextPackSection shortened = withContent(candidate,
                    truncate(candidate.content(), middle));
            trial = new ArrayList<>(selected);
            trial.add(shortened);
            if (fits(source, trial, maxTokens)) {
                best = shortened;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        if (best == null
                || (!mandatory && tokenEstimator.estimate(best.content()) < minimum)) {
            return false;
        }
        selected.add(best);
        return true;
    }

    private AiContextPackSection withContent(AiContextPackSection section, String content) {
        Map<String, Object> metadata = new LinkedHashMap<>(section.metadata());
        metadata.put("truncated", true);
        metadata.putIfAbsent("sourceChars", section.content().length());
        metadata.putIfAbsent("sourceTokens", tokenEstimator.estimate(section.content()));
        metadata.put("estimatedTokens", tokenEstimator.estimate(content));
        return new AiContextPackSection(
                section.type(), section.title(), content, section.priority(), Map.copyOf(metadata));
    }

    private double selectionScore(AiContextPackSection section) {
        return switch (section.type()) {
            case SEMANTIC_MEMORY -> 600.0 + number(section.metadata().get("effectiveScore"), 0.0) * 400.0;
            case RECENT_TASK -> 850.0;
            case BUILD_TRACE -> section.priority() <= 20 ? 950.0 : 720.0;
            default -> 0.0;
        };
    }

    private boolean mandatory(AiContextPackSection section) {
        return section.type() == AiContextPackSectionType.APP_SCOPE
                || section.type() == AiContextPackSectionType.USAGE_RULE;
    }

    private String truncate(String content, int maxTokens) {
        if (content == null || content.isEmpty() || maxTokens <= 0) {
            return "";
        }
        if (tokenEstimator.estimate(content) <= maxTokens) {
            return content;
        }
        BoundarySplit boundary = splitBoundary(content);
        if (boundary != null) {
            return truncatePreservingBoundary(boundary, maxTokens);
        }
        int contentBudget = maxTokens - tokenEstimator.estimate(TRUNCATION_MARKER);
        if (contentBudget <= 0) {
            return tokenEstimator.truncate(TRUNCATION_MARKER, maxTokens);
        }
        while (contentBudget > 0) {
            String result = tokenEstimator.truncate(content, contentBudget) + TRUNCATION_MARKER;
            int estimated = tokenEstimator.estimate(result);
            if (estimated <= maxTokens) {
                return result;
            }
            contentBudget = Math.max(0, contentBudget - (estimated - maxTokens) - 1);
        }
        return tokenEstimator.truncate(TRUNCATION_MARKER, maxTokens);
    }

    private String truncatePreservingBoundary(BoundarySplit boundary, int maxTokens) {
        String minimum = joinBoundary("", boundary.suffix());
        int minimumTokens = tokenEstimator.estimate(minimum);
        if (minimumTokens > maxTokens) {
            return "";
        }
        int headBudget = Math.max(0, maxTokens - minimumTokens);
        while (headBudget >= 0) {
            String head = tokenEstimator.truncate(boundary.head(), headBudget);
            String result = joinBoundary(head, boundary.suffix());
            int estimated = tokenEstimator.estimate(result);
            if (estimated <= maxTokens) {
                return result;
            }
            if (headBudget == 0) {
                break;
            }
            headBudget = Math.max(0, headBudget - (estimated - maxTokens) - 1);
        }
        return minimum;
    }

    private int minimumBoundaryTokens(String content) {
        BoundarySplit boundary = splitBoundary(content);
        return boundary == null ? 0 : tokenEstimator.estimate(joinBoundary("", boundary.suffix()));
    }

    private BoundarySplit splitBoundary(String content) {
        Matcher matcher = UNTRUSTED_BOUNDARY_SUFFIX.matcher(content);
        if (!matcher.find()) {
            return null;
        }
        return new BoundarySplit(
                content.substring(0, matcher.start()).stripTrailing(),
                matcher.group(1)
        );
    }

    private String joinBoundary(String head, String suffix) {
        return head == null || head.isBlank()
                ? TRUNCATION_MARKER + "\n" + suffix
                : head.stripTrailing() + "\n" + TRUNCATION_MARKER + "\n" + suffix;
    }

    private boolean fits(AiContextPack source,
                         List<AiContextPackSection> sections,
                         int maximumTokens) {
        return tokenEstimator.estimate(copy(source, sections).render()) <= maximumTokens;
    }

    private AiContextPack copy(AiContextPack source, List<AiContextPackSection> sections) {
        return new AiContextPack(source.appId(), source.appName(), source.targetType(), List.copyOf(sections));
    }

    private double ageDays(Object createdAt) {
        if (!(createdAt instanceof Instant instant)) {
            return 0.0;
        }
        Duration age = Duration.between(instant, clock.instant());
        return Math.max(0.0, age.toSeconds() / 86_400.0);
    }

    private double halfLifeDays() {
        return Math.max(1.0, properties.getSemanticMemoryHalfLife().toSeconds() / 86_400.0);
    }

    private double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private record BoundarySplit(String head, String suffix) {
    }
}
