package com.rush.rushaicodemother.config;

import com.rush.rushaicodemother.controller.app.websocket.InternalDevServerWebSocketHandshakeInterceptor;
import com.rush.rushaicodemother.controller.app.websocket.PublicDevServerWebSocketHandshakeInterceptor;
import com.rush.rushaicodemother.service.devserver.DevServerPreviewPaths;
import com.rush.rushaicodemother.service.devserver.DevServerWebSocketProxyHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** 注册同源浏览器和签名的节点到节点 Vite HMR 端点。 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class DevServerWebSocketConfiguration implements WebSocketConfigurer {

    private final DevServerWebSocketProxyHandler proxyHandler;
    private final PublicDevServerWebSocketHandshakeInterceptor publicInterceptor;
    private final InternalDevServerWebSocketHandshakeInterceptor internalInterceptor;

    public DevServerWebSocketConfiguration(
            DevServerWebSocketProxyHandler proxyHandler,
            PublicDevServerWebSocketHandshakeInterceptor publicInterceptor,
            InternalDevServerWebSocketHandshakeInterceptor internalInterceptor
    ) {
        this.proxyHandler = proxyHandler;
        this.publicInterceptor = publicInterceptor;
        this.internalInterceptor = internalInterceptor;
    }

    /**
 * 注册 WebSocket 处理器。
 *
 * @param registry 目标注册器
 */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(proxyHandler, DevServerPreviewPaths.PUBLIC_PROXY_PREFIX + "**")
                .addInterceptors(publicInterceptor)
                // CSP sandbox 页面会把 WebSocket Origin 序列化为 null；授权仍由 publicInterceptor 完成。
                .setAllowedOrigins("null");
        registry.addHandler(proxyHandler, DevServerPreviewPaths.INTERNAL_PROXY_PREFIX + "**")
                .addInterceptors(internalInterceptor);
    }
}
