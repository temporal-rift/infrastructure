package io.github.temporalrift.systemtest.browser;

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.AriaRole;

/** Drives the deployed client's accessible controls; target legality remains server-owned. */
final class GameScreen {

    enum ActionSubmission {
        NONE,
        CARD,
        SPECIAL,
        PASS
    }

    // Short and explicit: these probe reads run inside polling predicates (see
    // BrowserGameScenario.waitUntil), which retry every 500ms. Playwright's own default
    // actionability timeout is 30s — left in place, a probe called before its element exists would
    // block for 30s and throw TimeoutError, aborting the whole poll instead of just failing this one
    // tick. safeInnerText/safeIsEnabled catch exactly that timeout and report "not ready yet".
    private static final int PROBE_TIMEOUT_MS = 2000;
    private static final long RESULTS_REFRESH_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final Pattern GAME_PAGE_PATH = Pattern.compile("/games/[A-Za-z0-9_-]+");

    private final Page page;
    private long lastResultsRefreshNanos;

    GameScreen(Page page) {
        this.page = page;
    }

    private static String safeInnerText(Locator locator) {
        try {
            return locator.innerText(new Locator.InnerTextOptions().setTimeout(PROBE_TIMEOUT_MS));
        } catch (TimeoutError _) {
            return "";
        }
    }

    /** Clicks within the probe timeout. A timed choice (action round, paradox resolution) can close
     * between checking a control and clicking it, and the client then re-renders without it: report
     * that as "not clicked" so the caller's polling loop retries against the current state, instead
     * of blocking on Playwright's 30s default and failing the whole scenario. */
    private static boolean safeClick(Locator locator) {
        try {
            locator.click(new Locator.ClickOptions().setTimeout(PROBE_TIMEOUT_MS));
            return true;
        } catch (TimeoutError _) {
            return false;
        }
    }

    private static boolean safeIsEnabled(Locator locator) {
        try {
            return locator.isEnabled(new Locator.IsEnabledOptions().setTimeout(PROBE_TIMEOUT_MS));
        } catch (TimeoutError _) {
            return false;
        }
    }

    // --- Lobby ---------------------------------------------------------

    void createGame(String playerName) {
        var lobby = page.getByLabel("Game lobby").first();
        lobby.getByLabel("Player name for creating").fill(playerName);
        lobby.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Create game"))
                .click();
    }

    void joinGame(String invitationReference, String playerName) {
        var lobby = page.getByLabel("Game lobby").first();
        lobby.getByLabel("Invitation link or lobby reference").fill(invitationReference);
        lobby.getByLabel("Player name for joining").fill(playerName);
        lobby.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Join game"))
                .click();
    }

    String lobbyId() {
        var dd = page.getByLabel("Game lobby").locator("dl dd").first();
        return dd.count() > 0 ? safeInnerText(dd) : "";
    }

    String invitationUrl() {
        return safeInnerText(page.locator("code"));
    }

    int memberCount() {
        return page.getByLabel("Lobby members").locator("li").count();
    }

    boolean isReadyToStart() {
        var startButton = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Start game"));
        return startButton.count() > 0 && safeIsEnabled(startButton);
    }

    void startGame() {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Start game"))
                .click();
    }

    /** The path of the page this context is on, e.g. {@code /games/{gameId}}. Read from the page itself
     * rather than {@code page.url()}: Playwright Java applies navigation events only while a call into
     * Playwright is running, so inside a polling predicate that makes no other call {@code page.url()}
     * keeps returning the path from before a client-side navigation. */
    String currentPath() {
        return String.valueOf(page.evaluate("() => window.location.pathname"));
    }

    /** True once this context is on a game page ({@code /games/{gameId}}), where the client moves
     * every lobby member when the host starts. */
    boolean isOnGamePage() {
        return GAME_PAGE_PATH.matcher(currentPath()).matches();
    }

    // --- Seven-to-five hand keep -----------------------------------------

    boolean isHandKeepOffered() {
        var section = handSelectionSection();
        return section.getByLabel("Private card offer").locator("li button").count() > 0
                && section.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Confirm five cards"))
                                .count()
                        > 0;
    }

    private Locator handSelectionSection() {
        return page.getByLabel("Hand selection");
    }

    void keepFirstFiveOfferedCards() {
        var section = handSelectionSection();
        var cards = section.getByLabel("Private card offer").locator("li button");
        for (int i = 0; i < 5; i++) {
            cards.nth(i).click();
        }
        section.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Confirm five cards"))
                .click();
    }

    // --- Action round ----------------------------------------------------

