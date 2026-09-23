package io.github.temporalrift.systemtest.browser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.options.AriaRole;

/**
 * One isolated, separately authenticated browser session — the browser-suite counterpart of
 * {@code Actor} in the command-only harness. Each instance owns its own {@link BrowserContext} (no
 * cookie/storage sharing across players, matching the isolation real separate players have) and
 * performs a real OIDC authorization-code-with-PKCE redirect login through the suite's interactive
 * mock issuer, rather than injecting a bearer token directly.
 */
final class BrowserPlayer implements AutoCloseable {

    private final String name;
    private final String subject;
    private final BrowserContext context;
    private final Page page;
    private final NetworkPayloadRecorder network;
    private final GameScreen screen;

    private BrowserPlayer(String name, String subject, BrowserContext context, Page page) {
        this.name = name;
        this.subject = subject;
        this.context = context;
        this.page = page;
        this.network = NetworkPayloadRecorder.attachedTo(page);
        this.screen = new GameScreen(page);
    }

    static BrowserPlayer signIn(Browser browser, String name, String subject, String clientOrigin) {
        // ignoreHTTPSErrors: the playtest edge terminates TLS with a locally generated,
        // self-signed certificate for this suite — never a real deployment's cert.
        var context = browser.newContext(new Browser.NewContextOptions().setIgnoreHTTPSErrors(true));
        context.tracing()
                .start(new Tracing.StartOptions()
                        .setScreenshots(true)
                        .setSnapshots(true)
                        .setSources(false)
                        .setName(name));
        var page = context.newPage();
        try {
            page.navigate(clientOrigin);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign in"))
                    .click();
            performMockIssuerLogin(page, subject);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign out"))
                    .waitFor();
            return new BrowserPlayer(name, subject, context, page);
        } catch (RuntimeException exception) {
            // A failed login has no BrowserPlayer for scenario teardown to close. Capture
            // its rendered error before closing the context so this failure is diagnosable.
            try {
                var screenshotsDir = Path.of(System.getProperty("browserE2e.tracesDir", "target/browser-e2e-traces"));
                Files.createDirectories(screenshotsDir);
                page.screenshot(
                        new Page.ScreenshotOptions().setPath(screenshotsDir.resolve(name + "-sign-in-failure.png")));
            } catch (IOException | RuntimeException captureFailure) {
                exception.addSuppressed(captureFailure);
            } finally {
                context.close();
            }
            throw exception;
        }
    }

    // mock-oauth2-server's own interactive debug login page (not this codebase's markup): a plain
    // form with a "username" field the caller supplies as the token's eventual subject claim, and
    // an `<input type="submit" value="Sign-in">` (hyphenated, confirmed against the real container
    // — not the hyphen-free "Sign in" text game-client's own sign-in button uses).
    private static void performMockIssuerLogin(Page page, String subject) {
        page.locator("input[name='username']").waitFor();
        page.locator("input[name='username']").fill(subject);
        page.getByRole(
                        AriaRole.BUTTON,
                        new Page.GetByRoleOptions().setName(Pattern.compile("Sign.?in", Pattern.CASE_INSENSITIVE)))
                .click();
    }

    String name() {
        return name;
    }

    String subject() {
        return subject;
    }

    Page page() {
        return page;
    }

    GameScreen screen() {
        return screen;
    }

    NetworkPayloadRecorder network() {
        return network;
    }

    void reload() {
        page.reload();
    }

    @Override
    public void close() {
        var tracesDir = System.getProperty("browserE2e.tracesDir", "target/browser-e2e-traces");
        var traceFile = Path.of(tracesDir, name + "-" + System.currentTimeMillis() + ".zip");
        // Unconditional, not "only on failure": Playwright's tracing API has no built-in signal for
        // whether the enclosing JUnit test failed, and always exporting a small trace zip is cheap.
        // capture-browser-e2e-diagnostics.sh bundles this directory; the CI workflow uploads that
        // bundle as a build artifact only when the job itself failed.
        context.tracing().stop(new Tracing.StopOptions().setPath(traceFile));
        context.close();
    }
}
