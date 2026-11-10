package com.maiconamaral.acompanhaai.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ImportPageReaderTest {

    @Test
    void keepsOnlyTheVisibleText() {
        var content = ImportPageReader.read("""
            <!DOCTYPE html>
            <html>
              <head>
                <title>Vaga | Portal</title>
                <style>p > a { color: red; }</style>
                <script>if (a < b) { document.write("<p>oculto</p>"); }</script>
              </head>
              <body>
                <!-- <p>comentário</p> -->
                <h1>Pessoa&nbsp;desenvolvedora   Java</h1>
                <p title="a > b">Sal&aacute;rio &lt; R$ 9.000 <b>CLT</b></p>
                <ul><li>Spring</li><li>PostgreSQL</li></ul>
                <noscript>Ative o JavaScript</noscript>
              </body>
            </html>
            """);

        assertThat(content.text()).isEqualTo(
            "Pessoa desenvolvedora Java\n\nSalário < R$ 9.000 CLT\n\nSpring\n\nPostgreSQL"
        );
        assertThat(content.scripted()).isTrue();
        assertThat(content.passwordField()).isFalse();
        assertThat(content.jobPosting()).isEmpty();
    }

    @Test
    void readsTheJobPostingPublishedAsStructuredData() {
        var content = ImportPageReader.read("""
            <script type="application/ld+json">{ inválido </script>
            <script data-type="x" type='application/ld+json'>
            {
              "@context": "https://schema.org",
              "@graph": [
                { "@type": "Organization", "name": "Portal" },
                {
                  "@type": ["Thing", "JobPosting"],
                  "title": "  Pessoa desenvolvedora\\n Java ",
                  "hiringOrganization": { "@type": "Organization", "name": "Empresa Exemplo" },
                  "description": "&lt;p&gt;&lt;b&gt;Spring&lt;/b&gt; &amp;amp; SQL&lt;/p&gt;&lt;p&gt;Remoto&lt;/p&gt;",
                  "jobLocation": [
                    { "@type": "Place", "address": { "addressLocality": "Curitiba", "addressRegion": "PR" } }
                  ],
                  "jobLocationType": "TELECOMMUTE",
                  "baseSalary": {
                    "@type": "MonetaryAmount",
                    "currency": "BRL",
                    "value": { "@type": "QuantitativeValue", "value": 8500.50, "unitText": "MONTH" }
                  }
                }
              ]
            }
            </script>
            <p>Vaga publicada no portal.</p>
            """);

        assertThat(content.jobPosting()).isEqualTo(Map.of(
            "title", "Pessoa desenvolvedora Java",
            "companyName", "Empresa Exemplo",
            "description", "Spring & SQL\n\nRemoto",
            "location", "Curitiba, PR",
            "workMode", "REMOTE",
            "salary", new BigDecimal("8500.5"),
            "salaryCurrency", "BRL",
            "salaryPeriod", "MONTH"
        ));
        assertThat(content.text()).isEqualTo("Vaga publicada no portal.");
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "{ \"currency\": \"USD\", \"value\": { \"value\": 5000, \"unitText\": \"MONTH\" } }",
            "{ \"currency\": \"BRL\", \"value\": { \"minValue\": 5000, \"maxValue\": 7000, \"unitText\": \"MONTH\" } }",
            "{ \"currency\": \"BRL\", \"value\": { \"value\": 5000 } }",
            "{ \"currency\": \"BRL\", \"value\": { \"value\": \"5.000,00\", \"unitText\": \"MONTH\" } }",
            "{ \"currency\": \"BRL\", \"value\": { \"value\": 0, \"unitText\": \"MONTH\" } }",
            "{ \"value\": { \"value\": 5000, \"unitText\": \"MONTH\" } }",
            "{ \"currency\": \"BRL\", \"value\": { \"value\": 5000, \"unitText\": \"A combinar\" } }",
            "5000"
        }
    )
    void leavesTheSalaryOutWithoutExplicitValueCurrencyAndPeriod(String baseSalary) {
        var content = ImportPageReader.read(
            "<script type=\"application/ld+json\">" +
            "{ \"@type\": \"JobPosting\", \"title\": \"Analista\", \"baseSalary\": " +
            baseSalary +
            " }</script>"
        );

        assertThat(content.jobPosting()).isEqualTo(Map.of("title", "Analista"));
    }

    @Test
    void acceptsAnExactSalaryDeclaredAsRange() {
        var content = ImportPageReader.read("""
            <script type="application/ld+json">
            [{
              "@type": "JobPosting",
              "title": "Analista",
              "hiringOrganization": "Empresa Exemplo",
              "jobLocation": { "address": "Recife" },
              "baseSalary": {
                "currency": "brl",
                "value": { "minValue": "4000.00", "maxValue": 4000, "unitText": "year" }
              }
            }]
            </script>
            """);

        assertThat(content.jobPosting()).isEqualTo(Map.of(
            "title", "Analista",
            "companyName", "Empresa Exemplo",
            "location", "Recife",
            "salary", new BigDecimal("4000"),
            "salaryCurrency", "BRL",
            "salaryPeriod", "YEAR"
        ));
    }

    @Test
    void ignoresStructuredDataThatIsNotAJobPosting() {
        var content = ImportPageReader.read("""
            <script type="application/ld+json">{ "@type": "Article", "title": "Notícia" }</script>
            <script type="application/ld+json">{ "@type": "JobPosting", "identifier": 7 }</script>
            <script type="application/json">{ "@type": "JobPosting", "title": "Oculta" }</script>
            <form><input name="email"><input data-type="text" TYPE=password></form>
            """);

        assertThat(content.jobPosting()).isEmpty();
        assertThat(content.passwordField()).isTrue();
    }

    @Test
    void toleratesBrokenMarkupAndRemovesControlCharacters() {
        assertThat(ImportPageReader.read("<p>Vaga <b>Java<script>var a = 1;").text()).isEqualTo("Vaga Java");
        assertThat(ImportPageReader.read("a < b e c > d <").text()).isEqualTo("a < b e c > d <");
        assertThat(ImportPageReader.read("<a href=\"x").text()).isEmpty();
        assertThat(ImportPageReader.clean(" Vaga\u0000 Java \r\n\t\r\n\n\n Remoto\u00A0 ")).isEqualTo(
            "Vaga Java\n\nRemoto"
        );
        assertThat(ImportPageReader.clean("a".repeat(ImportPageReader.MAX_TEXT + 10))).hasSize(
            ImportPageReader.MAX_TEXT
        );
    }
}