    private Locator actionSection() {
        return page.getByLabel("Your action");
    }

    boolean hasOpenActionRound() {
        return actionSection()
                        .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Confirm action"))
                        .count()
                > 0;
    }

    /** "Era {n} · Round {m} · one card or one special this round" — used to prove a round actually
     * advanced (e.g. after a timeout) rather than merely re-rendering the same one. */
    String currentRoundLabel() {
        var label = actionSection().locator("p").first();
        return label.count() > 0 ? safeInnerText(label) : "";
    }

    boolean hasSubmittedAction() {
        return actionSection()
                        .getByText("Your action is submitted for this round.")
                        .count()
                > 0;
    }

    /**
     * Tries enabled options in preference order, resolves each rendered target picker, and submits
     * the first option whose confirmation becomes enabled. Mirrors a human choosing a legal option
     * from the visible controls rather than the harness deciding legality itself.
     *
     * @return the option submitted, or {@code NONE} if no complete option is rendered yet
     */
    ActionSubmission submitFirstAvailableAction(boolean preferSpecial) {
        if (!hasOpenActionRound() || hasSubmittedAction()) {
            return ActionSubmission.NONE;
        }
        var section = actionSection();
        var cards = section.getByLabel("Hand").locator("button");
        var specials = section.getByLabel("Faction specials").locator("button");
        var first = preferSpecial
                ? submitFirstCompletableOption(section, specials, ActionSubmission.SPECIAL)
                : submitFirstCompletableOption(section, cards, ActionSubmission.CARD);
        if (first != ActionSubmission.NONE) {
            return first;
        }
        var fallback = preferSpecial
                ? submitFirstCompletableOption(section, cards, ActionSubmission.CARD)
                : submitFirstCompletableOption(section, specials, ActionSubmission.SPECIAL);
        if (fallback != ActionSubmission.NONE) {
            return fallback;
        }
        // Missing controls or unfinished targets mean retry, not an implicit decision to pass.
        return section.getByLabel("Hand").count() == 1
                        && section.getByLabel("Faction specials").count() == 1
                        && firstEnabled(cards) == null
                        && firstEnabled(specials) == null
                ? submitPass()
                : ActionSubmission.NONE;
    }

