package com.rush.rushaicodemother.service.devserver;

import java.net.URI;

/** Resolves a durable Preview owner node to its internal application endpoint. */
public interface DevServerNodeRouteResolver {

    URI resolve(String nodeId);
}
