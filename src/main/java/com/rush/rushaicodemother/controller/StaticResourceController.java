package com.rush.rushaicodemother.controller;

import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.infrastructure.filesystem.SecurePathResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

/**
 * 生成结果静态资源访问控制器。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/static")
public class StaticResourceController {

    private static final Path PREVIEW_ROOT = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR);

    private final SecurePathResolver securePathResolver;

    /**
     * 提供生成结果的静态资源访问，并对目录请求补充 index.html。
     */
    @GetMapping("/{deployKey}/**")
    public ResponseEntity<Resource> serveStaticResource(@PathVariable String deployKey,
                                                         HttpServletRequest request) {
        String mappedPath = (String) request.getAttribute(
                HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE
        );
        String prefix = "/static/" + deployKey;
        if (mappedPath == null || !mappedPath.startsWith(prefix)) {
            return ResponseEntity.badRequest().build();
        }

        String requestedPath = mappedPath.substring(prefix.length());
        if (requestedPath.isEmpty()) {
            HttpHeaders headers = new HttpHeaders();
            headers.setLocation(java.net.URI.create(request.getRequestURI() + "/"));
            return new ResponseEntity<>(headers, HttpStatus.MOVED_PERMANENTLY);
        }

        String relativePath = requestedPath.startsWith("/")
                ? requestedPath.substring(1)
                : requestedPath;
        if (relativePath.isEmpty() || relativePath.endsWith("/")) {
            relativePath += "index.html";
        }

        try {
            Path resourcePath = securePathResolver.resolveRegularFile(
                    PREVIEW_ROOT,
                    deployKey,
                    relativePath
            );
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
        if (fileName.endsWith(".svg")) {
            return MediaType.parseMediaType("image/svg+xml");
        }
        if (fileName.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
