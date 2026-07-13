package com.rush.rushaicodemother.ratelimiter.ip;

import java.util.Optional;

/**
 * IPv4/IPv6 CIDR 匹配器。
 */
final class CidrBlock {

    private final byte[] networkAddress;
    private final int prefixLength;

    private CidrBlock(byte[] networkAddress, int prefixLength) {
        this.networkAddress = networkAddress;
        this.prefixLength = prefixLength;
    }

    static CidrBlock parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("可信代理 CIDR 不能为空");
        }
        String[] parts = value.trim().split("/", -1);
        if (parts.length > 2) {
            throw new IllegalArgumentException("可信代理 CIDR 格式无效: " + value);
        }

        Optional<IpAddressParser.ParsedIpAddress> parsedAddress = IpAddressParser.parse(parts[0]);
        if (parsedAddress.isEmpty()) {
            throw new IllegalArgumentException("可信代理 CIDR 地址无效: " + value);
        }
        byte[] addressBytes = parsedAddress.orElseThrow().bytes();
        int maximumPrefixLength = addressBytes.length * Byte.SIZE;
        int parsedPrefixLength = parts.length == 1
                ? maximumPrefixLength
                : parsePrefixLength(parts[1], maximumPrefixLength, value);
        return new CidrBlock(mask(addressBytes, parsedPrefixLength), parsedPrefixLength);
    }

    boolean contains(IpAddressParser.ParsedIpAddress address) {
        byte[] candidate = address.bytes();
        if (candidate.length != networkAddress.length) {
            return false;
        }
        byte[] maskedCandidate = mask(candidate, prefixLength);
        return java.util.Arrays.equals(networkAddress, maskedCandidate);
    }

    private static int parsePrefixLength(String value, int maximum, String source) {
        try {
            int prefix = Integer.parseInt(value);
            if (prefix < 0 || prefix > maximum) {
                throw new IllegalArgumentException("可信代理 CIDR 前缀超出范围: " + source);
            }
            return prefix;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("可信代理 CIDR 前缀无效: " + source, exception);
        }
    }

    private static byte[] mask(byte[] address, int prefixLength) {
        byte[] masked = address.clone();
        int completeBytes = prefixLength / Byte.SIZE;
        int remainingBits = prefixLength % Byte.SIZE;
        if (completeBytes < masked.length && remainingBits > 0) {
            int bitMask = 0xFF << (Byte.SIZE - remainingBits);
            masked[completeBytes] = (byte) (masked[completeBytes] & bitMask);
            completeBytes++;
        }
        for (int index = completeBytes; index < masked.length; index++) {
            masked[index] = 0;
        }
        return masked;
    }
}
