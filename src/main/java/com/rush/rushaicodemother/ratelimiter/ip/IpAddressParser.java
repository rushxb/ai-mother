package com.rush.rushaicodemother.ratelimiter.ip;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

/**
 * 仅解析 IP 字面量，禁止把不可信输入交给 DNS。
 */
final class IpAddressParser {

    private IpAddressParser() {
    }

    /** 解析{@code Ip}{@code Address}{@code Parser}。 */
    static Optional<ParsedIpAddress> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String candidate = value.trim();
        if (candidate.isEmpty() || candidate.indexOf('%') >= 0) {
            return Optional.empty();
        }

        byte[] addressBytes = candidate.indexOf(':') >= 0
                ? parseIpv6(candidate)
                : parseIpv4(candidate);
        if (addressBytes == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ParsedIpAddress(
                    addressBytes,
                    InetAddress.getByAddress(addressBytes).getHostAddress()
            ));
        } catch (UnknownHostException exception) {
            return Optional.empty();
        }
    }

    /** 解析{@code Ipv4}。 */
    private static byte[] parseIpv4(String candidate) {
        String[] segments = candidate.split("\\.", -1);
        if (segments.length != 4) {
            return null;
        }
        byte[] bytes = new byte[4];
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            if (segment.isEmpty() || segment.length() > 3 || !segment.chars().allMatch(Character::isDigit)) {
                return null;
            }
            int value;
            try {
                value = Integer.parseInt(segment);
            } catch (NumberFormatException exception) {
                return null;
            }
            if (value > 255) {
                return null;
            }
            bytes[index] = (byte) value;
        }
        return bytes;
    }

    /** 解析{@code Ipv6}。 */
    private static byte[] parseIpv6(String candidate) {
        if (!candidate.matches("[0-9A-Fa-f:.]+")) {
            return null;
        }
        try {
            InetAddress address = InetAddress.getByName(candidate);
            return address instanceof Inet6Address ? address.getAddress() : null;
        } catch (UnknownHostException exception) {
            return null;
        }
    }

    record ParsedIpAddress(byte[] bytes, String normalizedValue) {

        ParsedIpAddress {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