    ActionSubmission submitPass() {
        if (!hasOpenActionRound()) {
            return ActionSubmission.NONE;
        }
        var section = actionSection();
        var pass = section.getByRole(
                AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Pass").setExact(true));
        var confirm = section.getByRole(
                AriaRole.BUTTON,
                new Locator.GetByRoleOptions().setName("Confirm action").setExact(true));
        return safeClick(pass) && safeIsEnabled(confirm) && safeClick(confirm)
                ? ActionSubmission.PASS
                : ActionSubmission.NONE;
    }

    private ActionSubmission submitFirstCompletableOption(
            Locator section, Locator options, ActionSubmission actionType) {
        var confirm = section.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Confirm action"));
        for (int i = 0; i < options.count(); i++) {
            var option = options.nth(i);
            if (!safeIsEnabled(option)) {
                continue;
            }
            if (!safeClick(option) || !resolveTargetIfPresent(section)) {
                return ActionSubmission.NONE;
            }
            if (safeIsEnabled(confirm)) {
                return safeClick(confirm) ? actionType : ActionSubmission.NONE;
            }
        }
        return ActionSubmission.NONE;
    }

    boolean hasAvailableSpecial() {
        return firstEnabled(actionSection().getByLabel("Faction specials").locator("button")) != null;
    }

    String currentFaction() {
        return safeInnerText(page.locator("[aria-label='Your faction'] strong"));
    }

    /** Picks the first valid target, if the chosen card or special needs one. Returns false when a
     * control disappeared mid-selection (the round closed), so the caller abandons this attempt. */
    private boolean resolveTargetIfPresent(Locator section) {
        var disguise = section.getByLabel("Choose a disguise");
        if (disguise.count() > 0) {
            var category = firstEnabled(disguise.getByRole(AriaRole.BUTTON));
            return category != null && safeClick(category);
        }
        var targetPicker = section.getByLabel("Choose a target");
        if (targetPicker.count() == 0) {
            return true;
        }
        var eventButtons = targetPicker.getByLabel("Events").locator(":scope > li > button");
        if (eventButtons.count() > 0) {
            if (!safeClick(eventButtons.first())) {
                return false;
            }
            // Pair outcomes must stay on the event whose source was selected.
            var event = eventButtons.first().locator("..");
            var sourceOutcome = firstEnabled(event.locator("ul[aria-label$='source outcomes'] button"));
            if (sourceOutcome != null) {
                if (!safeClick(sourceOutcome)) {
                    return false;
                }
                var targetOutcomes = event.locator("ul[aria-label$='target outcomes'] button");
                var targetOutcome = firstEnabled(targetOutcomes);
                return targetOutcome == null || safeClick(targetOutcome);
            }
            var outcomeButton = firstEnabled(event.locator("ul[aria-label$='outcomes'] button"));
            if (outcomeButton != null) {
                return safeClick(outcomeButton);
            }
            var confirm = section.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Confirm action"));
            for (int i = 1; i < eventButtons.count() && !safeIsEnabled(confirm); i++) {
                if (safeIsEnabled(eventButtons.nth(i)) && !safeClick(eventButtons.nth(i))) {
                    return false;
                }
            }
            return true;
        }
        var playerButtons = targetPicker.getByLabel("Players").getByRole(AriaRole.BUTTON);
        var confirm = section.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Confirm action"));
        for (int i = 0; i < playerButtons.count() && !safeIsEnabled(confirm); i++) {
            var playerButton = playerButtons.nth(i);
            if (safeIsEnabled(playerButton) && !safeClick(playerButton)) {
                return false;
            }
        }
        return true;
    }

    private Locator firstEnabled(Locator candidates) {
        int count = candidates.count();
        for (int i = 0; i < count; i++) {
            var candidate = candidates.nth(i);
            if (safeIsEnabled(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    // --- Paradox resolution ----------------------------------------------

    private Locator paradoxSection() {
        return page.getByLabel("Paradox resolution");
    }

    boolean hasOpenParadoxChoice() {
        return paradoxSection()
                        .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Confirm resolution choice"))
                        .count()
                > 0;
    }

    void submitFirstEligibleParadoxChoice() {
        var section = paradoxSection();
        var confirm =
                section.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Confirm resolution choice"));
        var offer = section.getByLabel("Eligible resolution cards");
        if (offer.count() != 1) {
            return;
        }
        var card = firstEnabled(offer.getByRole(AriaRole.BUTTON));
        if (card != null) {
            if (!safeClick(card)) {
                return;
            }
            var outcome =
                    firstEnabled(section.getByLabel("Affected events").locator("ul[aria-label$='outcomes'] button"));
            if (outcome != null && safeClick(outcome) && safeIsEnabled(confirm)) {
                safeClick(confirm);
            }
            return;
        }
        if (safeClick(section.getByRole(
                        AriaRole.BUTTON,
                        new Locator.GetByRoleOptions().setName("Pass").setExact(true)))
                && safeIsEnabled(confirm)) {
            safeClick(confirm);
        }
    }

    // --- Declaration window ----------------------------------------------

    boolean hasOpenDeclaration() {
        return page.getByLabel("Declaration window")
                        .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Confirm declaration"))
                        .count()
                > 0;
    }

    void submitFirstAvailableDeclaration() {
        var section = page.getByLabel("Declaration window");
        var mode = firstEnabled(section.getByLabel("Eligible declaration modes").getByRole(AriaRole.BUTTON));
        if (mode == null || !safeClick(mode)) {
            return;
        }
        var outcome = firstEnabled(section.getByLabel("Declaration targets").getByRole(AriaRole.BUTTON));
        if (outcome == null || !safeClick(outcome)) {
            return;
        }
        var confirm = section.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Confirm declaration"));
        if (safeIsEnabled(confirm)) {
            safeClick(confirm);
        }
    }

    // --- Results -----------------------------------------------------------

    Locator resultsSection() {
        return page.getByLabel("Final results");
    }

    boolean hasCompleteResults() {
        return resultsSection().getByLabel("Final scores").count() > 0;
    }

    boolean canRefreshResults() {
        if (lastResultsRefreshNanos != 0
                && System.nanoTime() - lastResultsRefreshNanos < RESULTS_REFRESH_INTERVAL_NANOS) {
            return false;
        }
        var button = refreshResultsButton();
        return button.count() > 0 && safeIsEnabled(button);
    }

    void refreshResults() {
        lastResultsRefreshNanos = System.nanoTime();
        refreshResultsButton().click();
    }

    private Locator refreshResultsButton() {
        return resultsSection()
                .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName(Pattern.compile("Refresh results")));
    }

    int finalScoreCount() {
        return resultsSection().getByLabel("Final scores").locator("li").count();
    }

    int handCardCount() {
        return page.getByLabel("Hand", new Page.GetByLabelOptions().setExact(true))
                .locator("li")
                .count();
    }
}
