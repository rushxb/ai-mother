package com.rush.rushaicodemother.controller;

import com.rush.rushaicodemother.service.artifact.DeploymentArtifactResourceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** 生成结果静态资源访问控制器。 */
@RestController
@RequestMapping("/static")
public class StaticResourceController {

    private final DeploymentArtifactResourceService deploymentArtifactResourceService;

    public StaticResourceController(DeploymentArtifactResourceService deploymentArtifactResourceService) {
        this.deploymentArtifactResourceService = Objects.requireNonNull(
                deploymentArtifactResourceService,
                "deploymentArtifactResourceService must not be null"
        );
    }

    /**
     * 提供已提交部署产物的静态资源访问，并对目录请求补充 index.html。
     */
    @GetMapping("/{deployKey}/**")
    public ResponseEntity<Resource> serveStaticResource(
            @PathVariable String deployKey,
            HttpServletRequest request
    ) {
        String mappedPath = (String) request.getAttribute(
                HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE
        );
        String deploymentPath = "/static/" + deployKey;
        if (mappedPath == null) {
            return ResponseEntity.badRequest().build();
        }
        if (mappedPath.equals(deploymentPath)) {
            HttpHeaders headers = new HttpHeaders();
            headers.setLocation(java.net.URI.create(request.getRequestURI() + "/"));
            return new ResponseEntity<>(headers, HttpStatus.MOVED_PERMANENTLY);
        }

        String resourcePrefix = deploymentPath + "/";
        if (!mappedPath.startsWith(resourcePrefix)) {
            return ResponseEntity.badRequest().build();
        }
        String relativePath = mappedPath.substring(resourcePrefix.length());
        if (relativePath.isEmpty() || relativePath.endsWith("/")) {
            relativePath += "index.html";
        }

        try {
            Path resourcePath = deploymentArtifactResourceService.resolve(deployKey, relativePath);
            MediaType contentType = resolveContentType(resourcePath);
            return ResponseEntity.ok()
                    .contentType(contentType)
                    .cacheControl(CacheControl.noStore())
                    .header("X-Content-Type-Options", "nosniff")
                    .body(new FileSystemResource(resourcePath));
        } catch (NoSuchFileException exception) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().build();
        } catch (IOException exception) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private MediaType resolveContentType(Path resourcePath) {
        String fileName = resourcePath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".html")) {
            return MediaType.parseMediaType("text/html;charset=UTF-8");
        }
        if (fileName.endsWith(".css")) {
            return MediaType.parseMediaType("text/css;charset=UTF-8");
        }
        if (fileName.endsWith(".js") || fileName.endsWith(".mjs")) {
            return MediaType.parseMediaType("application/javascript;charset=UTF-8");
        }
        if (fileName.endsWith(".json")) {
            return MediaType.APPLICATION_JSON;
        }
        return MediaTypeFactory.getMediaType(fileName)
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
    }
}
