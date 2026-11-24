package org.fuin.porelo.forge.timed;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Stands in for a forge client, so a timed call is named by a forge client's own method. */
public final class FakeForge {

    private final HttpClient http;
    private final URI address;

    /**
     * Calls one address through a client.
     *
     * @param http the client
     * @param address what it calls
     */
    public FakeForge(final HttpClient http, final URI address) {
        this.http = http;
        this.address = address;
    }

    /**
     * Calls the address once with a token in its header, as a forge client does.
     *
     * @param token the token sent
     * @return the answer's status
     * @throws IOException when the call fails
     * @throws InterruptedException when interrupted
     */
    public int issueBoard(final String token) throws IOException, InterruptedException {
        return send(token);
    }

    private int send(final String token) throws IOException, InterruptedException {
        return http.send(
                        HttpRequest.newBuilder(address)
                                .header("Authorization", "Bearer " + token)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }
}
