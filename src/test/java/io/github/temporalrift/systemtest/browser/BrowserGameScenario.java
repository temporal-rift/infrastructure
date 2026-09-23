package io.github.temporalrift.systemtest.browser;

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns the Playwright browser process and every isolated {@link BrowserPlayer} context for one
 * scenario, plus the small amount of scenario-level orchestration (waiting for a rendered
 * condition, reading the deployment's effective manifest) that mirrors
 * {@code TemporalRiftScenario}'s role for the command-only harness.
 */
final class BrowserGameScenario implements AutoCloseable {

    // Overridable so a developer can point this suite at an already-running stack without editing
    // source, matching the override conventions the playtest scripts already use.
    private static final String CLIENT_ORIGIN =
            System.getProperty("browserE2e.clientOrigin", "https://localhost:20443");
    private static final Path MANIFEST_PATH =
            Path.of(System.getProperty("browserE2e.manifestPath", "playtest/manifest.json"));
    private static final Duration TRANSITION_TIMEOUT = Duration.ofSeconds(90);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Playwright playwright = Playwright.create();
    private final Browser browser = playwright
            .chromium()
            .launch(new BrowserType.LaunchOptions()
                    .setHeadless(!"false".equals(System.getProperty("browserE2e.headed"))));
    private final List<BrowserPlayer> players = new ArrayList<>();

    BrowserPlayer signIn(String name, String subject) {
        var player = BrowserPlayer.signIn(browser, name, subject, CLIENT_ORIGIN);
        players.add(player);
        return player;
    }

    static void waitUntil(BooleanSupplier condition, String description) {
        waitUntil(condition, description, TRANSITION_TIMEOUT);
    }

    static void waitUntil(BooleanSupplier condition, String description, Duration timeout) {
        await().atMost(timeout)
                .pollInterval(Duration.ofMillis(500))
                .alias(description)
                .until(condition::getAsBoolean);
    }

    /** The isolated deployment's immutable effective manifest (infrastructure#46), read from disk
     * rather than served by the player-facing edge — the manifest is operator/CI attribution, not
     * a player-facing route. */
    JsonNode effectiveManifest() {
        try {
            return JSON.readTree(Files.readString(MANIFEST_PATH));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read the playtest manifest at " + MANIFEST_PATH, exception);
        }
    }

    List<BrowserPlayer> players() {
        return List.copyOf(players);
    }

    @Override
    public void close() {
        players.forEach(BrowserPlayer::close);
        browser.close();
        playwright.close();
    }
}
