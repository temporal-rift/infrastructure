package io.github.temporalrift.systemtest.browser;

import java.util.List;
import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
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

    private final Page page;

    GameScreen(Page page) {
        this.page = page;
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
        return page.getByLabel("Game lobby").locator("dl dd").first().innerText();
    }

    String invitationUrl() {
        return page.locator("code").innerText();
    }

    int memberCount() {
        return page.getByLabel("Lobby members").locator("li").count();
    }

    boolean isReadyToStart() {
        var startButton = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Start game"));
        return startButton.count() > 0 && startButton.isEnabled();
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
    //
    // Speculative: no hand-keep UI exists on game-client's main branch at the time this suite was
    // written (tracked separately by game-client#7, open with an unmerged PR). Written against
    // that issue's stated contract — an owner-private seven-card offer confirmed down to exactly
    // five — using the same accessible-section convention every other panel in this app follows.
    // If the merged component uses different labels, this method (and only this method) needs a
    // matching update; every other page-object method here targets already-merged, verified markup.

    private static final Pattern KEEP_FIVE_LABEL = Pattern.compile("keep|hand selection", Pattern.CASE_INSENSITIVE);

    boolean isHandKeepOffered() {
        return page.getByRole(AriaRole.LISTITEM).locator("button").count() > 0
                && findHandKeepSection().count() > 0;
    }

    private Locator findHandKeepSection() {
        return page.locator("section").filter(new Locator.FilterOptions().setHasText(KEEP_FIVE_LABEL));
    }

    void keepFirstFiveOfferedCards() {
        var section = findHandKeepSection().first();
        var cards =
                section.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName(Pattern.compile("Grade")));
        int toSelect = Math.min(5, cards.count());
        for (int i = 0; i < toSelect; i++) {
            cards.nth(i).click();
        }
        section.getByRole(
                        AriaRole.BUTTON,
                        new Locator.GetByRoleOptions()
                                .setName(Pattern.compile("Confirm|Keep", Pattern.CASE_INSENSITIVE)))
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
        return label.count() > 0 ? label.innerText() : "";
    }

    boolean hasSubmittedAction() {
        return actionSection()
                        .getByText("Your action is submitted for this round.")
                        .count()
                > 0;
    }

    /** This player's own dealt hand, as rendered only to its owner — the isolation check's ground
     * truth for what must never appear in any other context's captured network traffic. */
    List<String> handCardNames() {
        return actionSection().getByLabel("Hand").locator("li").allInnerTexts();
    }

    /**
     * Selects the first enabled option — a faction special if {@code preferSpecial} and one is
     * enabled, otherwise the first enabled hand card — resolves whatever target picker then
     * appears using only what is rendered, and confirms. Mirrors a human choosing any legal option
     * rather than the harness deciding legality itself.
     */
    void submitFirstAvailableAction(boolean preferSpecial) {
        var section = actionSection();
        Locator chosen = preferSpecial
                ? firstEnabled(section.getByLabel("Faction specials").locator("button"))
                : null;
        if (chosen == null) {
            chosen = firstEnabled(section.getByLabel("Hand").locator("button"));
        }
        if (chosen == null) {
            chosen = firstEnabled(section.getByLabel("Faction specials").locator("button"));
        }
        if (chosen == null) {
            // Nothing enabled yet (the round just opened and is still rendering availability) —
            // the caller polls, so simply not acting this tick is correct; forcing a click here
            // would throw instead of retrying.
            return;
        }
        chosen.click();
        resolveTargetIfPresent(section);
        section.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Confirm action"))
                .click();
    }

    private void resolveTargetIfPresent(Locator section) {
        var targetPicker = section.getByLabel("Choose a target");
        if (targetPicker.count() == 0) {
            return;
        }
        var eventButtons = targetPicker.getByLabel("Events").locator("> li > button");
        if (eventButtons.count() > 0) {
            eventButtons.first().click();
            var outcomeButtons = targetPicker.locator("ul[aria-label$='outcomes'] button");
            if (outcomeButtons.count() > 0) {
                outcomeButtons.first().click();
            }
            var toOutcomeButtons = targetPicker.locator("ul[aria-label$='target outcomes'] button");
            if (toOutcomeButtons.count() > 0) {
                toOutcomeButtons.first().click();
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
            if (candidate.isEnabled()) {
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
        section.getByLabel("Eligible resolution cards")
                .locator("button")
                .first()
                .click();
        section.getByLabel("Affected events")
                .locator("ul[aria-label$='outcomes'] button")
                .first()
                .click();
        section.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Confirm resolution choice"))
                .click();
    }

    // --- Results -----------------------------------------------------------

    Locator resultsSection() {
        return page.getByLabel("Final results");
    }

    boolean hasCompleteResults() {
        return resultsSection().getByLabel("Winners").count() > 0;
    }

    boolean canRefreshResults() {
        return refreshResultsButton().count() > 0;
    }

    void refreshResults() {
        refreshResultsButton().click();
    }

    private Locator refreshResultsButton() {
        return resultsSection()
                .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName(Pattern.compile("Refresh results")));
    }

    List<String> winnerNames() {
        return resultsSection().getByLabel("Winners").locator("li strong").allInnerTexts();
    }
}
