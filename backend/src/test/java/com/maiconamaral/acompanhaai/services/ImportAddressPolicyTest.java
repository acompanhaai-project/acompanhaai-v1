package com.maiconamaral.acompanhaai.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ImportAddressPolicyTest {

    @ParameterizedTest
    @ValueSource(
        strings = {
            "0.0.0.0",
            "0.1.2.3",
            "10.0.0.1",
            "100.64.0.1",
            "127.0.0.1",
            "169.254.169.254",
            "172.16.0.1",
            "192.0.0.1",
            "192.0.2.1",
            "192.168.1.1",
            "198.18.0.1",
            "198.51.100.1",
            "203.0.113.1",
            "224.0.0.1",
            "240.0.0.1",
            "255.255.255.255",
            "::",
            "::1",
            "::7f00:1",
            "::ffff:10.0.0.1",
            "::ffff:127.0.0.1",
            "64:ff9b::7f00:1",
            "2001::1",
            "2001:db8::1",
            "2002:7f00:1::1",
            "3fff::1",
            "fc00::1",
            "fd12:3456::1",
            "fe80::1",
            "ff02::1"
        }
    )
    void blocksLocalPrivateAndReservedDestinations(String literal) throws Exception {
        assertThat(ImportAddressPolicy.isPublic(InetAddress.getByName(literal))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
        strings = { "1.1.1.1", "8.8.8.8", "200.160.2.3", "2001:4860:4860::8888", "2606:4700:4700::1111", "2804:14c::1" }
    )
    void allowsPublicDestinations(String literal) throws Exception {
        assertThat(ImportAddressPolicy.isPublic(InetAddress.getByName(literal))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = { "localhost", "127.0.0.1", "[::1]", "10.0.0.1" })
    void refusesToResolveNonPublicHosts(String host) {
        var policy = new ImportAddressPolicy();

        assertThatThrownBy(() -> policy.resolve(host)).isInstanceOf(ImportAddressPolicy.Blocked.class);
    }
}
