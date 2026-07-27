package com.rush.rushaicodemother.service.devserver;

import java.net.URI;

/** 将持久预览所有者节点解析为其内部应用程序端点。 */
public interface DevServerNodeRouteResolver {

    URI resolve(String nodeId);
}
