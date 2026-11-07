package com.maiconamaral.acompanhaai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;
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
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeDriverService;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.boot.test.context.SpringBootTest;
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

    private void openBrowser(int width, String theme) throws Exception {
        desiredTheme = theme;
        EdgeOptions options = new EdgeOptions();
        options.addArguments("--headless=new", "--disable-gpu", "--no-first-run");
        options.setExperimentalOption("prefs", java.util.Map.of(
            "credentials_enable_service", false,
            "profile.password_manager_enabled", false
        ));
        EdgeDriverService service = new EdgeDriverService.Builder()
            .withLogOutput(java.io.OutputStream.nullOutputStream()).build();
        EdgeDriver edge = new EdgeDriver(service, options);
        browser = edge;
        Files.writeString(BrowserEnvironment.OUTPUT.resolve("environment.txt"), """
            Java: %s
            Selenium: %s
            Browser: %s %s
            """.formatted(
                System.getProperty("java.version"),
                new org.openqa.selenium.BuildInfo().getReleaseLabel(),
                edge.getCapabilities().getBrowserName(),
                edge.getCapabilities().getBrowserVersion()
            ));
        browser.manage().window().setSize(new Dimension(width, 1000));
        edge.executeCdpCommand("Emulation.setDeviceMetricsOverride", java.util.Map.of(
            "width", width,
            "height", 1000,
            "deviceScaleFactor", 1,
            "mobile", false
        ));
        wait = new WebDriverWait(browser, Duration.ofSeconds(20));
        visit("/cadastro");
        edge.executeCdpCommand("Emulation.setEmulatedMedia", java.util.Map.of(
            "features", java.util.List.of(java.util.Map.of(
                "name", "prefers-color-scheme", "value", theme
            ))
        ));
        // A largura interna é a viewport efetivamente exercitada e registrada no relatório.
        assertEquals(width, ((Number) script("return innerWidth")).intValue());
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
