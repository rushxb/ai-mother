package com.rush.rushaicodemother.infrastructure.security;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** 可替换的主机地址解析接口；生产实现使用系统 DNS。 */
@FunctionalInterface
public interface HostAddressResolver {

    InetAddress[] resolve(String host) throws UnknownHostException;
}
