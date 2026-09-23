package io.github.temporalrift.systemtest.browser;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.AriaRole;

/**
 * Page object over {@code game-client}'s single continuously-updating screen: every panel (lobby,
 * action round, paradox resolution, knowledge, results) is a labeled {@code <section>} that
 * appears or disappears in place rather than a distinct route, so this class exposes one accessor
 * per section rather than one class per "page". Every locator here targets the accessible
 * role/label/text the real component tree already exposes (confirmed by reading
 * {@code SignInPanel}, {@code LobbyPanel}, {@code ActionPanel}, {@code ParadoxResolutionPanel},
 * {@code ResultsPanel} on {@code game-client}'s main branch) — no {@code data-testid} hooks exist
 * or are needed.
 */
final class GameScreen {

    enum ActionSubmission {
        NONE,
        CARD,
        SPECIAL
    }

    // Short and explicit: these probe reads run inside polling predicates (see
    // BrowserGameScenario.waitUntil), which retry every 500ms. Playwright's own default
    // actionability timeout is 30s — left in place, a probe called before its element exists would
    // block for 30s and throw TimeoutError, aborting the whole poll instead of just failing this one
    // tick. safeInnerText/safeIsEnabled catch exactly that timeout and report "not ready yet".
    private static final int PROBE_TIMEOUT_MS = 2000;
    private static final long RESULTS_REFRESH_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);

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

    void waitForGameStarted() {
        page.getByText("Game started.").waitFor();
    }

    /** True once this context's own screen reflects the game having started, whether it is still
     * showing the lobby's "Game started." transition text or has already advanced to hand keep or
     * an open action round (recovered state after a reload can skip straight past the former). */
    boolean hasGameStarted() {
        return page.getByText("Game started.").count() > 0 || isHandKeepOffered() || hasOpenActionRound();
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
        var section = actionSection();
        var cards = section.getByLabel("Hand").locator("button");
        var specials = section.getByLabel("Faction specials").locator("button");
        var first = preferSpecial
                ? submitFirstCompletableOption(section, specials, ActionSubmission.SPECIAL)
                : submitFirstCompletableOption(section, cards, ActionSubmission.CARD);
        if (first != ActionSubmission.NONE) {
            return first;
        }
        return preferSpecial
                ? submitFirstCompletableOption(section, cards, ActionSubmission.CARD)
                : submitFirstCompletableOption(section, specials, ActionSubmission.SPECIAL);
    }

    private ActionSubmission submitFirstCompletableOption(
            Locator section, Locator options, ActionSubmission actionType) {
        var confirm = section.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Confirm action"));
        for (int i = 0; i < options.count(); i++) {
            var option = options.nth(i);
            if (!safeIsEnabled(option)) {
                continue;
            }
            option.click();
            resolveTargetIfPresent(section);
            if (safeIsEnabled(confirm)) {
                confirm.click();
                return actionType;
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

    private void resolveTargetIfPresent(Locator section) {
        var targetPicker = section.getByLabel("Choose a target");
        if (targetPicker.count() == 0) {
            return;
        }
        var eventButtons = targetPicker.getByLabel("Events").locator(":scope > li > button");
        if (eventButtons.count() > 0) {
            eventButtons.first().click();
            var sourceOutcome = firstEnabled(targetPicker.locator("ul[aria-label$='source outcomes'] button"));
            if (sourceOutcome != null) {
                sourceOutcome.click();
                var targetOutcomes = targetPicker.locator("ul[aria-label$='target outcomes'] button");
                var targetOutcome = firstEnabled(targetOutcomes);
                if (targetOutcome != null) {
                    targetOutcome.click();
                }
            } else {
                var outcomeButton = firstEnabled(targetPicker.locator("ul[aria-label$='outcomes'] button"));
                if (outcomeButton != null) {
                    outcomeButton.click();
                } else {
                    var confirm = section.getByRole(
                            AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Confirm action"));
                    for (int i = 1; i < eventButtons.count() && !safeIsEnabled(confirm); i++) {
                        if (safeIsEnabled(eventButtons.nth(i))) {
                            eventButtons.nth(i).click();
                        }
                    }
                }
            }
            return;
        }
        var playerButton = firstEnabled(targetPicker.getByLabel("Players").locator("button"));
        if (playerButton != null) {
            playerButton.click();
        }
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
                                .getByRole(
                                        AriaRole.BUTTON,
                                        new Locator.GetByRoleOptions().setName("Confirm resolution choice"))
                                .count()
                        > 0
                && paradoxSection()
                                .getByLabel("Eligible resolution cards")
                                .locator("button")
                                .count()
                        > 0;
    }

    void submitFirstEligibleParadoxChoice() {
        var section = paradoxSection();
        section.getByLabel("Eligible resolution cards")
                .locator("button")
                .first()
                .click();
        var outcome = firstEnabled(section.getByLabel("Affected events").locator("ul[aria-label$='outcomes'] button"));
        if (outcome == null) {
            return;
        }
        outcome.click();
        var confirm =
                section.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Confirm resolution choice"));
        if (safeIsEnabled(confirm)) {
            confirm.click();
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

    // --- Knowledge (earned intel) ------------------------------------------

    /** This player's own earned intel (Scan/Trace/Intercept), rendered only to its owner — used
     * alongside the network-payload check as a second, DOM-level isolation signal for future
     * intelligence, distinct from the hand-card identity check. */
    List<String> earnedKnowledgeEntries() {
        var list = page.getByLabel("Your earned knowledge");
        return list.count() > 0 ? list.locator("li").allInnerTexts() : List.of();
    }
}
