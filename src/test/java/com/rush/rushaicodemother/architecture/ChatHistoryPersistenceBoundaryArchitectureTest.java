package com.rush.rushaicodemother.architecture;

import com.mybatisflex.core.service.IService;
import com.rush.rushaicodemother.service.ChatHistoryService;
import com.rush.rushaicodemother.service.impl.ChatHistoryServiceImpl;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 对话历史模块持久化边界门禁。 */
class ChatHistoryPersistenceBoundaryArchitectureTest {

    @Test
    void serviceMustNotExposeGenericCrudContract() {
        assertFalse(IService.class.isAssignableFrom(ChatHistoryService.class));
        assertEquals(Object.class, ChatHistoryServiceImpl.class.getSuperclass());
    }

    @Test
    void controllerAndApplicationLayerMustNotDependOnOrmQueryObjects() throws IOException {
        String controllerSource = readSource(
                "src/main/java/com/rush/rushaicodemother/controller/ChatHistoryController.java"
        );
        String applicationSource = readSource(
                "src/main/java/com/rush/rushaicodemother/application/chathistory/ChatHistoryQueryApplicationService.java"
        );

        assertFalse(controllerSource.contains("QueryWrapper"));
        assertFalse(controllerSource.contains("ChatHistoryService"));
        assertFalse(applicationSource.contains("QueryWrapper"));
        assertFalse(applicationSource.contains("Page.of("));
    }

    private String readSource(String relativePath) throws IOException {
        return Files.readString(Path.of(relativePath));
    }
}
