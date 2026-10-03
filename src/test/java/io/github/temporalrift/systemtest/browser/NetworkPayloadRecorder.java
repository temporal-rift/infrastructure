package io.github.temporalrift.systemtest.browser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import tools.jackson.databind.ObjectMapper;

/**
 * Captures every request/response body a {@link Page} exchanges, from the moment it is attached,
 * so private-view isolation can be asserted against the full session rather than one late snapshot
 * that could miss a leak that self-corrected before game end.
 */
final class NetworkPayloadRecorder {

    private static final ObjectMapper JSON = new ObjectMapper();

    record Exchange(String url, String requestBody, int status, String responseBody) {}

    private final List<Exchange> exchanges = Collections.synchronizedList(new ArrayList<>());

    static NetworkPayloadRecorder attachedTo(Page page) {
        var recorder = new NetworkPayloadRecorder();
        page.onResponse(recorder::capture);
        return recorder;
    }

    private void capture(Response response) {
        String requestBody;
        try {
            requestBody = response.request().postData();
        } catch (RuntimeException _) {
            requestBody = null;
        }
        String responseBody;
        try {
            responseBody = response.text();
        } catch (RuntimeException _) {
            // Non-text responses (e.g. static assets) carry nothing an isolation check needs.
            responseBody = null;
        }
        exchanges.add(new Exchange(response.url(), requestBody, response.status(), responseBody));
    }

    List<Exchange> capturedExchanges() {
        return List.copyOf(exchanges);
    }

    Set<String> acceptedActionTypes() {
        return capturedExchanges().stream()
                .filter(exchange -> exchange.url().matches(".*/eras/[0-9]+/rounds/[0-9]+/actions"))
                .filter(NetworkPayloadRecorder::isAccepted)
                .map(exchange ->
                        JSON.readTree(exchange.requestBody()).path("actionType").asText())
                .collect(Collectors.toSet());
    }

    boolean hasAcceptedDeclaration() {
        return capturedExchanges().stream()
                .anyMatch(exchange -> exchange.url().matches(".*/eras/[0-9]+/declarations") && isAccepted(exchange));
    }

    long acceptedActionCount() {
        return capturedExchanges().stream()
                .filter(exchange -> exchange.url().matches(".*/eras/[0-9]+/rounds/[0-9]+/actions"))
                .filter(NetworkPayloadRecorder::isAccepted)
                .count();
    }

    private static boolean isAccepted(Exchange exchange) {
        return exchange.status() >= 200 && exchange.status() < 300 && exchange.requestBody() != null;
    }
}
