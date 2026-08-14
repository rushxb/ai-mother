package com.rush.rushaicodemother.infrastructure.security;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** 基于 JVM DNS 缓存与系统解析器的生产适配器。 */
@Component
public final class SystemHostAddressResolver implements HostAddressResolver {

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }
}
