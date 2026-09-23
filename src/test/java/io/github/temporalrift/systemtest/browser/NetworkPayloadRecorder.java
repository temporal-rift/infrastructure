package io.github.temporalrift.systemtest.browser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;

/**
 * Captures every request/response body a {@link Page} exchanges, from the moment it is attached,
 * so private-view isolation can be asserted against the full session rather than one late snapshot
 * that could miss a leak that self-corrected before game end.
 */
final class NetworkPayloadRecorder {

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
        } catch (RuntimeException exception) {
            requestBody = null;
        }
        String responseBody;
        try {
            responseBody = response.text();
        } catch (RuntimeException exception) {
            // Non-text responses (e.g. static assets) carry nothing an isolation check needs.
            responseBody = null;
        }
        exchanges.add(new Exchange(response.url(), requestBody, response.status(), responseBody));
    }

    List<Exchange> capturedExchanges() {
        return List.copyOf(exchanges);
    }
}
