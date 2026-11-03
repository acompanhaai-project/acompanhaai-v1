package com.maiconamaral.acompanhaai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.maiconamaral.acompanhaai.services.JobUrlService;
import com.maiconamaral.acompanhaai.services.exceptions.InvalidDataException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class JobUrlServiceTest {

    private final JobUrlService urls = new JobUrlService();

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
            " HTTPS://Jobs.Example.Test:443 |https://jobs.example.test/",
            "http://EXAMPLE.TEST:80/jobs|http://example.test/jobs",
            "https://example.test:8443/jobs|https://example.test:8443/jobs",
            "https://example.test:/|https://example.test/",
            "https://EXAMPLE.TEST/Jobs/ABC%2F12?jobId=A%2FB&sig=a+b#Job-1|https://example.test/Jobs/ABC%2F12?jobId=A%2FB&sig=a+b#Job-1",
            "https://example.test/jobs?|https://example.test/jobs?",
            "https://example.test/jobs#|https://example.test/jobs#",
            "http://[::1]:80|http://[::1]/",
            "http://[FE80::A%25Eth0]/|http://[fe80::a%25Eth0]/"
        }
    )
    void normalizesOnlyTransportEquivalencesAndPreservesRawIdentifiers(String original, String expected) {
        assertThat(urls.normalize(original)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "https://example.test/jobs/AbC",
            "http://example.test/jobs/abc",
            "https://example.test/jobs/abc/",
            "https://example.test/jobs/abc?jobId=1",
            "https://example.test/jobs/abc#job-1",
            "https://example.test/jobs/abc?utm_source=feed",
            "https://example.test/jobs/abc?b=2&a=1",
            "https://example.test/jobs/a%2Fbc",
            "https://example.test/jobs/../jobs/abc"
        }
    )
    void preservesDifferencesThatCouldIdentifyAnotherOpportunity(String other) {
        assertThat(urls.normalize(other)).isNotEqualTo(urls.normalize("https://example.test/jobs/abc"));
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "ftp://example.test/jobs",
            "https:///jobs",
            "https://example.test:70000/jobs",
            "https://example.test:0/jobs",
            "https://example.test:abc/jobs",
            "https://example.test/%ZZ",
            "https://example.test/a b",
            "https://user:fake-password@example.test/jobs",
            "https://user@example.test/jobs"
        }
    )
    void rejectsMalformedLinksAndEmbeddedCredentialsWithoutEchoingTheInput(String value) {
        assertThatThrownBy(() -> urls.normalize(value))
            .isInstanceOf(InvalidDataException.class)
            .hasMessage("Informe uma URL HTTP ou HTTPS válida, com host e sem credenciais embutidas.");
    }

    @Test
    void absentLinksRemainNullAndHashUsesSha256() {
        assertThat(urls.normalize(null)).isNull();
        assertThat(urls.normalize("   ")).isNull();
        assertThat(urls.hash(null)).isNull();
        assertThat(urls.hash("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
