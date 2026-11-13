package com.maiconamaral.acompanhaai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.chromium.ChromiumDriver;
import org.openqa.selenium.chromium.ChromiumOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeDriverService;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import com.maiconamaral.acompanhaai.services.BrowserImportNetwork;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.config.import="
)
@ActiveProfiles("test")
@Import(BrowserImportNetwork.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessJourneysBrowserIT {

    private static final String INITIAL_PASSWORD = "TesteInicial123!";
    private static final String NEW_PASSWORD = "TesteAlterada456!";
    private static final Pattern RESET_LINK = Pattern.compile(
        "http://127\\.0\\.0\\.1:\\d+/reset-password#token=[A-Za-z0-9_-]+"
    );

    @LocalServerPort
    private int apiPort;

    private WebDriver browser;
    private String axeSource;
    private WebDriverWait wait;
    private String desiredTheme;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        BrowserEnvironment.properties(registry);
    }

    @BeforeAll
    void startApplication() throws Exception {
        BrowserEnvironment.startFrontend(apiPort);
    }

    @AfterEach
    void closeBrowser() {
        if (browser != null) {
            browser.quit();
            browser = null;
        }
    }

    @AfterAll
    void stopApplication() {
        BrowserEnvironment.stop();
    }

    @ParameterizedTest(name = "Jornada completa: {0}px, tema {1}")
    @CsvSource({ "1440, light", "1440, dark", "390, light", "390, dark" })
    void accountLifecycle(int width, String theme) throws Exception {
        openBrowser(width, theme);
        String email = "qa.browser." + UUID.randomUUID() + "@example.test";
        register(email);

        visit("/cadastro");
        fillRegistration(email);
        submit();
        visible("#erro-email");
        assertEquals("cad-email", focusedId());
        assertEquals("true", visible("#cad-email").getDomAttribute("aria-invalid"));

        String destination = "/conta?origem=qa#retorno";
        visit(destination);
        visible("#email");
        assertEquals("/entrar", path());
        assertTrue(browser.getCurrentUrl().contains("returnUrl="));
        enterEmail(email);
        loginPassword("SenhaIncorreta123!");
        wait.until(ExpectedConditions.textToBePresentInElementLocated(
            By.cssSelector(".alert"), "E-mail ou senha inválidos."
        ));
        assertEquals("", visible("#login-senha").getDomProperty("value"));
        assertEquals("login-senha", focusedId());
        loginPassword(INITIAL_PASSWORD);
        heading("Acesso confirmado");
        wait.until(driver -> driver.getCurrentUrl().endsWith(destination));
        assertEquals(0L, ((Number) script(
            "return localStorage.length + sessionStorage.length"
        )).longValue());
        assertFalse(browser.manage().getCookies().isEmpty());
        assertTrue(browser.manage().getCookieNamed("JSESSIONID").isHttpOnly());
        assertFalse(((String) script("return document.cookie")).contains("JSESSIONID"));
        assertNoOverflow();
        screenshot("account-" + width + "-" + theme + ".png");

        browser.navigate().refresh();
        heading("Acesso confirmado");
        assertTrue(browser.getCurrentUrl().endsWith(destination));
        String originalTab = browser.getWindowHandle();
        browser.switchTo().newWindow(org.openqa.selenium.WindowType.TAB);
        visit("/entrar");
        heading("Acesso confirmado");
        button("Sair").click();
        visible("#email");
        assertEquals("/entrar", path());
        browser.close();
        browser.switchTo().window(originalTab);
        browser.navigate().refresh();
        visible("#email");
        assertEquals("/entrar", path());
        signIn(email, INITIAL_PASSWORD);
        heading("Acesso confirmado");

        BrowserEnvironment.MAIL.purgeEmailFromAllMailboxes();
        browser.switchTo().newWindow(org.openqa.selenium.WindowType.TAB);
        visit("/recuperar-senha");
        type("rec-email", email);
        submit();
        heading("Verifique seu e-mail");
        assertFalse(buttonStarting("Reenviar link").isEnabled());
        assertTrue(BrowserEnvironment.MAIL.waitForIncomingEmail(15_000, 1), "SMTP não recebeu a mensagem.");
        String content = BrowserEnvironment.MAIL.getReceivedMessages()[0].getContent().toString();
        var matcher = RESET_LINK.matcher(content);
        assertTrue(matcher.find(), "E-mail não contém link de redefinição local.");
        String link = matcher.group();
        browser.get(link);
        heading("Crie uma nova senha");
        wait.until(driver -> !driver.getCurrentUrl().contains("token="));
        type("red-senha", NEW_PASSWORD);
        type("red-confirma", NEW_PASSWORD);
        submit();
        heading("Senha redefinida!");

        browser.switchTo().window(originalTab);
        browser.navigate().refresh();
        visible("#email");
        assertEquals("/entrar", path());
        enterEmail(email);
        loginPassword(INITIAL_PASSWORD);
        wait.until(ExpectedConditions.textToBePresentInElementLocated(
            By.cssSelector(".alert"), "E-mail ou senha inválidos."
        ));
        loginPassword(NEW_PASSWORD);
        heading("Acesso confirmado");
        button("Sair").click();
        visible("#email");

        browser.get(link);
        heading("Crie uma nova senha");
        type("red-senha", "OutraSenha789!");
        type("red-confirma", "OutraSenha789!");
        submit();
        heading("Link inválido ou expirado");
        assertNoOverflow();
    }

    @ParameterizedTest(name = "Teclado e validação: {0}px, tema {1}")
    @CsvSource({ "1440, light", "1440, dark", "390, light", "390, dark" })
    void keyboardAndFieldErrors(int width, String theme) throws Exception {
        openBrowser(width, theme);
        visit("/cadastro");
        submit();
        visible("#erro-nome");
        assertEquals("cad-nome", focusedId());
        assertEquals("true", visible("#cad-nome").getDomAttribute("aria-invalid"));
        type("cad-nome", "Maria de Teste");
        browser.switchTo().activeElement().sendKeys(Keys.TAB);
        assertEquals("cad-email", focusedId());
        browser.switchTo().activeElement().sendKeys("invalido", Keys.TAB);
        assertEquals("cad-senha", focusedId());
        assertEquals("true", visible("#cad-email").getDomAttribute("aria-invalid"));
        visible("#erro-email");
        type("cad-email", "qa.keyboard." + UUID.randomUUID() + "@example.test");
        type("cad-senha", INITIAL_PASSWORD);
        type("cad-confirma", "Diferente123!");
        submit();
        visible("#erro-confirma");
        assertEquals("cad-confirma", focusedId());
        assertTrue(
            visible("#cad-confirma").getDomAttribute("aria-describedby").contains("erro-confirma")
        );
        assertNoOverflow();

        visit("/entrar");
        type("email", "qa.keyboard@example.test");
        visible("#email").sendKeys(Keys.ENTER);
        visible("#login-senha");
        wait.until(driver -> "login-senha".equals(focusedId()));
        assertEquals("login-senha", focusedId());
        type("login-senha", INITIAL_PASSWORD);
        visible("#login-senha").sendKeys(Keys.TAB);
        WebElement toggle = browser.switchTo().activeElement();
        assertEquals("Mostrar senha", toggle.getDomAttribute("aria-label"));
        toggle.sendKeys(Keys.SPACE);
        wait.until(driver -> "text".equals(visible("#login-senha").getDomAttribute("type")));
        assertEquals("true", toggle.getDomAttribute("aria-pressed"));

        visit("/recuperar-senha");
        type("rec-email", "invalido");
        visible("#rec-email").sendKeys(Keys.TAB);
        visible("#erro-email");
        assertEquals("true", visible("#rec-email").getDomAttribute("aria-invalid"));
        assertNoOverflow();
        visit("/redefinir-senha");
        heading("Link inválido ou expirado");
        assertNoOverflow();
        screenshot("invalid-link-" + width + "-" + theme + ".png");
    }

    @ParameterizedTest(name = "Perfil: {0}px, tema {1}")
    @CsvSource({ "1440, light", "1440, dark", "390, light", "390, dark" })
    void profileLifecycle(int width, String theme) throws Exception {
        openBrowser(width, theme);
        String email = "qa.perfil." + UUID.randomUUID() + "@example.test";
        String updatedEmail = "editado." + email;
        register(email);

        visit("/conta/perfil");
        visible("#email");
        assertEquals("/entrar", path());
        assertEquals("returnUrl=/conta/perfil", URI.create(browser.getCurrentUrl()).getQuery());
        signIn(email, INITIAL_PASSWORD);
        heading("Meu perfil");
        assertEquals(email, visible("#profile-email").getDomProperty("value"));
        assertEquals("MT", visible(".avatar").getText());
        visible(".back").sendKeys(Keys.TAB);
        assertEquals("profile-photo", focusedId());
        assertTrue((Boolean) script("return document.activeElement.matches(':focus-visible')"));

        type("profile-name", "Pessoa Editada");
        type("profile-email", "invalido");
        button("Salvar dados").click();
        message(".settings [role='alert']", "Confira o nome e o e-mail.");
        assertEquals("true", visible("#profile-email").getDomAttribute("aria-invalid"));
        type("profile-email", updatedEmail.toUpperCase(Locale.ROOT));
        button("Salvar dados").click();
        message(".settings [role='status']", "Dados atualizados.");
        assertEquals(updatedEmail, visible("#profile-email").getDomProperty("value"));

        verifyPhotoChanges(width, theme);
        changeProfilePassword(updatedEmail);
        deleteProfile(updatedEmail);
    }

    @ParameterizedTest(name = "Quadro: {0}px, tema {1}")
    @CsvSource({ "1440, light", "1440, dark", "390, light", "390, dark" })
    void boardLifecycle(int width, String theme) throws Exception {
        openBrowser(width, theme);
        String email = "qa.quadro." + UUID.randomUUID() + "@example.test";
        register(email);

        visit("/candidaturas");
        visible("#email");
        assertEquals("/entrar", path());
        assertEquals("returnUrl=/candidaturas", URI.create(browser.getCurrentUrl()).getQuery());
        signIn(email, INITIAL_PASSWORD);
        heading("Minhas candidaturas");
        message(".board-message", "Você ainda não tem candidaturas cadastradas.");

        Map<String, Object> job = Map.of(
            "title", "Pessoa desenvolvedora Java",
            "companyName", "Empresa Teste",
            "description", "Desenvolvimento de APIs",
            "hardSkills", List.of("Java"),
            "softSkills", List.of("Comunicação")
        );
        Map<?, ?> initial = seedApplication(job, "APPLIED");
        Map<String, String> stages = Map.of(
            "SCREENING", "screening",
            "INTERVIEW", "interview",
            "OFFER", "offer",
            "SAVED", "saved",
            "HIRED", "finished",
            "REJECTED", "finished",
            "WITHDRAWN", "finished"
        );
        Map<Long, String> expectedLanes = new LinkedHashMap<>();
        long id = ((Number) initial.get("id")).longValue();
        expectedLanes.put(id, "applied");
        for (var stage : stages.entrySet()) {
            Map<?, ?> created = seedApplication(Map.of("title", "Vaga " + stage.getKey()), stage.getKey());
            expectedLanes.put(((Number) created.get("id")).longValue(), stage.getValue());
        }

        browser.navigate().refresh();
        message(".count", "Mostrando 8 de 8 candidaturas");
        for (var expected : expectedLanes.entrySet()) {
            assertEquals(expected.getValue(), laneOf(expected.getKey()));
        }
        assertEquals(4, browser.findElements(By.cssSelector(".lanes:not(.secondary) .lane")).size());
        message("[data-lane='finished']", "Contratação");
        message("[data-lane='finished']", "Rejeitada");
        message("[data-lane='finished']", "Desistência");
        assertNoOverflow();
        assertAccessible("quadro " + width + "px " + theme);
        screenshot("board-" + width + "-" + theme + ".png");

        String resource = "/api/applications/" + id;
        String card = "#application-" + id;
        WebElement destination = visible(card + " select");
        destination.sendKeys(Keys.HOME, Keys.ARROW_DOWN, Keys.ARROW_DOWN, Keys.ARROW_DOWN);
        assertEquals("INTERVIEW", destination.getDomProperty("value"));
        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector(card + " .move")));
        destination.sendKeys(Keys.TAB);
        WebElement move = browser.switchTo().activeElement();
        assertEquals("Mover", move.getText());

        String freshNotes = "Retorno registrado em outra aba.";
        browserApi("PUT", resource, Map.of(
            "job", job,
            "status", "SCREENING",
            "appliedOn", initial.get("appliedOn"),
            "notes", freshNotes,
            "version", initial.get("version")
        ), 200);
        move.sendKeys(Keys.ENTER);
        message(card + " [role='alert']", "O registro foi alterado.");
        assertAccessible("conflito no quadro " + width + "px " + theme);
        assertEquals("applied", laneOf(id));
        assertEquals("INTERVIEW", destination.getDomProperty("value"));
        assertFalse(visible(card + " .move").isEnabled());

        button("Atualizar candidatura").click();
        message(".announcement", "Candidatura atualizada.");
        assertEquals("screening", laneOf(id));
        assertEquals("INTERVIEW", visible(card + " select").getDomProperty("value"));
        visible(card + " .move").click();
        message(".announcement", "Candidatura movida para Entrevista.");
        assertEquals("interview", laneOf(id));
        wait.until(driver -> ("application-" + id).equals(focusedId()));

        Map<?, ?> persisted = (Map<?, ?>) browserApi("GET", resource, null, 200);
        assertEquals("INTERVIEW", persisted.get("status"));
        assertEquals(freshNotes, persisted.get("notes"));
        Map<?, ?> persistedJob = (Map<?, ?>) persisted.get("job");
        assertEquals(job.get("hardSkills"), persistedJob.get("hardSkills"));
        assertEquals(job.get("description"), persistedJob.get("description"));

        destination = visible(card + " select");
        destination.sendKeys(Keys.HOME, Keys.ARROW_DOWN);
        message(card + " .hint", "Mover para Salvas remove a data da candidatura.");
        visible(card + " .move").click();
        message(".announcement", "Candidatura movida para Salva.");
        assertEquals("saved", laneOf(id));
        Map<?, ?> saved = (Map<?, ?>) browserApi("GET", resource, null, 200);
        assertEquals("SAVED", saved.get("status"));
        assertNull(saved.get("appliedOn"));
        List<?> history = (List<?>) browserApi("GET", resource + "/history", null, 200);
        assertEquals(List.of("APPLIED", "SCREENING", "INTERVIEW", "SAVED"), history.stream()
            .map(entry -> ((Map<?, ?>) entry).get("toStatus"))
            .toList());
    }

    @ParameterizedTest(name = "Gerenciar candidatura: {0}px, tema {1}")
    @CsvSource({ "1440, light", "1440, dark", "390, light", "390, dark" })
    void applicationLifecycle(int width, String theme) throws Exception {
        openBrowser(width, theme);
        String email = "qa.candidatura." + UUID.randomUUID() + "@example.test";
        String url = "https://example.test/vagas/" + UUID.randomUUID();
        String title = "Pessoa desenvolvedora Java";
        register(email);

        visit("/candidaturas/nova");
        visible("#email");
        assertEquals("/entrar", path());
        signIn(email, INITIAL_PASSWORD);
        heading("Nova candidatura");
        assertEquals("/candidaturas/nova", path());
        assertNoOverflow();

        button("Salvar candidatura").click();
        assertEquals("", applicationField("title").getDomProperty("value"));
        assertEquals(Boolean.FALSE, script("return document.querySelector('form').checkValidity()"));
        assertEquals("/candidaturas/nova", path());

        fillApplicationField("title", title);
        fillApplicationField("companyName", "Empresa Fictícia");
        fillApplicationField("sourceUrl", url);
        fillApplicationField("location", "Curitiba");
        new Select(applicationField("workMode")).selectByVisibleText("Híbrido");
        fillApplicationField("description", "Desenvolvimento de APIs");
        fillApplicationField("hardSkills", "Java\nSpring Boot");
        fillApplicationField("softSkills", "java");
        fillApplicationField("notes", "Aguardar retorno");
        button("Salvar candidatura").click();
        message("main [role='alert']", "Não são permitidas habilidades duplicadas");
        assertEquals(title, applicationField("title").getDomProperty("value"));

        fillApplicationField("softSkills", "Comunicação");
        button("Salvar candidatura").click();
        heading(title);
        message(".panel dl", "Aguardar retorno");
        String detail = path();
        assertTrue(detail.matches("/candidaturas/[1-9]\\d*"));
        String resource = "/api" + detail.replace("/candidaturas", "/applications");
        WebElement salaryValue = browser.findElement(By.xpath(
            "//dt[normalize-space(.)='Salário']/following-sibling::dd"
        ));
        assertEquals("Não informado", salaryValue.getText());
        assertNoOverflow();

        browser.findElement(By.linkText("Voltar para candidaturas")).click();
        heading("Minhas candidaturas");
        wait.until(ExpectedConditions.elementToBeClickable(By.linkText("Nova candidatura"))).click();
        heading("Nova candidatura");
        fillApplicationField("title", "Candidatura duplicada");
        fillApplicationField("sourceUrl", url);
        button("Salvar candidatura").click();
        message("main [role='alert']", "Esta vaga já foi cadastrada com esse link.");
        assertEquals(url, applicationField("sourceUrl").getDomProperty("value"));

        browser.findElement(By.linkText("Cancelar")).click();
        heading("Minhas candidaturas");
        wait.until(ExpectedConditions.elementToBeClickable(By.linkText(title))).click();
        heading(title);
        button("Editar candidatura").click();
        heading("Editar candidatura");
        fillApplicationField("salary", "9500");
        button("Salvar candidatura").click();
        message("main [role='alert']", "Selecione o período do salário.");
        new Select(applicationField("salaryPeriod")).selectByVisibleText("Por mês");
        new Select(applicationField("status")).selectByVisibleText("Entrevista");
        button("Salvar candidatura").click();
        heading(title);
        message("main [role='status']", "Candidatura atualizada.");
        message(".panel dl", "R$ 9.500,00 por mês");
        message("ol li:nth-child(2)", "Candidatura realizada → Entrevista");
        assertEquals(2, browser.findElements(By.cssSelector("ol li")).size());
        assertNoOverflow();

        Map<?, ?> persisted = (Map<?, ?>) browserApi("GET", resource, null, 200);
        Map<?, ?> job = (Map<?, ?>) persisted.get("job");
        assertEquals("Empresa Fictícia", job.get("companyName"));
        assertEquals("Curitiba", job.get("location"));
        assertEquals("HYBRID", job.get("workMode"));
        assertEquals("Desenvolvimento de APIs", job.get("description"));
        assertEquals(List.of("Java", "Spring Boot"), job.get("hardSkills"));
        assertEquals(List.of("Comunicação"), job.get("softSkills"));
        assertEquals("BRL", job.get("salaryCurrency"));
        assertEquals("MONTH", job.get("salaryPeriod"));
        assertEquals("Aguardar retorno", persisted.get("notes"));

        button("Excluir candidatura").click();
        button("Manter candidatura").click();
        browserApi("GET", resource, null, 200);
        button("Excluir candidatura").click();
        button("Confirmar exclusão").click();
        heading("Minhas candidaturas");
        message(".board-message", "Você ainda não tem candidaturas cadastradas.");
        browserApi("GET", resource, null, 404);
    }

    @ParameterizedTest(name = "Fluxo manual entre contas: {0}px, tema {1}")
    @CsvSource({ "1440, light", "390, dark" })
    void manualFlowAcrossAccounts(int width, String theme) throws Exception {
        openBrowser(width, theme);
        String marker = UUID.randomUUID().toString().substring(0, 8);
        String owner = "qa.fluxo." + UUID.randomUUID() + "@example.test";
        String other = "qa.fluxo.outra." + UUID.randomUUID() + "@example.test";
        String title = "Vaga E2E " + marker;
        register(owner);
        visit("/cadastro");
        register(other);

        visit("/candidaturas/nova");
        visible("#email");
        signIn(owner, INITIAL_PASSWORD);
        heading("Nova candidatura");
        fillApplicationField("title", title);
        fillApplicationField("companyName", "Empresa E2E");
        fillApplicationField("location", "Recife");
        fillApplicationField("description", "Fluxo manual completo");
        fillApplicationField("hardSkills", "Java");
        button("Salvar candidatura").click();
        heading(title);
        String detail = path();
        assertTrue(detail.matches("/candidaturas/[1-9]\\d*"));
        long id = Long.parseLong(detail.substring(detail.lastIndexOf('/') + 1));
        String resource = "/api/applications/" + id;
        message("ol li", "Candidatura realizada");

        button("Editar candidatura").click();
        heading("Editar candidatura");
        fillApplicationField("location", "Recife, PE");
        fillApplicationField("notes", "Primeira edição");
        button("Salvar candidatura").click();
        message("main [role='status']", "Candidatura atualizada.");
        message(".panel dl", "Recife, PE");
        message(".panel dl", "Primeira edição");

        button("Editar candidatura").click();
        heading("Editar candidatura");
        fillApplicationField("notes", "Rascunho que perde a corrida");
        Map<?, ?> current = (Map<?, ?>) browserApi("GET", resource, null, 200);
        Map<String, Object> concurrent = new LinkedHashMap<>();
        concurrent.put("job", current.get("job"));
        concurrent.put("status", current.get("status"));
        concurrent.put("appliedOn", current.get("appliedOn"));
        concurrent.put("notes", "Alterada em outra aba");
        concurrent.put("version", current.get("version"));
        browserApi("PUT", resource, concurrent, 200);
        button("Salvar candidatura").click();
        message("main [role='alert']", "A candidatura mudou desde que você a abriu.");
        assertEquals("Rascunho que perde a corrida", applicationField("notes").getDomProperty("value"));
        button("Descartar rascunho e recarregar").click();
        message(".panel dl", "Alterada em outra aba");

        ChromiumDriver chromium = (ChromiumDriver) browser;
        button("Editar candidatura").click();
        heading("Editar candidatura");
        fillApplicationField("notes", "Salva depois da queda de rede");
        chromium.executeCdpCommand("Network.enable", Map.of());
        chromium.executeCdpCommand("Network.setBlockedURLs", Map.of("urls", List.of("*/api/applications/*")));
        try {
            button("Salvar candidatura").click();
            message("main [role='alert']", "Não foi possível receber uma resposta do servidor.");
            assertEquals("Salva depois da queda de rede", applicationField("notes").getDomProperty("value"));
        } finally {
            chromium.executeCdpCommand("Network.setBlockedURLs", Map.of("urls", List.of()));
        }
        button("Salvar candidatura").click();
        message("main [role='status']", "Candidatura atualizada.");
        message(".panel dl", "Salva depois da queda de rede");
        assertNoOverflow();

        browser.findElement(By.linkText("Voltar para candidaturas")).click();
        heading("Minhas candidaturas");
        type("filter-q", marker);
        visible("#filter-q").sendKeys(Keys.ENTER);
        message(".announcement", "1 candidatura encontrada.");
        new Select(visible("#filter-status")).selectByValue("APPLIED");
        wait.until(driver -> driver.getCurrentUrl().contains("etapa=APPLIED"));
        assertTrue(browser.getCurrentUrl().contains("busca=" + marker));
        assertEquals("applied", laneOf(id));
        new Select(visible("#application-" + id + " select")).selectByValue("INTERVIEW");
        wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector("#application-" + id + " .move"))).click();
        message(".announcement", "Ela não aparece com o filtro de etapa atual.");
        message(".board-message", "Nenhuma candidatura encontrada com esses filtros.");
        new Select(visible("#filter-status")).selectByValue("INTERVIEW");
        wait.until(ExpectedConditions.elementToBeClickable(By.linkText(title))).click();
        heading(title);
        message("ol li:nth-child(2)", "Candidatura realizada → Entrevista");
        assertEquals(2, browser.findElements(By.cssSelector("ol li")).size());
        browser.findElement(By.linkText("Voltar para candidaturas")).click();
        heading("Minhas candidaturas");
        wait.until(driver -> driver.getCurrentUrl().contains("etapa=INTERVIEW"));
        assertEquals(marker, visible("#filter-q").getDomProperty("value"));
        assertEquals("INTERVIEW", visible("#filter-status").getDomProperty("value"));
        assertEquals("interview", laneOf(id));
        assertNoOverflow();
        screenshot("manual-flow-" + width + "-" + theme + ".png");

        List<?> history = (List<?>) browserApi("GET", resource + "/history", null, 200);
        assertEquals(List.of("APPLIED", "INTERVIEW"), history.stream()
            .map(entry -> ((Map<?, ?>) entry).get("toStatus"))
            .toList());

        switchAccount(other);
        visit("/candidaturas");
        heading("Minhas candidaturas");
        message(".board-message", "Você ainda não tem candidaturas cadastradas.");
        type("filter-q", marker);
        visible("#filter-q").sendKeys(Keys.ENTER);
        message(".board-message", "Nenhuma candidatura encontrada com esses filtros.");
        visit(detail);
        visible("main [role='alert']");
        assertTrue(browser.findElements(By.xpath("//h1[normalize-space(.)='" + title + "']")).isEmpty());
        assertTrue(browser.findElements(By.xpath("//button[normalize-space(.)='Editar candidatura']")).isEmpty());
        browserApi("GET", resource, null, 404);
        browserApi("GET", resource + "/history", null, 404);
        browserApi("PUT", resource, concurrent, 404);
        browserApi("DELETE", resource, null, 404);

        switchAccount(owner);
        visit(detail);
        heading(title);
        message(".panel dl", "Salva depois da queda de rede");
        button("Excluir candidatura").click();
        button("Confirmar exclusão").click();
        heading("Minhas candidaturas");
        message(".board-message", "Você ainda não tem candidaturas cadastradas.");
        browserApi("GET", resource, null, 404);

        browserApi("DELETE", "/api/users/me", null, 204);
        switchAccount(other);
        browserApi("DELETE", "/api/users/me", null, 204);
    }

    /** Executa o axe-core da dependência do frontend e falha com as regras violadas. */
    private void assertAccessible(String context) throws Exception {
        if (axeSource == null) {
            axeSource = Files.readString(
                BrowserEnvironment.ROOT.resolve("frontend/node_modules/axe-core/axe.min.js")
            );
        }
        script(axeSource);
        Object violations = ((JavascriptExecutor) browser).executeAsyncScript("""
            const done = arguments[arguments.length - 1];
            axe.run(document, {
                runOnly: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa']
            }).then(
                result => done(result.violations.map(v =>
                    v.id + ': ' + v.nodes.map(n => n.target.join(' ')).slice(0, 3).join(', ')
                )),
                error => done(['axe: ' + error.message])
            );
            """);
        assertEquals(List.of(), violations, "Violações de acessibilidade em " + context);
    }

    @ParameterizedTest(name = "Importação por texto: {0}px, tema {1}")
    @CsvSource({ "1440, light", "390, dark" })
    void importFromTextAndSaveOnce(int width, String theme) throws Exception {
        openBrowser(width, theme);
        String marker = UUID.randomUUID().toString().substring(0, 8);
        String email = "qa.importacao." + UUID.randomUUID() + "@example.test";
        String title = "Engenheira de Qualidade " + marker;
        register(email);
        visit("/candidaturas/importar");
        visible("#email");
        signIn(email, INITIAL_PASSWORD);
        heading("Importar vaga");

        visible("input[value='TEXT']").click();
        visible("textarea[formcontrolname='text']").sendKeys(String.join(
            "\n",
            "Cargo: " + title,
            "Empresa: Empresa Simulada",
            "Local: Recife, PE",
            "Salário: R$ 9.500 por mês.",
            "Requisitos: Java, Selenium e PostgreSQL."
        ));
        button("Importar").click();
        message("#ready-title", "Revise os dados da vaga");
        String review = path();
        assertTrue(review.matches("/candidaturas/importar/[1-9]\\d*"));
        long task = Long.parseLong(review.substring(review.lastIndexOf('/') + 1));
        assertReviewFilledBy(title);
        message("small.evidence", "Trecho da vaga:");
        assertNoOverflow();
        assertAccessible("revisão da importação " + width + "px " + theme);
        screenshot("import-review-" + width + "-" + theme + ".png");

        browser.navigate().refresh();
        message("#ready-title", "Revise os dados da vaga");
        assertReviewFilledBy(title);
        reviewField("notes").sendKeys("Revisado no E2E");

        browser.manage().deleteAllCookies();
        button("Salvar vaga").click();
        wait.until(driver -> path().equals("/entrar")
            || !driver.findElements(By.cssSelector("main [role='alert']")).isEmpty());
        if (!path().equals("/entrar")) {
            visible("main [role='alert']");
            assertEquals("Revisado no E2E", reviewField("notes").getDomProperty("value"));
            button("Salvar vaga").click();
        }
        wait.until(driver -> path().equals("/entrar"));
        message("main", "Sua sessão expirou. Entre novamente para continuar.");
        signIn(email, INITIAL_PASSWORD);
        wait.until(driver -> path().equals(review));
        message("#ready-title", "Revise os dados da vaga");
        assertReviewFilledBy(title);

        ChromiumDriver chromium = (ChromiumDriver) browser;
        chromium.executeCdpCommand("Network.enable", Map.of());
        chromium.executeCdpCommand("Network.setBlockedURLs", Map.of("urls", List.of("*/confirm")));
        try {
            button("Salvar vaga").click();
            message("main [role='alert']", "Não foi possível receber uma resposta do servidor.");
            assertEquals(title, reviewField("title").getDomProperty("value"));
        } finally {
            chromium.executeCdpCommand("Network.setBlockedURLs", Map.of("urls", List.of()));
        }

        WebElement save = button("Salvar vaga");
        ((JavascriptExecutor) browser).executeScript("arguments[0].click(); arguments[0].click();", save);
        heading(title);
        assertTrue(path().matches("/candidaturas/[1-9]\\d*"));
        Map<?, ?> found = (Map<?, ?>) browserApi("GET", "/api/applications?q=" + marker, null, 200);
        assertEquals(1, ((Number) found.get("totalElements")).intValue());
        Map<?, ?> completed = (Map<?, ?>) browserApi("GET", "/api/import-tasks/" + task, null, 200);
        assertEquals("COMPLETED", completed.get("status"));

        visit(review);
        message("#completed-title", "Vaga salva");
        browserApi("DELETE", "/api/users/me", null, 204);
    }

    @ParameterizedTest(name = "Importação por link e revisão manual: {0}px, tema {1}")
    @CsvSource({ "1440, light" })
    void importFromPageAndReviewManuallyWhenTheProviderFails(int width, String theme) throws Exception {
        openBrowser(width, theme);
        String marker = UUID.randomUUID().toString().substring(0, 8);
        String email = "qa.importacao.link." + UUID.randomUUID() + "@example.test";
        String fromPage = "Desenvolvedora Java " + marker;
        register(email);
        visit("/candidaturas/importar");
        visible("#email");
        signIn(email, INITIAL_PASSWORD);
        heading("Importar vaga");

        visible("input[formcontrolname='url']").sendKeys(ImportSimulation.pageUrl(fromPage, "Empresa da Página"));
        button("Importar").click();
        message("#ready-title", "Revise os dados da vaga");
        assertEquals(fromPage, reviewField("title").getDomProperty("value"));
        assertEquals("Empresa da Página", reviewField("companyName").getDomProperty("value"));
        message("small.evidence", "Origem: dados estruturados da página.");
        visible("input[formcontrolname='decision'][value='SAVED']").click();
        button("Salvar vaga").click();
        heading(fromPage);

        int calls = ImportSimulation.extractions();
        String manual = "Analista de Testes " + marker;
        visit("/candidaturas/importar");
        heading("Importar vaga");
        visible("input[value='TEXT']").click();
        visible("textarea[formcontrolname='text']").sendKeys(
            "Vaga de analista de testes com foco em automação. " + ImportSimulation.UNAVAILABLE
        );
        button("Importar").click();
        message("#ready-title", "Revise os dados da vaga");
        assertEquals("", reviewField("title").getDomProperty("value"));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.xpath(
            "//label[.//input[@formcontrolname='title']]//span[@class='missing' and normalize-space(.)='Não encontrado']"
        )));
        assertTrue(reviewField("description").getDomProperty("value").contains("analista de testes"));
        assertEquals(calls + 2, ImportSimulation.extractions(), "duas tentativas no provedor indisponível");
        button("Salvar vaga").click();
        message("main [role='alert']", "Preencha o título da vaga");
        reviewField("title").sendKeys(manual);
        button("Salvar vaga").click();
        heading(manual);

        visit("/candidaturas?busca=" + marker);
        message(".count", "Mostrando 2 de 2");
        assertEquals(2, browser.findElements(By.cssSelector("article")).size());
        browserApi("DELETE", "/api/users/me", null, 204);
    }

    private void assertReviewFilledBy(String title) {
        assertEquals(title, reviewField("title").getDomProperty("value"));
        assertEquals("Empresa Simulada", reviewField("companyName").getDomProperty("value"));
        assertEquals("Recife, PE", reviewField("location").getDomProperty("value"));
        assertEquals("9500", reviewField("salary").getDomProperty("value"));
        assertEquals("Java\nSelenium\nPostgreSQL", reviewField("hardSkills").getDomProperty("value"));
    }

    private WebElement reviewField(String name) {
        return visible("form [formcontrolname='" + name + "']");
    }

    private void switchAccount(String email) {
        browser.manage().deleteAllCookies();
        visit("/entrar");
        signIn(email, INITIAL_PASSWORD);
        heading("Acesso confirmado");
    }

    private WebElement applicationField(String name) {
        return visible("[formcontrolname='" + name + "']");
    }

    private void fillApplicationField(String name, String value) {
        WebElement field = applicationField(name);
        field.clear();
        field.sendKeys(value);
    }

    private Map<?, ?> seedApplication(Map<String, ?> job, String status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("job", job);
        body.put("status", status);
        if (!"SAVED".equals(status)) {
            body.put("appliedOn", "2026-10-31");
        }
        return (Map<?, ?>) browserApi("POST", "/api/applications", body, 201);
    }

    private String laneOf(long id) {
        return visible("#application-" + id)
            .findElement(By.xpath("ancestor::section[@data-lane]"))
            .getDomAttribute("data-lane");
    }

    private Object browserApi(String method, String route, Map<String, ?> body, int status) {
        Map<?, ?> result = (Map<?, ?>) ((JavascriptExecutor) browser).executeAsyncScript("""
            const [method, route, body, done] = arguments;
            (async () => {
                const options = { method, headers: {} };
                if (method !== 'GET') {
                    const csrf = await fetch('/api/auth/csrf');
                    if (!csrf.ok) {
                        return { status: csrf.status };
                    }
                    const { headerName, token } = await csrf.json();
                    options.headers[headerName] = token;
                }
                if (body !== null) {
                    options.headers['Content-Type'] = 'application/json';
                    options.body = JSON.stringify(body);
                }
                const response = await fetch(route, options);
                const json = response.ok && response.status !== 204;
                return { status: response.status, body: json ? await response.json() : null };
            })().then(done, () => done({ status: 0 }));
            """, method, route, body);
        assertEquals(status, ((Number) result.get("status")).intValue(), method + " " + route);
        return result.get("body");
    }

    private void verifyPhotoChanges(int width, String theme) throws Exception {
        Path png = createImage("profile-test.png", "png", 24, 24);
        Path jpeg = createImage("profile-test.jpeg", "jpeg", 32, 32);
        selectPhoto(png);
        visible("img[alt='Prévia da nova foto']");
        button("Cancelar prévia").click();
        wait.until(ExpectedConditions.textToBe(By.cssSelector(".avatar"), "PE"));

        selectPhoto(png);
        button("Salvar foto").click();
        message(".photo [role='status']", "Foto atualizada.");
        waitForSavedPhoto(24);
        selectPhoto(jpeg);
        button("Salvar foto").click();
        message(".photo [role='status']", "Foto atualizada.");
        waitForSavedPhoto(32);
        browser.navigate().refresh();
        heading("Meu perfil");
        assertEquals("Pessoa Editada", visible("#profile-name").getDomProperty("value"));
        waitForSavedPhoto(32);
        assertNoOverflow();
        screenshot("profile-" + width + "-" + theme + ".png");

        Path largeFile = BrowserEnvironment.OUTPUT.resolve("profile-over-2-mib.png");
        Files.write(largeFile, new byte[2 * 1024 * 1024 + 1]);
        selectPhoto(largeFile);
        message(".photo [role='alert']", "Selecione uma imagem JPEG ou PNG de até 2 MiB.");
        waitForSavedPhoto(32);

        Path largeImage = createImage("profile-over-4-megapixels.png", "png", 2001, 2000);
        selectPhoto(largeImage);
        button("Salvar foto").click();
        message(".photo [role='alert']", "A imagem deve ter no máximo 4 milhões de pixels.");
        button("Cancelar prévia").click();
        waitForSavedPhoto(32);
        button("Remover foto").click();
        message(".photo [role='status']", "Foto removida.");
        wait.until(ExpectedConditions.textToBe(By.cssSelector(".avatar"), "PE"));
    }

    private void changeProfilePassword(String email) {
        visible(".password summary").sendKeys(Keys.ENTER);
        type("profile-current-password", "incorreta");
        type("profile-new-password", NEW_PASSWORD);
        type("profile-confirm-password", NEW_PASSWORD);
        button("Salvar nova senha").click();
        message(".password [role='alert']", "Senha atual incorreta.");
        assertEquals("/conta/perfil", path());

        type("profile-current-password", INITIAL_PASSWORD);
        button("Salvar nova senha").click();
        message(".ok", "Senha alterada. Entre com a nova senha.");
        assertEquals("/entrar", path());
        signIn(email, INITIAL_PASSWORD);
        message(".alert", "E-mail ou senha inválidos.");
        loginPassword(NEW_PASSWORD);
        heading("Acesso confirmado");
        visible("a[href='/conta/perfil']").click();
        heading("Meu perfil");
    }

    private void deleteProfile(String email) {
        WebElement opener = button("Excluir minha conta");
        opener.sendKeys(Keys.ENTER);
        visible("dialog[open]");
        browser.switchTo().activeElement().sendKeys(Keys.ESCAPE);
        wait.until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector("dialog[open]")));
        assertEquals(opener, browser.switchTo().activeElement());

        opener.sendKeys(Keys.ENTER);
        type("profile-delete-email", "outra@example.test");
        button("Confirmar exclusão").click();
        message("dialog [role='alert']", "Digite o e-mail da sua conta para confirmar a exclusão.");
        type("profile-delete-email", email);
        button("Confirmar exclusão").click();
        message(".ok", "Sua conta foi excluída.");
        assertEquals("/entrar", path());
        signIn(email, NEW_PASSWORD);
        message(".alert", "E-mail ou senha inválidos.");
    }

    private Path createImage(String filename, String format, int width, int height) throws Exception {
        Path path = BrowserEnvironment.OUTPUT.resolve(filename);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        assertTrue(ImageIO.write(image, format, path.toFile()));
        return path;
    }

    private void selectPhoto(Path path) {
        browser.findElement(By.id("profile-photo")).sendKeys(path.toString());
    }

    private void waitForSavedPhoto(int width) {
        wait.until(driver -> ((Number) script(
            "return document.querySelector('img[alt=\"Foto de perfil\"]')?.naturalWidth ?? 0"
        )).intValue() == width);
    }

    private void message(String selector, String text) {
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.cssSelector(selector), text));
    }

    private void openBrowser(int width, String theme) throws Exception {
        desiredTheme = theme;
        ChromiumDriver driver = createBrowser();
        browser = driver;
        Files.writeString(BrowserEnvironment.OUTPUT.resolve("environment.txt"), """
            Java: %s
            Selenium: %s
            Browser: %s %s
            """.formatted(
                System.getProperty("java.version"),
                new org.openqa.selenium.BuildInfo().getReleaseLabel(),
                driver.getCapabilities().getBrowserName(),
                driver.getCapabilities().getBrowserVersion()
            ));
        browser.manage().window().setSize(new Dimension(width, 1000));
        driver.executeCdpCommand("Emulation.setDeviceMetricsOverride", Map.of(
            "width", width,
            "height", 1000,
            "deviceScaleFactor", 1,
            "mobile", false
        ));
        wait = new WebDriverWait(browser, Duration.ofSeconds(20));
        visit("/cadastro");
        driver.executeCdpCommand("Emulation.setEmulatedMedia", Map.of(
            "features", java.util.List.of(Map.of(
                "name", "prefers-color-scheme", "value", theme
            ))
        ));
        // A largura interna é a viewport efetivamente exercitada e registrada no relatório.
        assertEquals(width, ((Number) script("return innerWidth")).intValue());
    }

    private ChromiumDriver createBrowser() {
        String chromeBinary = System.getenv("ACOMPANHAAI_QA_CHROME_BINARY");
        if (chromeBinary != null && !chromeBinary.isBlank()) {
            ChromeOptions options = new ChromeOptions();
            options.setBinary(chromeBinary);
            configureBrowser(options);
            ChromeDriverService service = new ChromeDriverService.Builder()
                .withLogOutput(java.io.OutputStream.nullOutputStream())
                .build();
            return new ChromeDriver(service, options);
        }

        EdgeOptions options = new EdgeOptions();
        configureBrowser(options);
        EdgeDriverService service = new EdgeDriverService.Builder()
            .withLogOutput(java.io.OutputStream.nullOutputStream())
            .build();
        return new EdgeDriver(service, options);
    }

    private void configureBrowser(ChromiumOptions<?> options) {
        options.addArguments("--headless=new", "--disable-gpu", "--no-first-run");
        options.setExperimentalOption("prefs", Map.of(
            "credentials_enable_service", false,
            "profile.password_manager_enabled", false
        ));
    }

    private void register(String email) {
        fillRegistration(email);
        submit();
        heading("Conta criada!");
    }

    private void fillRegistration(String email) {
        type("cad-nome", "Maria de Teste");
        type("cad-email", email);
        type("cad-senha", INITIAL_PASSWORD);
        type("cad-confirma", INITIAL_PASSWORD);
    }

    private void signIn(String email, String password) {
        enterEmail(email);
        loginPassword(password);
    }

    private void enterEmail(String email) {
        type("email", email);
        submit();
        visible("#login-senha");
    }

    private void loginPassword(String password) {
        type("login-senha", password);
        submit();
    }

    private void visit(String route) {
        browser.get(BrowserEnvironment.BASE + route);
        visible("button[aria-label='Alternar tema']");
        if (!desiredTheme.equals(script(
            "return document.documentElement.dataset.theme || "
                + "(matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light')"
        ))) {
            visible("button[aria-label='Alternar tema']").click();
        }
    }

    private void type(String id, String value) {
        WebElement input = visible("#" + id);
        input.clear();
        input.sendKeys(value);
    }

    private void submit() {
        WebElement submit = wait.until(ExpectedConditions.elementToBeClickable(
            By.cssSelector("button[type='submit']")
        ));
        submit.click();
    }

    private WebElement visible(String selector) {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(selector)));
    }

    private WebElement button(String text) {
        return wait.until(ExpectedConditions.elementToBeClickable(By.xpath(
            "//button[normalize-space(.)='" + text + "']"
        )));
    }

    private WebElement buttonStarting(String text) {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(By.xpath(
            "//button[starts-with(normalize-space(.),'" + text + "')]"
        )));
    }

    private void heading(String text) {
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.cssSelector("h1"), text));
    }

    private String focusedId() {
        return (String) script("return document.activeElement.id");
    }

    private String path() {
        return URI.create(browser.getCurrentUrl()).getPath();
    }

    private Object script(String code) {
        return ((JavascriptExecutor) browser).executeScript(code);
    }

    private void assertNoOverflow() {
        assertTrue((Boolean) script(
            "return document.documentElement.scrollWidth <= innerWidth"
        ), "A página apresentou rolagem horizontal.");
    }

    private void screenshot(String filename) throws Exception {
        byte[] image = ((org.openqa.selenium.TakesScreenshot) browser).getScreenshotAs(OutputType.BYTES);
        Files.write(BrowserEnvironment.OUTPUT.resolve(filename), image);
    }
}
