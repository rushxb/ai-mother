package com.rush.rushaicodemother.application.app;

import com.rush.rushaicodemother.config.CodeStorageProperties;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.ProjectDownloadService;
import com.rush.rushaicodemother.service.app.AppPersistenceService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppCodeDownloadApplicationServiceTest {

    private static final Long APP_ID = 9_876_543_210L;

    private AppPersistenceService appPersistenceService;
    private ProjectDownloadService projectDownloadService;
    private AppCodeDownloadApplicationService service;
    private GenerationWorkspaceService generationWorkspaceService;
    private Path projectDirectory;

    @BeforeEach
    void setUp() {
        appPersistenceService = mock(AppPersistenceService.class);
        projectDownloadService = mock(ProjectDownloadService.class);
        generationWorkspaceService = new GenerationWorkspaceService(new CodeStorageProperties());
        service = new AppCodeDownloadApplicationService(
                appPersistenceService,
                projectDownloadService,
                new AppAccessPolicy(),
                generationWorkspaceService
        );
        projectDirectory = generationWorkspaceService
                .resolve(APP_ID, CodeGenTypeEnum.VUE_PROJECT)
                .canonicalRootPath();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (!Files.exists(projectDirectory)) {
            return;
        }
        try (var paths = Files.walk(projectDirectory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void ownerDownloadMustUseValidatedRealProjectPath() throws IOException {
        Files.createDirectories(projectDirectory);
        Files.writeString(projectDirectory.resolve("index.html"), "content");
        User owner = User.builder().id(7L).build();
        when(appPersistenceService.findActiveById(APP_ID)).thenReturn(
                App.builder().id(APP_ID).userId(7L).codeGenType("vue_project").build()
        );
        HttpServletResponse response = mock(HttpServletResponse.class);

        service.download(APP_ID, owner, response);

        verify(projectDownloadService).downloadProjectAsZip(
                projectDirectory.toRealPath().toString(),
                APP_ID.toString(),
                response
        );
    }

    @Test
    void unauthorizedUserMustBeRejectedBeforeDownloadServiceCall() {
        when(appPersistenceService.findActiveById(APP_ID)).thenReturn(
                App.builder().id(APP_ID).userId(7L).codeGenType("vue_project").build()
        );
        HttpServletResponse response = mock(HttpServletResponse.class);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.download(APP_ID, User.builder().id(8L).build(), response)
        );

        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), exception.getCode());
        verify(projectDownloadService, never()).downloadProjectAsZip(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                same(response)
        );
    }

    @Test
    void missingGeneratedProjectMustBeRejectedBeforeDownloadServiceCall() {
        when(appPersistenceService.findActiveById(APP_ID)).thenReturn(
                App.builder().id(APP_ID).userId(7L).codeGenType("vue_project").build()
        );
        HttpServletResponse response = mock(HttpServletResponse.class);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.download(APP_ID, User.builder().id(7L).build(), response)
        );

        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), exception.getCode());
        verify(projectDownloadService, never()).downloadProjectAsZip(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                same(response)
        );
    }
}
