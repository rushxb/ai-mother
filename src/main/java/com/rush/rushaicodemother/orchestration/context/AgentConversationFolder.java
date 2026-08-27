package com.rush.rushaicodemother.orchestration.context;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 把最早的若干轮工具循环折叠为系统提示摘要，替代静默丢弃。
 *
 * <p>三条不可违背的约束：
 * <ol>
 *   <li><b>按轮折叠</b>：一轮 = 一条含工具请求的 {@link AiMessage} 加其全部
 *       {@link ToolExecutionResultMessage}。绝不允许折叠出孤儿结果消息，
 *       否则 OpenAI 兼容提供商会直接拒绝请求；</li>
 *   <li><b>摘要并入既有系统消息</b>：不新增第二条 {@link SystemMessage}。
 *       {@code DurableToolConversationCodec} 以「最后一条系统消息」为审批恢复锚点，
 *       新增系统消息会顶掉真实系统提示；</li>
 *   <li><b>只折叠已闭合的轮次</b>：末尾未闭合的工具轮保持原文，
 *       审批恢复与失败重试都依赖它的完整参数。</li>
 * </ol>
 */
@Component
public class AgentConversationFolder {

    /** 摘要中每一栏最多列出的路径数，超出只保留计数，避免摘要自身膨胀。 */
    private static final int MAX_LISTED_PATHS = 12;

    /** 失败证据最多保留的条数。 */
    private static final int MAX_UNRESOLVED_ERRORS = 5;

    /** 失败证据单条最大字符数。 */
    private static final int MAX_ERROR_CHARS = 160;

    private final ToolRoundPathExtractor pathExtractor;

    public AgentConversationFolder(ToolRoundPathExtractor pathExtractor) {
        this.pathExtractor = Objects.requireNonNull(pathExtractor, "工具路径提取器不能为空");
    }

    /**
     * 折叠消息序列，使保留的工具轮数不超过 {@code keepRecentRounds}。
     *
     * @param messages         原始消息序列
     * @param keepRecentRounds 保留原文的最近工具轮数，必须为正
     * @return 折叠结果；无需折叠时原样返回
     */
    public FoldResult fold(List<ChatMessage> messages, int keepRecentRounds) {
        if (messages == null || messages.isEmpty() || keepRecentRounds <= 0) {
            return FoldResult.unchanged(messages);
        }
        List<Round> rounds = splitRounds(messages);
        List<Round> toolRounds = rounds.stream().filter(Round::hasToolRequests).toList();
        int foldableCount = toolRounds.size() - keepRecentRounds;
        if (foldableCount <= 0) {
            return FoldResult.unchanged(messages);
        }
        // 折叠边界取「第 foldableCount 个已闭合工具轮」的结束位置。
        Round boundary = null;
        int foldedRounds = 0;
        for (Round round : toolRounds) {
            if (foldedRounds >= foldableCount) {
                break;
            }
            if (!round.closed()) {
                // 未闭合轮必须保留原文，其之前的轮次照常折叠。
                break;
            }
            boundary = round;
            foldedRounds++;
        }
        if (boundary == null || foldedRounds <= 0) {
            return FoldResult.unchanged(messages);
        }

        int boundaryIndex = indexOfIdentity(rounds, boundary);
        List<Round> folded = rounds.subList(0, boundaryIndex + 1);
        AgentToolRoundSummary summary = summarize(folded, foldedRounds);
        if (summary.blank()) {
            return FoldResult.unchanged(messages);
        }
        return new FoldResult(rebuild(rounds, boundaryIndex, summary), summary, foldedRounds);
    }

