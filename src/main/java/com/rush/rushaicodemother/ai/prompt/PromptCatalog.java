package com.rush.rushaicodemother.ai.prompt;

import java.util.Optional;

/** Runtime port for immutable prompt versions and deterministic release selection. */
public interface PromptCatalog {

    Optional<PromptSelection> select(PromptRolloutSubject subject);

    Optional<PromptSelection> identify(String promptContent);

    PromptCatalogSnapshot snapshot();

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
        public Optional<PromptSelection> identify(String promptContent) {
            return Optional.empty();
        }

        @Override
        public PromptCatalogSnapshot snapshot() {
            return PromptCatalogSnapshot.unmanaged();
        }
    }
}
