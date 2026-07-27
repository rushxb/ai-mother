package com.rush.rushaicodemother.service.aimodel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultAiModelEnabledConfigurationSourceTest {

    @Test
    void missingSnapshotMustDelegateToPersistence() {
        AiModelPersistenceService persistence = mock(AiModelPersistenceService.class);
        List<AiModelConfiguration> expected = List.of(model("chat"));
        when(persistence.findEnabled("chat")).thenReturn(expected);
        DefaultAiModelEnabledConfigurationSource source =
                new DefaultAiModelEnabledConfigurationSource(persistence, Optional.empty());

        assertEquals(expected, source.findEnabled("chat"));
        verify(persistence).findEnabled("chat");
    }

    @Test
    void snapshotMustBeFilteredWithoutReadingPersistence() {
        AiModelPersistenceService persistence = mock(AiModelPersistenceService.class);
        AiModelEnabledConfigurationSnapshot snapshot =
                mock(AiModelEnabledConfigurationSnapshot.class);
        when(snapshot.enabledModels()).thenReturn(List.of(model("chat"), model("reasoning")));
        DefaultAiModelEnabledConfigurationSource source =
                new DefaultAiModelEnabledConfigurationSource(
                        persistence, Optional.of(snapshot));

        assertEquals(List.of("chat"), source.findEnabled("chat").stream()
                .map(AiModelConfiguration::getModelType)
                .toList());
    }

    private AiModelConfiguration model(String type) {
        return AiModelConfiguration.builder().modelType(type).build();
    }
}
