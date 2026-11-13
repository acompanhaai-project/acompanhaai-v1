package com.maiconamaral.acompanhaai.services;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import org.apache.hc.client5.http.DnsResolver;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Coletor das jornadas no navegador: o nome {@code vagas.qa.test} aponta para o servidor local de
 * páginas controladas, em porta aleatória. Todos os outros nomes seguem a política de endereços.
 */
@TestConfiguration(proxyBeanMethods = false)
public class BrowserImportNetwork {

    public static final String HOST = "vagas.qa.test";

    @Bean
    @Primary
    ImportPageCollector browserImportCollector(Clock clock) {
        var policy = new ImportAddressPolicy();
        DnsResolver resolver = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                return HOST.equals(host) ? new InetAddress[] { InetAddress.getLoopbackAddress() } : policy.resolve(host);
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
        return new ImportPageCollector(Duration.ofSeconds(10), 2 * 1024 * 1024, clock, resolver, true);
    }
}
