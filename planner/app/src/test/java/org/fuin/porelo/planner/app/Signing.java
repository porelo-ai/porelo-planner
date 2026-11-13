package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Signs a test's calls as the window signs them, written apart from the planner's own code from the
 * form the description gives, so the two are held to each other.
 */
final class Signing {

    static final String HEADER = "Porelo-Signature";

    static final String ANSWER_HEADER = "Porelo-Answer";

    private static final SecureRandom RANDOM = new SecureRandom();

    private Signing() {}

    /** A signed request, its nonce and the token it was signed under. */
    record Signed(HttpRequest.Builder builder, byte[] nonce, String token) {

        /**
         * Sends the request and holds its answer to its proof.
         *
         * @return The answer.
         */
        HttpResponse<String> send() throws IOException, InterruptedException {
            final HttpResponse<byte[]> answer =
                    HttpClient.newHttpClient()
                            .send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (answer.statusCode() != 401) {
                assertThat(answer.headers().firstValue(ANSWER_HEADER))
                        .as("the proof of the answer %s", answer.statusCode())
                        .hasValue(answer(token, nonce, answer.statusCode(), answer.body()));
            }
            return new HttpResponse<>() {
                @Override
                public int statusCode() {
                    return answer.statusCode();
                }

                @Override
                public HttpRequest request() {
                    return answer.request();
                }

                @Override
                public java.util.Optional<HttpResponse<String>> previousResponse() {
                    return java.util.Optional.empty();
                }

                @Override
                public java.net.http.HttpHeaders headers() {
                    return answer.headers();
                }

                @Override
                public String body() {
                    return new String(answer.body(), StandardCharsets.UTF_8);
                }

                @Override
                public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
                    return answer.sslSession();
                }

                @Override
                public URI uri() {
                    return answer.uri();
                }

                @Override
                public HttpClient.Version version() {
                    return answer.version();
                }
            };
        }
    }

    /**
     * Asks a planner for its proof of a new challenge, unsigned, as the window asks it.
     *
     * @param port The planner's port.
     * @param token The token the proof must be made under.
     * @return The status and body of the answer, and whether its proof holds.
     */
    static String askProof(final int port, final String token)
            throws IOException, InterruptedException {
        final byte[] challenge = new byte[32];
        RANDOM.nextBytes(challenge);
        final HttpResponse<String> answer =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(
                                                URI.create("http://127.0.0.1:" + port + "/proof"))
                                        .timeout(Duration.ofSeconds(10))
                                        .header("Content-Type", "application/json")
                                        .POST(
                                                HttpRequest.BodyPublishers.ofString(
                                                        "{\"challenge\":\""
                                                                + base64(challenge)
                                                                + "\"}"))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        final boolean holds =
                answer.body().equals("{\"proof\":\"" + proof(token, port, challenge) + "\"}");
        return answer.statusCode() + (holds ? " proven" : " not proven: " + answer.body());
    }

    /**
     * Opens a project's changes stream, signed, and holds its opening to its proof.
     *
     * @param address The planner's address.
     * @param path The stream's path.
     * @return The stream's answer, its lines read as they come.
     */
    static HttpResponse<Stream<String>> stream(
            final RuntimeDirectories.Address address, final String path)
            throws IOException, InterruptedException {
        final Signed signed = sign(address, "GET", path, null, null);
        final HttpResponse<Stream<String>> answer =
                HttpClient.newHttpClient()
                        .send(signed.builder().build(), HttpResponse.BodyHandlers.ofLines());
        assertThat(answer.headers().firstValue(ANSWER_HEADER))
                .as("the proof of the stream's opening")
                .hasValue(answer(address.token(), signed.nonce(), answer.statusCode(), null));
        return answer;
    }

    /**
     * Signs a call to the planner whose address file a test read.
     *
     * @param address The address.
     * @param method The method.
     * @param path The path, with its query.
     * @param type The content type, or {@code null} for none.
     * @param body The body, or {@code null} for none.
     * @return The signed request.
     */
    static Signed sign(
            final RuntimeDirectories.Address address,
            final String method,
            final String path,
            final @Nullable String type,
            final @Nullable String body) {
        return sign(address.port(), address.token(), method, path, type, body);
    }

    /**
     * Signs a call to a planner on the loopback address.
     *
     * @param port The planner's port.
     * @param token The planner's token.
     * @param method The method.
     * @param path The path, with its query.
     * @param type The content type, or {@code null} for none.
     * @param body The body, or {@code null} for none.
     * @return The signed request.
     */
    static Signed sign(
            final int port,
            final String token,
            final String method,
            final String path,
            final @Nullable String type,
            final @Nullable String body) {
        final byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        final byte[] nonce = nonce();
        final HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(60))
                        .method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofByteArray(bytes))
                        .header(
                                HEADER,
                                header(
                                        token,
                                        method,
                                        path,
                                        "127.0.0.1:" + port,
                                        type == null ? "" : type,
                                        bytes,
                                        now(),
                                        nonce));
        if (type != null) {
            builder.header("Content-Type", type);
        }
        return new Signed(builder, nonce, token);
    }

    /**
     * Signs a MockMvc request as it stands when it is performed.
     *
     * @param token The token.
     * @return The processor that adds the signature.
     */
    static RequestPostProcessor mvc(final String token) {
        return request -> {
            if (request.getHeader("Host") == null) {
                request.addHeader("Host", "localhost");
            }
            final String query = request.getQueryString();
            final byte[] body = request.getContentAsByteArray();
            final String type = request.getHeader("Content-Type");
            request.addHeader(
                    HEADER,
                    header(
                            token,
                            request.getMethod(),
                            request.getRequestURI() + (query == null ? "" : "?" + query),
                            request.getHeader("Host"),
                            type == null ? "" : type,
                            body == null ? new byte[0] : body,
                            now(),
                            nonce()));
            return request;
        };
    }

    /**
     * The signature header of a request.
     *
     * @return The header's value.
     */
    static String header(
            final String token,
            final String method,
            final String target,
            final String host,
            final String type,
            final byte[] body,
            final long time,
            final byte[] nonce) {
        final ByteArrayOutputStream form = labeled("porelo-planner-request-1");
        field(form, method.getBytes(StandardCharsets.UTF_8));
        field(form, target.getBytes(StandardCharsets.UTF_8));
        field(form, host.getBytes(StandardCharsets.UTF_8));
        field(form, type.getBytes(StandardCharsets.UTF_8));
        field(form, sha256(body));
        field(form, Long.toString(time).getBytes(StandardCharsets.US_ASCII));
        field(form, nonce);
        return "porelo-planner-request-1;t="
                + time
                + ";n="
                + base64(nonce)
                + ";s="
                + base64(mac(token, form.toByteArray()));
    }

    /**
     * The proof header an answer must carry.
     *
     * @param body The answer's body, or {@code null} for the opening of a stream.
     * @return The header's value.
     */
    static String answer(
            final String token,
            final byte[] nonce,
            final int status,
            final byte @Nullable [] body) {
        final ByteArrayOutputStream form = labeled("porelo-planner-answer-1");
        field(form, nonce);
        field(form, Integer.toString(status).getBytes(StandardCharsets.US_ASCII));
        field(form, body == null ? new byte[0] : sha256(body));
        return "porelo-planner-answer-1;s=" + base64(mac(token, form.toByteArray()));
    }

    /**
     * The proof the planner must answer for a challenge.
     *
     * @return The proof in base64url.
     */
    static String proof(final String token, final int port, final byte[] challenge) {
        final ByteArrayOutputStream form = labeled("porelo-planner-proof-1");
        form.write(port >>> 8);
        form.write(port);
        form.writeBytes(challenge);
        return base64(mac(token, form.toByteArray()));
    }

    static byte[] nonce() {
        final byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        return nonce;
    }

    static long now() {
        return System.currentTimeMillis() / 1000;
    }

    static String base64(final byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static byte[] mac(final String token, final byte[] message) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(message);
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] sha256(final byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ByteArrayOutputStream labeled(final String label) {
        final ByteArrayOutputStream form = new ByteArrayOutputStream();
        form.writeBytes(label.getBytes(StandardCharsets.US_ASCII));
        form.write(0);
        return form;
    }

    private static void field(final ByteArrayOutputStream form, final byte[] bytes) {
        form.write(bytes.length >>> 24);
        form.write(bytes.length >>> 16);
        form.write(bytes.length >>> 8);
        form.write(bytes.length);
        form.writeBytes(bytes);
    }
}
