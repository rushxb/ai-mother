package com.rush.rushaicodemother.ai.prompt.release;

import java.util.List;
import java.util.Optional;

/** Persistence port for atomic prompt release pointers and immutable audit history. */
public interface PromptReleaseRepository {

    PromptReleaseState loadCurrent();

    PromptReleaseRecord publish(PromptReleaseMutation mutation);

    Optional<PromptReleaseHistoryEntry> findHistory(String promptKey, long revision);

    List<PromptReleaseHistoryEntry> listHistory(String promptKey, int limit);
}
