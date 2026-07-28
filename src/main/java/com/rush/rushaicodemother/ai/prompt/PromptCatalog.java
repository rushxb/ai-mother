package com.rush.rushaicodemother.ai.prompt;

import java.util.Optional;

/** 用于不可变提示版本和确定性版本选择的运行时端口。 */
public interface PromptCatalog {

    Optional<PromptSelection> select(PromptRolloutSubject subject);

    /** 按稳定提示键和隐私安全分桶键选择发布版本。 */
    Optional<PromptSelection> selectByKey(String promptKey, String cohortKey);

    Optional<PromptSelection> identify(String promptContent);

    PromptCatalogSnapshot snapshot();

    /**
 * 返回{@code bundle}编号。
 *
 * @return 处理后的提示词目录文本
 */
    default String bundleId() {
        return snapshot().bundleId();
    }

    static PromptCatalog unmanaged() {
        return UnmanagedPromptCatalog.INSTANCE;
    }

    final class UnmanagedPromptCatalog implements PromptCatalog {
        private static final UnmanagedPromptCatalog INSTANCE = new UnmanagedPromptCatalog();

        private UnmanagedPromptCatalog() {
        }

        @Override
        public Optional<PromptSelection> select(PromptRolloutSubject subject) {
            return Optional.empty();
        }

        @Override
        public Optional<PromptSelection> selectByKey(String promptKey, String cohortKey) {
            return Optional.empty();
        }

        @Override
        public Optional<PromptSelection> identify(String promptContent) {
            return Optional.empty();
        }

        @Override
        public PromptCatalogSnapshot snapshot() {
            return PromptCatalogSnapshot.unmanaged();
        }
    }
}
