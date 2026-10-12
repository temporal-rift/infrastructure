package io.github.temporalrift.systemtest.workbench;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The designer's whole loop through the real UI of the deployed workbench: preview and freeze, launch,
 * follow progress, report and compare, inspect one case as a player and as an authorized observer, and
 * reproduce it to an exact match. The test only drives the browser; real lanes play the games.
 */
class DesignerJourneyIT {

    private static final String DRAFT_KEY = "workbench.experiment-draft.v1";
    private static final Duration UI_WAIT = Duration.ofSeconds(60);
    private static final Duration RUN_WAIT = Duration.ofMinutes(Integer.getInteger("workbenchE2e.journeyMinutes", 90));

    private static Playwright playwright;
    private static Browser browser;

    private final Deployment deployment = Deployment.load();
    private final Evidence evidence = new Evidence("journey");
    private BrowserContext context;
    private Page page;

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
    }

    @AfterAll
    static void closeBrowser() {
        browser.close();
        playwright.close();
    }

    @BeforeEach
    void openPage() {
        context = browser.newContext();
        context.setDefaultTimeout((double) UI_WAIT.toMillis());
        page = context.newPage();
    }

    @AfterEach
    void closePage() {
        System.err.println("Page at end: " + page.url());
        context.close();
        evidence.write(deployment);
    }

    @Test
    void aDesignerFreezesRunsComparesInspectsAndReproducesACaseInTheBrowser() throws Exception {
        var catalog = new Workbench(
                        deployment.workbenchUrl(),
                        new Issuer(deployment.issuer()).token("designer-api", Issuer.DESIGNER))
                .policies();
        var manifest = Manifests.build(
                deployment,
                catalog,
                new Manifests.Spec(
                        "journey " + deployment.runId() + " " + UUID.randomUUID(),
                        List.of(Manifests.BASELINE, Manifests.CANDIDATE),
                        List.of("7"),
                        List.of(3),
                        List.of(List.of("ERASERS", "PROPHETS", "WEAVERS")),
                        List.of(Manifests.RANDOM),
                        deployment.lanes().size(),
                        Integer.getInteger("workbenchE2e.caseTimeoutSeconds", 900)));
        startWithDraft(manifest);
        signInAndOpen("designer", "/experiments/new");

        // Preview the exact matrix, then confirm the digest that was shown.
        click("Preview matrix");
        assertThat(heading("Matrix preview")).isVisible();
        click("Freeze this experiment…");
        assertThat(page.getByText(Pattern.compile("freeze exactly 6 cases"))).isVisible();
        click("Confirm and freeze");

        // Launch, then follow the run on the page until the real lanes have played it.
        click("Launch a run");
        assertThat(page.getByText("State: Completed")).isVisible(runWait());
        assertThat(page.getByRole(AriaRole.TABLE, new Page.GetByRoleOptions().setName("Case counts")))
                .containsText("6");
        var runId = Pattern.compile("/runs/([0-9a-f-]{36})").matcher(page.url());
        org.assertj.core.api.Assertions.assertThat(runId.find()).as(page.url()).isTrue();

        // The report is complete, and its CSV export is exactly what the service returned.
        click(AriaRole.LINK, "Report");
        assertThat(heading(Pattern.compile("Report for run"))).isVisible();
        assertThat(page.getByText(Pattern.compile("Partial report"))).hasCount(0);
        var served = new AtomicReference<byte[]>();
        page.route("**/api/v1/runs/*/report", route -> {
            var response = route.fetch();
            if ("text/csv".equals(route.request().headerValue("accept"))) {
                served.set(response.body());
            }
            route.fulfill(new Route.FulfillOptions().setResponse(response));
        });
        var download = page.waitForDownload(() -> click("Export CSV"));
        org.assertj.core.api.Assertions.assertThat(Files.readAllBytes(download.path()))
                .isNotEmpty()
                .isEqualTo(served.get());

        // Compare the two variants of the run.
        click(AriaRole.LINK, "Comparisons");
        page.getByLabel("Baseline run ID").fill(runId.group(1));
        page.getByLabel("Baseline variant").fill(Manifests.BASELINE);
        page.getByLabel("Candidate run ID").fill(runId.group(1));
        page.getByLabel("Candidate variant").fill(Manifests.CANDIDATE);
        click("Compare");
        assertThat(heading("Comparison of baseline and candidate")).isVisible();
        assertThat(page.getByText("Declared differences")).isVisible();

        // Inspect one case as a player and as an authorized observer.
        click(AriaRole.LINK, "baseline");
        page.getByRole(AriaRole.TABLE, new Page.GetByRoleOptions().setName("Cases of this run"))
                .getByRole(AriaRole.LINK)
                .first()
                .click();
        assertThat(heading(Pattern.compile("^Case "))).isVisible();
        click("View as player");
        assertThat(heading("Player view: seat 0")).isVisible();
        click("View as observer…");
        assertThat(heading("Observer evidence")).isVisible();
        assertThat(page.getByText(Pattern.compile("Observer access denied"))).hasCount(0);

        // Reproduce it: queued behind other work first, then exactly the recording.
        click("Reproduce this case");
        assertThat(page.getByText(Pattern.compile("matched the recording exactly")))
                .isVisible(runWait());

        evidence.record("journeyRunId", runId.group(1));
        evidence.record("journeyOutcome", "MATCH");
    }

    private void startWithDraft(JsonNode manifest) {
        var stored = Http.JSON.writeValueAsString(DraftJson.storedDraft(manifest));
        // Seeded once per tab, so freezing (which clears the draft) is not undone by the next page load.
        context.addInitScript("if (location.origin === '%s' && !sessionStorage.getItem('seeded')) {"
                        .formatted(deployment.workbenchUrl())
                + " sessionStorage.setItem('seeded', '1'); sessionStorage.setItem('%s', %s); }"
                        .formatted(DRAFT_KEY, Http.JSON.writeValueAsString(stored)));
    }

    private void signInAndOpen(String subject, String path) {
        page.navigate(deployment.workbenchUrl() + path);
        click("Sign in");
        page.locator("input[name=username]").fill(subject);
        page.locator("input[type=submit]").click();
        page.waitForURL(url -> url.startsWith(deployment.workbenchUrl()) && !url.contains("/auth/callback"));
    }

    private Locator heading(String name) {
        return page.getByRole(
                AriaRole.HEADING, new Page.GetByRoleOptions().setName(name).setExact(true));
    }

    private Locator heading(Pattern name) {
        return page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName(name));
    }

    private void click(String button) {
        click(AriaRole.BUTTON, button);
    }

    private void click(AriaRole role, String name) {
        page.getByRole(role, new Page.GetByRoleOptions().setName(name)).click();
    }

    private static LocatorAssertions.IsVisibleOptions runWait() {
        return new LocatorAssertions.IsVisibleOptions().setTimeout((double) RUN_WAIT.toMillis());
    }

    /** The form's unsent-draft representation of a manifest, so the browser starts from a filled-in form. */
    static final class DraftJson {

        private DraftJson() {}

        static JsonNode storedDraft(JsonNode manifest) {
            var draft = Http.JSON.createObjectNode();
            draft.put("name", manifest.get("name").asString());
            var variants = draft.putArray("variants");
            manifest.get("variants").forEach(variant -> {
                var entry = variants.addObject();
                entry.put("label", variant.get("label").asString());
                entry.putObject("rulesArtifact")
                        .put("digest", variant.at("/rulesArtifact/digest").asString());
                entry.putObject("contentArtifact")
                        .put("digest", variant.at("/contentArtifact/digest").asString());
                entry.put(
                        "effectiveRulesDigest",
                        variant.get("effectiveRulesDigest").asString());
                entry.put(
                        "effectiveContentDigest",
                        variant.get("effectiveContentDigest").asString());
            });
            var services = draft.putObject("services");
            for (var name : List.of("gameService", "timelineService", "readService")) {
                var service = services.putObject(name);
                service.put(
                        "imageDigest",
                        manifest.at("/services/" + name + "/imageDigest").asString());
                service.put(
                        "sourceRevision",
                        manifest.at("/services/" + name + "/sourceRevision").asString());
            }
            var contracts = draft.putArray("contracts");
            manifest.get("contracts").properties().forEach(contract -> {
                var entry = contracts.addObject();
                entry.put("module", contract.getKey());
                entry.put("version", contract.getValue().asString());
            });
            var policies = draft.putArray("policies");
            manifest.get("policies").forEach(policy -> {
                var entry = policies.addObject();
                entry.put("id", policy.get("id").asString());
                entry.put("version", policy.get("version").asString());
                entry.put("artifactDigest", policy.get("artifactDigest").asString());
                entry.put("parameters", Http.JSON.writeValueAsString(policy.get("parameters")));
                var seats = entry.putArray("seats");
                policy.get("seatAssignment")
                        .forEach(seat -> seats.add(seat.get("seatIndex").asString()));
            });
            var seeds = new ArrayList<String>();
            manifest.get("seeds").forEach(seed -> seeds.add(seed.asString()));
            draft.put("seeds", String.join("\n", seeds));
            draft.set("playerCounts", manifest.get("playerCounts"));
            draft.set("factionSets", manifest.get("factionSets"));
            draft.put("concurrency", manifest.get("concurrency").asString());
            draft.put(
                    "caseWallTimeoutSeconds",
                    manifest.get("caseWallTimeoutSeconds").asString());
            draft.put(
                    "maxRejectedCandidatesPerWindow",
                    manifest.get("maxRejectedCandidatesPerWindow").asString());

            var stored = Http.JSON.createObjectNode();
            stored.set("draft", draft);
            stored.putNull("basedOn");
            return stored;
        }
    }
}
