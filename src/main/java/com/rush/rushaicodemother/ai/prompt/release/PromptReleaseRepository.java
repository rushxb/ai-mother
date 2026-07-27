package com.rush.rushaicodemother.ai.prompt.release;

import java.util.List;
import java.util.Optional;

/** 用于原子提示释放指针和不可变审计历史记录的持久端口。 */
public interface PromptReleaseRepository {

    PromptReleaseState loadCurrent();

    PromptReleaseRecord publish(PromptReleaseMutation mutation);

    Optional<PromptReleaseHistoryEntry> findHistory(String promptKey, long revision);

    List<PromptReleaseHistoryEntry> listHistory(String promptKey, int limit);
}
