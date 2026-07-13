package com.rush.rushaicodemother.service.impl;

import com.rush.rushaicodemother.model.dto.chathistory.ChatHistoryQueryRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ChatHistoryServiceImplQueryTest {

    private final ChatHistoryServiceImpl service = new ChatHistoryServiceImpl();

    @Test
    void missingSortFieldMustUseDefaultSortWithoutThrowing() {
        ChatHistoryQueryRequest request = new ChatHistoryQueryRequest();

        assertDoesNotThrow(() -> service.getQueryWrapper(request));
    }
}
