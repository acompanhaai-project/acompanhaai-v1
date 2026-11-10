package com.maiconamaral.acompanhaai.services;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.apache.hc.client5.http.DnsResolver;

final class ImportAddressPolicy implements DnsResolver {

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        var addresses = InetAddress.getAllByName(host);
        for (var address : addresses) {
            if (!isPublic(address)) {
                throw new Blocked();
            }
        }
        return addresses;
    }

    @Override
    public String resolveCanonicalHostname(String host) {
        return host;
    }

    static boolean isPublic(InetAddress address) {
        if (
            address.isAnyLocalAddress() ||
            address.isLoopbackAddress() ||
            address.isLinkLocalAddress() ||
            address.isSiteLocalAddress() ||
            address.isMulticastAddress()
        ) {
            return false;
        }
        var bytes = address.getAddress();
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        int third = bytes[2] & 0xff;
        if (address instanceof Inet4Address) {
            return !(
                first == 0 ||
                (first == 100 && second >= 64 && second <= 127) ||
                (first == 192 && second == 0 && (third == 0 || third == 2)) ||
                (first == 192 && second == 88 && third == 99) ||
                (first == 198 && (second == 18 || second == 19)) ||
                (first == 198 && second == 51 && third == 100) ||
                (first == 203 && second == 0 && third == 113) ||
                first >= 240
            );
        }
        // Fora de 2000::/3 ficam ULA, NAT64 e formas com IPv4 embutido.
        if ((first & 0xe0) != 0x20) {
            return false;
        }
        boolean protocolAssignments = first == 0x20 && second == 0x01 && third <= 0x01;
        boolean documentation = first == 0x20 && second == 0x01 && third == 0x0d && (bytes[3] & 0xff) == 0xb8;
        boolean documentationV2 = first == 0x3f && second == 0xff && third <= 0x0f;
        boolean sixToFour = first == 0x20 && second == 0x02;
        return !(protocolAssignments || documentation || documentationV2 || sixToFour);
    }

    static final class Blocked extends UnknownHostException {
    }
}
