package com.rush.rushaicodemother.orchestration.runtime.identity;

/**
 * Generates globally unique identities for generation tasks.
 *
 * <p>The abstraction keeps task identity ownership outside orchestration persistence so the
 * current in-process runtime can later be replaced by a durable task runtime without changing
 * preparation, execution, or snapshot modules.</p>
 */
public interface GenerationTaskIdGenerator {

    String nextId();
}
