package io.github.temporalrift.systemtest.browser;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Checks page-object targeting independently of the deployment's random deals and phase timers. */
class GameScreenIT {

    private Playwright playwright;
    private Browser browser;
    private Page page;
    private GameScreen screen;

    @BeforeEach
    void openBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
        page = browser.newPage();
        screen = new GameScreen(page);
    }

    @AfterEach
    void closeBrowser() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    @Test
    void nullifySelectsTwoDistinctEnabledOpponents() {
        actionBoard("""
                <fieldset aria-label="Choose a target"><ul aria-label="Players">
                  <li><button disabled>Disconnected player</button></li>
                  <li><button onclick="pick('second', 2)">Second</button></li>
                  <li><button onclick="pick('third', 2)">Third</button></li>
                </ul></fieldset>
                """);
        assertThat(screen.submitFirstAvailableAction(false)).isEqualTo(GameScreen.ActionSubmission.CARD);
        assertThat(chosenTargets()).containsExactly("second", "third");
        assertThat(screen.hasSubmittedAction()).isTrue();
    }

    @Test
    void scanSelectsAllThreeEventsWithoutSelectingOutcomes() {
        actionBoard("""
                <fieldset aria-label="Choose a target"><ol aria-label="Events">
                  <li><button onclick="pick('first', 3)">Target First</button></li>
                  <li><button onclick="pick('second', 3)">Target Second</button></li>
                  <li><button onclick="pick('third', 3)">Target Third</button></li>
                </ol></fieldset>
                """);
        assertThat(screen.submitFirstAvailableAction(false)).isEqualTo(GameScreen.ActionSubmission.CARD);
        assertThat(chosenTargets()).containsExactly("first", "second", "third");
    }

    @Test
    void decoyChoosesADisguiseWithoutAnEventOrPlayerTarget() {
        actionBoard("""
                <fieldset aria-label="Choose a disguise">
                  <button onclick="pick('disruption', 1)">Disruption</button>
                </fieldset>
                """);
        assertThat(screen.submitFirstAvailableAction(false)).isEqualTo(GameScreen.ActionSubmission.CARD);
        assertThat(chosenTargets()).containsExactly("disruption");
    }

    @Test
    void swingKeepsItsSourceAndTargetOnTheSameEvent() {
        actionBoard("""
                <fieldset aria-label="Choose a target"><ol aria-label="Events">
                  <li><button>Target First</button>
                    <ul id="pair" aria-label="First source outcomes">
                      <li><button onclick="pick('source', 2); this.disabled=true;
                        document.getElementById('pair').setAttribute('aria-label', 'First target outcomes')">
                        From</button></li>
                      <li><button onclick="pick('target', 2)">To</button></li>
                    </ul>
                  </li>
                  <li><button>Target Second</button>
                    <ul aria-label="Second source outcomes"><li><button>Unrelated source</button></li></ul>
                  </li>
                </ol></fieldset>
                """);
        assertThat(screen.submitFirstAvailableAction(false)).isEqualTo(GameScreen.ActionSubmission.CARD);
        assertThat(chosenTargets()).containsExactly("source", "target");
    }

    @Test
    void ordinaryCardChoosesAnOutcomeOnTheEventBoard() {
        actionBoard("""
                <fieldset aria-label="Choose a target"><ol aria-label="Events">
                  <li><button>Target First</button><ul aria-label="First outcomes">
                    <li><button onclick="pick('outcome', 1)">Chosen outcome</button></li>
                  </ul></li>
                </ol></fieldset>
                """);
        assertThat(screen.submitFirstAvailableAction(false)).isEqualTo(GameScreen.ActionSubmission.CARD);
        assertThat(chosenTargets()).containsExactly("outcome");
    }

    @Test
    void targetFreeSpecialUsesTheFactionRail() {
        actionBoard("");
        page.getByLabel("Faction specials").evaluate("""
                element => element.innerHTML = `<li><button onclick="
                  document.getElementById('confirm').disabled=false">Obscure</button></li>`
                """);
        assertThat(screen.submitFirstAvailableAction(true)).isEqualTo(GameScreen.ActionSubmission.SPECIAL);
        assertThat(chosenTargets()).isEmpty();
        assertThat(screen.hasSubmittedAction()).isTrue();
    }

    @Test
    void emptyActionHandCanPassAndCannotSubmitAgain() {
        actionBoard("");
        page.getByLabel("Hand").locator("li").evaluate("element => element.remove()");
        assertThat(screen.submitFirstAvailableAction(false)).isEqualTo(GameScreen.ActionSubmission.PASS);
        assertThat(screen.hasSubmittedAction()).isTrue();
        assertThat(screen.submitFirstAvailableAction(false)).isEqualTo(GameScreen.ActionSubmission.NONE);
    }

    @Test
    void incompleteCardTargetsRetryWithoutPassingThenSubmitWhenReady() {
        actionBoard("<fieldset aria-label=\"Choose a target\"><ul aria-label=\"Players\"></ul></fieldset>");
        assertThat(screen.submitFirstAvailableAction(false)).isEqualTo(GameScreen.ActionSubmission.NONE);
        assertThat(screen.hasSubmittedAction()).isFalse();
        assertThat(page.locator("#confirm").isEnabled()).isFalse();
        page.getByLabel("Players").evaluate("""
                element => element.innerHTML = `<li><button onclick="pick('opponent', 1)">Opponent</button></li>`
                """);
        assertThat(screen.submitFirstAvailableAction(false)).isEqualTo(GameScreen.ActionSubmission.CARD);
        assertThat(chosenTargets()).containsExactly("opponent");
    }

    @Test
    void missingActionOffersDoNotCountAsAnEmptyHand() {
        actionBoard("");
        page.getByLabel("Hand").evaluate("element => element.remove()");
        assertThat(screen.submitFirstAvailableAction(false)).isEqualTo(GameScreen.ActionSubmission.NONE);
        assertThat(screen.hasSubmittedAction()).isFalse();
    }

    @Test
    void disabledActionOptionsCanPass() {
        actionBoard("");
        page.getByLabel("Hand").locator("button").evaluate("element => element.disabled=true");
        assertThat(screen.submitFirstAvailableAction(false)).isEqualTo(GameScreen.ActionSubmission.PASS);
    }

    @Test
    void incompleteResolutionTargetsRetryWithoutPassingThenSubmitWhenReady() {
        page.setContent("""
                <section aria-label="Paradox resolution">
                  <ul aria-label="Eligible resolution cards"><li><button>Stabilize</button></li></ul>
                  <fieldset aria-label="Affected events"><ul aria-label="First outcomes"></ul></fieldset>
                  <button id="confirm" disabled onclick="this.parentElement.innerHTML='Resolution accepted'">
                    Confirm resolution choice
                  </button>
                  <button onclick="document.getElementById('confirm').disabled=false">Pass</button>
                </section>
                """);
        screen.submitFirstEligibleParadoxChoice();
        assertThat(page.locator("#confirm").isEnabled()).isFalse();
        page.getByLabel("First outcomes").evaluate("""
                element => element.innerHTML = `<li><button onclick="
                  document.getElementById('confirm').disabled=false">Chosen outcome</button></li>`
                """);
        screen.submitFirstEligibleParadoxChoice();
        assertThat(page.getByLabel("Paradox resolution").innerText()).contains("Resolution accepted");
    }

    @Test
    void missingResolutionOfferDoesNotPass() {
        page.setContent("""
                <section aria-label="Paradox resolution">
                  <button id="confirm" disabled>Confirm resolution choice</button>
                  <button onclick="document.getElementById('confirm').disabled=false">Pass</button>
                </section>
                """);
        screen.submitFirstEligibleParadoxChoice();
        assertThat(page.locator("#confirm").isEnabled()).isFalse();
    }

    @Test
    void emptyResolutionOfferCanStillPass() {
        page.setContent("""
                <section aria-label="Paradox resolution">
                  <ul aria-label="Eligible resolution cards"></ul>
                  <button id="confirm" disabled onclick="this.parentElement.innerHTML='Resolution accepted'">
                    Confirm resolution choice
                  </button>
                  <button onclick="document.getElementById('confirm').disabled=false">Pass</button>
                </section>
                """);
        assertThat(screen.hasOpenParadoxChoice()).isTrue();
        screen.submitFirstEligibleParadoxChoice();
        assertThat(page.getByLabel("Paradox resolution").innerText()).contains("Resolution accepted");
    }

    @Test
    void resolutionTargetsAnAffectedEventOnTheBoard() {
        page.setContent("""
                <section aria-label="Paradox resolution">
                  <ul aria-label="Eligible resolution cards"><li><button onclick="
                    document.getElementById('affected').hidden=false">Stabilize</button></li></ul>
                  <fieldset id="affected" aria-label="Affected events" hidden>
                    <ul aria-label="First outcomes"><li><button onclick="
                      document.getElementById('confirm').disabled=false">Chosen outcome</button></li></ul>
                  </fieldset>
                  <button id="confirm" disabled onclick="this.parentElement.innerHTML='Resolution accepted'">
                    Confirm resolution choice
                  </button>
                </section>
                """);
        screen.submitFirstEligibleParadoxChoice();
        assertThat(page.getByLabel("Paradox resolution").innerText()).contains("Resolution accepted");
    }

    @Test
    void declarationUsesItsOwnModesAndOutcomeControls() {
        page.setContent("""
                <section aria-label="Declaration window">
                  <ul aria-label="Eligible declaration modes"><li><button onclick="
                    document.getElementById('targets').hidden=false">Rally</button></li></ul>
                  <ul id="targets" aria-label="Declaration targets" hidden><li>
                    <ul aria-label="First outcomes"><li><button onclick="
                      document.getElementById('confirm').disabled=false">Chosen outcome</button></li></ul>
                  </li></ul>
                  <button id="confirm" disabled onclick="this.parentElement.innerHTML='Your declaration was accepted.'">
                    Confirm declaration
                  </button>
                </section>
                """);
        assertThat(screen.hasOpenDeclaration()).isTrue();
        screen.submitFirstAvailableDeclaration();
        assertThat(page.getByLabel("Declaration window").innerText()).contains("Your declaration was accepted.");
        assertThat(screen.hasOpenDeclaration()).isFalse();
    }

    @Test
    void coverageCountsAcceptedRequestsAndExcludesRejectedActions() {
        var recorder = NetworkPayloadRecorder.attachedTo(page);
        page.route("http://fixture.test/**", route -> {
            if (route.request().url().endsWith("/")) {
                route.fulfill(
                        new Route.FulfillOptions().setContentType("text/html").setBody("<html></html>"));
            } else {
                var rejected = route.request().postData().contains("rejected");
                route.fulfill(new Route.FulfillOptions()
                        .setStatus(rejected ? 422 : 202)
                        .setContentType("application/json")
                        .setBody("{}"));
            }
        });
        page.navigate("http://fixture.test/");
        page.evaluate("""
                async () => {
                  const send = (path, body) => fetch(path, {
                    method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)
                  });
                  await send('/api/v1/games/game/eras/1/rounds/1/actions', {
                    actionType: 'CARD', cardInstanceId: 'rejected'
                  });
                  await send('/api/v1/games/game/eras/1/rounds/1/actions', {
                    actionType: 'SPECIAL', specialAction: 'OBSCURE'
                  });
                  await send('/api/v1/games/game/eras/1/declarations', {specialAction: 'RALLY'});
                }
                """);
        assertThat(recorder.acceptedActionTypes()).containsExactly("SPECIAL");
        assertThat(recorder.acceptedActionCount()).isEqualTo(1);
        assertThat(recorder.hasAcceptedDeclaration()).isTrue();
    }

    private void actionBoard(String targets) {
        page.setContent("""
                <section aria-label="Your action">
                  <p>Era 1 · Round 1 · one card or one special this round, or pass</p>
                  <ul aria-label="Hand"><li><button onclick="
                    document.getElementById('targets').hidden=false">Playable card</button></li></ul>
                  <ul aria-label="Faction specials"></ul>
                  <div id="targets" hidden>%s</div>
                  <button id="confirm" disabled onclick="this.parentElement.innerHTML=
                    'Your action is submitted for this round. Private until round closure.'">Confirm action</button>
                  <button onclick="document.getElementById('confirm').disabled=false">Pass</button>
                </section>
                <script>
                  window.chosen = [];
                  function pick(target, required) {
                    window.chosen.push(target);
                    document.getElementById('confirm').disabled = window.chosen.length !== required;
                  }
                </script>
                """.formatted(targets));
    }

    private List<String> chosenTargets() {
        return ((List<?>) page.evaluate("() => window.chosen"))
                .stream().map(String.class::cast).toList();
    }
}
