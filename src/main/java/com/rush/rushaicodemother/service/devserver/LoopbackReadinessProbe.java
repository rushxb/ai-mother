package com.rush.rushaicodemother.service.devserver;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/** 通过 IPv4 回环地址探测 Dev Server 监听端口。 */
@Component
public class LoopbackReadinessProbe {

    private static final String LOOPBACK_ADDRESS = "127.0.0.1";
    private static final int CONNECT_TIMEOUT_MILLIS = 200;

    /**
 * 判断就绪是否满足约束。
 *
 * @param port 端口
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    public boolean isReady(int port) {
        if (port < 1 || port > 65535) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(LOOPBACK_ADDRESS, port), CONNECT_TIMEOUT_MILLIS);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }
}