    /**
     * 按引用定位轮次下标。
     *
     * <p>{@link Round} 是记录类型，{@code equals} 按值比较，重复的相同轮次
     * （例如用户重试同一诉求）会让 {@code indexOf} 命中错误位置，故按引用定位。</p>
     */
    private int indexOfIdentity(List<Round> rounds, Round target) {
        for (int index = 0; index < rounds.size(); index++) {
            if (rounds.get(index) == target) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 重建消息序列：系统消息追加摘要，锚点用户消息保留，其余只留未折叠轮次。
     *
     * <p>系统消息与最后一条独立用户消息是模型理解任务的锚点，任何情况下都不折叠。</p>
     */
    private List<ChatMessage> rebuild(List<Round> allRounds,
                                      int boundaryIndex,
                                      AgentToolRoundSummary summary) {
        List<Round> retained = allRounds.subList(boundaryIndex + 1, allRounds.size());
        List<ChatMessage> rebuilt = new ArrayList<>();
        SystemMessage system = lastSystemMessage(allRounds);
        rebuilt.add(SystemMessage.from(system == null
                ? summary.render()
                : system.text() + "\n\n" + summary.render()));

        UserMessage anchor = lastUserMessageBefore(allRounds, boundaryIndex);
        boolean retainedHasUser = retained.stream()
                .anyMatch(round -> round.head() instanceof UserMessage);
        if (anchor != null && !retainedHasUser) {
            rebuilt.add(anchor);
        }
        for (Round round : retained) {
            if (round.head() instanceof SystemMessage) {
                continue;
            }
            rebuilt.addAll(round.messages());
        }
        return List.copyOf(rebuilt);
    }

    /** 汇总被折叠轮次中的读取、改动与失败证据。 */
    private AgentToolRoundSummary summarize(List<Round> folded, int foldedRounds) {
        Set<String> readPaths = new LinkedHashSet<>();
        Set<String> mutatedPaths = new LinkedHashSet<>();
        Set<String> deletedPaths = new LinkedHashSet<>();
        List<String> unresolvedErrors = new ArrayList<>();

        for (Round round : folded) {
            if (!(round.head() instanceof AiMessage aiMessage) || !aiMessage.hasToolExecutionRequests()) {
                continue;
            }
            Map<String, ToolExecutionResultMessage> resultsById = resultsById(round);
            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                ToolExecutionResultMessage result = resultsById.get(request.id());
                boolean failed = result != null && Boolean.TRUE.equals(result.isError());
                if (failed) {
                    collectError(unresolvedErrors, request, result);
                    // 失败的写操作未落盘，不能宣称已改动，否则模型会跳过必要的重试。
                    continue;
                }
                ToolRoundPathExtractor.ExtractedPaths extracted = pathExtractor.extract(request, result);
                switch (extracted.effect()) {
                    case READ -> readPaths.addAll(extracted.paths());
                    case MUTATE -> mutatedPaths.addAll(extracted.paths());
                    case DELETE -> deletedPaths.addAll(extracted.paths());
                }
            }
        }
        // 已删除的文件不应再出现在「已改动」里，避免模型据此认为文件仍然存在。
        mutatedPaths.removeAll(deletedPaths);
        return new AgentToolRoundSummary(
                foldedRounds,
                limit(readPaths, "读取"),
                limit(mutatedPaths, "改动"),
                limit(deletedPaths, "删除"),
                List.copyOf(unresolvedErrors)
        );
    }

    private void collectError(List<String> errors,
                              ToolExecutionRequest request,
                              ToolExecutionResultMessage result) {
        if (errors.size() >= MAX_UNRESOLVED_ERRORS) {
            return;
        }
        String text = result.text() == null ? "" : result.text().strip();
        int lineBreak = text.indexOf('\n');
        String firstLine = lineBreak < 0 ? text : text.substring(0, lineBreak);
        if (firstLine.length() > MAX_ERROR_CHARS) {
            firstLine = firstLine.substring(0, MAX_ERROR_CHARS) + "…";
        }
        errors.add(request.name() + ": " + (firstLine.isBlank() ? "执行失败" : firstLine));
    }

    /** 超出列举上限时以计数收尾，保证摘要长度有界。 */
    private List<String> limit(Set<String> paths, String label) {
        if (paths.size() <= MAX_LISTED_PATHS) {
            return List.copyOf(paths);
        }
        List<String> limited = new ArrayList<>(
                new ArrayList<>(paths).subList(0, MAX_LISTED_PATHS));
        limited.add("……另有 " + (paths.size() - MAX_LISTED_PATHS) + " 个" + label + "路径");
        return List.copyOf(limited);
    }

    private Map<String, ToolExecutionResultMessage> resultsById(Round round) {
        Map<String, ToolExecutionResultMessage> resultsById = new LinkedHashMap<>();
        for (ChatMessage message : round.messages()) {
            if (message instanceof ToolExecutionResultMessage result && result.id() != null) {
                resultsById.put(result.id(), result);
            }
        }
        return resultsById;
    }

    /**
     * 按工具轮切分消息序列。
     *
     * <p>孤儿结果消息（前面没有对应的工具请求）单独成组并原样保留，
     * 不因折叠而改变其位置。</p>
     */
    private List<Round> splitRounds(List<ChatMessage> messages) {
        List<Round> rounds = new ArrayList<>();
        int index = 0;
        while (index < messages.size()) {
            ChatMessage head = messages.get(index);
            List<ChatMessage> group = new ArrayList<>();
            group.add(head);
            index++;
            if (head instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()) {
                while (index < messages.size()
                        && messages.get(index) instanceof ToolExecutionResultMessage) {
                    group.add(messages.get(index));
                    index++;
                }
                int expected = aiMessage.toolExecutionRequests().size();
                rounds.add(new Round(List.copyOf(group), true, group.size() - 1 >= expected));
                continue;
            }
            rounds.add(new Round(List.copyOf(group), false, true));
        }
        return List.copyOf(rounds);
    }

    private SystemMessage lastSystemMessage(List<Round> rounds) {
        SystemMessage found = null;
        for (Round round : rounds) {
            if (round.head() instanceof SystemMessage system) {
                found = system;
            }
        }
        return found;
    }

    /** 取被折叠区间内最后一条用户消息作为任务锚点。 */
    private UserMessage lastUserMessageBefore(List<Round> allRounds, int boundaryIndex) {
        UserMessage found = null;
        for (int index = 0; index <= boundaryIndex && index < allRounds.size(); index++) {
            if (allRounds.get(index).head() instanceof UserMessage user) {
                found = user;
            }
        }
        return found;
    }

    /**
     * 折叠结果。
     *
     * @param messages     折叠后的消息序列
     * @param summary      折叠摘要
     * @param foldedRounds 被折叠的工具轮数，0 表示未折叠
     */
    public record FoldResult(
            List<ChatMessage> messages,
            AgentToolRoundSummary summary,
            int foldedRounds
    ) {

        static FoldResult unchanged(List<ChatMessage> messages) {
            return new FoldResult(
                    messages == null ? List.of() : List.copyOf(messages),
                    AgentToolRoundSummary.none(),
                    0
            );
        }

        /**
         * 判断是否发生了折叠。
         *
         * @return 发生折叠时返回 {@code true}
         */
        public boolean folded() {
            return foldedRounds > 0;
        }
    }

    /**
     * 一个消息分组。
     *
     * @param messages        分组内的消息，首条为组头
     * @param hasToolRequests 组头是否为含工具请求的助手消息
     * @param closed          工具轮是否已收到全部结果
     */
    private record Round(List<ChatMessage> messages, boolean hasToolRequests, boolean closed) {

        ChatMessage head() {
            return messages.getFirst();
        }
    }
}
