package com.rush.rushaicodemother.service.devserver;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 以 UTF-8 和原子替换方式注入可视化编辑器引导脚本。
 */
@Slf4j
@Component
public class VisualEditorBootstrapInjector {

    private static final String BOOTSTRAP_MARKER = "visual-editor-bootstrap";
    private static final int MAX_SCRIPT_LENGTH = 300_000;
    private static final String BOOTSTRAP_SCRIPT = """
            <script id="visual-editor-bootstrap">
            // 仅接受当前父窗口发送的一次、带通道标识的编辑脚本。
            window.addEventListener('message', function(event) {
              if (window.parent === window || event.source !== window.parent) return;
              var data = event.data;
              if (!data || data.type !== 'INJECT_EDIT_SCRIPT') return;
              if (typeof data.channelId !== 'string' || !data.channelId) return;
              if (typeof data.script !== 'string' || data.script.length > %d) return;
              if (document.getElementById('visual-edit-script')) return;
              try {
                var script = document.createElement('script');
                script.id = 'visual-edit-script';
                script.textContent = data.script;
                document.head.appendChild(script);
              } catch (error) {
                console.error('注入编辑脚本失败:', error);
              }
            });
            </script>
            """.formatted(MAX_SCRIPT_LENGTH);

    public void inject(Path projectDirectory) {
        if (projectDirectory == null) {
            return;
        }
        Path indexHtml = projectDirectory.resolve("index.html").normalize();
        if (Files.isSymbolicLink(indexHtml)
                || !Files.isRegularFile(indexHtml, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }

        try {
            Path realProjectDirectory = projectDirectory.toRealPath();
            Path realIndexHtml = indexHtml.toRealPath();
            if (!realIndexHtml.startsWith(realProjectDirectory)) {
                log.warn("拒绝向项目目录外的 index.html 注入引导脚本: {}", realIndexHtml);
                return;
            }
            String content = Files.readString(realIndexHtml, StandardCharsets.UTF_8);
            if (content.contains(BOOTSTRAP_MARKER)) {
                return;
            }
            int headCloseIndex = content.indexOf("</head>");
            if (headCloseIndex < 0) {
                log.warn("index.html 缺少 </head>，跳过可视化编辑器引导脚本注入: {}", realIndexHtml);
                return;
            }
            String updatedContent = content.substring(0, headCloseIndex)
                    + BOOTSTRAP_SCRIPT
                    + content.substring(headCloseIndex);
            atomicReplace(realIndexHtml, updatedContent);
            log.info("已注入可视化编辑器引导脚本: {}", realIndexHtml);
        } catch (IOException exception) {
            log.warn("注入可视化编辑器引导脚本失败: path={}, error={}",
                    indexHtml, exception.getMessage());
        }
    }

    private void atomicReplace(Path target, String content) throws IOException {
        Path tempFile = Files.createTempFile(target.getParent(), ".visual-editor-", ".tmp");
        boolean moved = false;
        try {
            Files.copy(target, tempFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
            Files.writeString(tempFile, content, StandardCharsets.UTF_8);
            try {
                Files.move(tempFile, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(tempFile);
            }
        }
    }
}