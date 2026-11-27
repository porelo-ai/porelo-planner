package org.fuin.porelo.planner.app;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lets a request through only when it is signed under the planner's token, on every path, before
 * anything else runs, and proves each answer to it; the one exemption is the proof, {@code POST} on
 * the raw path {@code /proof}, which a caller asks before it sends anything else.
 *
 * <p>Only the request dispatch is checked: it marks the request with an attribute no caller can
 * set, and an async or error dispatch passes only with that mark.
 */
public final class AccessFilter implements Filter {

    /** The seconds a signature's time may be off the planner's clock. */
    static final long WINDOW = 60;

    /** The most characters of an issue's title GitHub takes. */
    static final int MOST_TITLE = 256;

    /** The most characters of an issue's or a comment's body GitHub takes. */
    static final int MOST_TEXT = 65536;

    /**
     * The most UTF-8 bytes one character takes: a character beyond the Basic Multilingual Plane.
     */
    static final int MOST_UTF8_CHAR = 4;

    /** The bytes a body's JSON envelope may take around the texts it carries. */
    static final int ENVELOPE = 4096;

    /**
     * The largest body of a request that carries text from or for an issue's body: the longest
     * title and body GitHub takes, each character in its longest UTF-8 form, with their envelope.
     */
    static final int MOST_TEXT_BODY = (MOST_TITLE + MOST_TEXT) * MOST_UTF8_CHAR + ENVELOPE;

    /** The largest body of a new idea, story or issue. */
    static final int MOST_LARGE_BODY = 1048576;

    /**
     * The largest body of a stage's result: a result file of {@value #MOST_LARGE_BODY} bytes and
     * its envelope.
     */
    static final int MOST_RESULT = MOST_LARGE_BODY + ENVELOPE;

    /**
     * The largest body of any request: the largest of the three bounds {@link #most} gives, so a
     * body larger than any request is refused before it is read.
     */
    static final int MOST_BODY = Math.max(MOST_RESULT, Math.max(MOST_LARGE_BODY, MOST_TEXT_BODY));

    /** The raw path a stage's result is handed in on. */
    private static final Pattern RESULT_PATH =
            Pattern.compile("/projects/[^/?#;%]+/tasks/[^/?#;%]+/result");

    /** The raw paths that create a story or an issue, or several issues in one board change. */
    private static final Pattern CREATION_PATH =
            Pattern.compile("/projects/[^/?#;%]+/(stories|issues|changes)");

    /** The largest body a proof's request may have. */
    static final int MOST_PROOF = 128;

    /** The raw path of the proof. */
    static final String PROOF_PATH = "/proof";

    private static final Logger LOG = LoggerFactory.getLogger(AccessFilter.class);

    private static final String VERIFIED = AccessFilter.class.getName() + ".verified";

    private static final String ANSWER = AccessFilter.class.getName() + ".answer";

    private static final String CHALLENGE = AccessFilter.class.getName() + ".challenge";

    private static final Set<String> JSON_TYPES =
            Set.of("application/json", "application/json;charset=utf-8");

    private static final JsonMapper JSON =
            JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    private final AccessToken token;

    private final Clock clock;

    private final SeenNonces nonces = new SeenNonces(WINDOW);

    /** The mark of a verified request; a caller can set no attribute, let alone this type. */
    private record Verified(byte[] nonce) {}

    /**
     * A proof's challenge, read and checked by the filter, for the proof's controller.
     *
     * @param bytes The 32 bytes of the challenge.
     */
    record Challenge(byte[] bytes) {}

    /**
     * Makes the filter on the system's clock.
     *
     * @param token The token every request is signed under.
     */
    public AccessFilter(final AccessToken token) {
        this(token, Clock.systemUTC());
    }

    /**
     * Makes the filter.
     *
     * @param token The token every request is signed under.
     * @param clock The clock a signature's time is held to.
     */
    AccessFilter(final AccessToken token, final Clock clock) {
        this.token = token;
        this.clock = clock;
    }

    /**
     * The challenge the filter let a proof's request through with.
     *
     * @param request The request.
     * @return Its challenge, or {@code null} when the filter let none through.
     */
    static @Nullable Challenge challenge(final HttpServletRequest request) {
        return request.getAttribute(CHALLENGE) instanceof final Challenge challenge
                ? challenge
                : null;
    }

    @Override
    public void doFilter(
            final ServletRequest servletRequest,
            final ServletResponse servletResponse,
            final FilterChain chain)
            throws IOException, ServletException {
        final HttpServletRequest request = (HttpServletRequest) servletRequest;
        final HttpServletResponse response = (HttpServletResponse) servletResponse;
        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            dispatched(request, response, chain);
            return;
        }
        if (isProof(request)) {
            proof(request, response, chain);
            return;
        }
        // Read before it is checked, as it was sent, but never more than its operation takes.
        final int most = most(request);
        if (request.getContentLengthLong() > most) {
            refuse(response);
            return;
        }
        final byte[] body = request.getInputStream().readNBytes(most + 1);
        if (body.length > most) {
            refuse(response);
            return;
        }
        final byte @Nullable [] nonce = verified(request, body);
        if (nonce == null) {
            refuse(response);
            return;
        }
        request.setAttribute(VERIFIED, new Verified(nonce));
        final ProvenAnswer answer = new ProvenAnswer(response, token, nonce);
        request.setAttribute(ANSWER, answer);
        chain.doFilter(new ReadBody(request, body), answer);
        if (!request.isAsyncStarted()) {
            answer.finish();
        }
    }

    /**
     * The most bytes the request's operation can legally carry, by its method and raw path: a
     * stage's result {@link #MOST_RESULT}, a new story or issue {@link #MOST_LARGE_BODY}, every
     * other request {@link #MOST_TEXT_BODY}; never more than {@link #MOST_BODY}.
     */
    static int most(final HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return MOST_TEXT_BODY;
        }
        if (RESULT_PATH.matcher(request.getRequestURI()).matches()) {
            return MOST_RESULT;
        }
        return CREATION_PATH.matcher(request.getRequestURI()).matches()
                ? MOST_LARGE_BODY
                : MOST_TEXT_BODY;
    }

    /** An async or error dispatch of a request the filter verified, and nothing else. */
    private void dispatched(
            final HttpServletRequest request,
            final HttpServletResponse response,
            final FilterChain chain)
            throws IOException, ServletException {
        if (!(request.getAttribute(VERIFIED) instanceof final Verified verified)) {
            refuse(response);
            return;
        }
        if (request.getDispatcherType() == DispatcherType.ERROR) {
            final ProvenAnswer answer = new ProvenAnswer(response, token, verified.nonce());
            chain.doFilter(request, answer);
            answer.finish();
            return;
        }
        chain.doFilter(request, response);
        if (!request.isAsyncStarted()
                && request.getAttribute(ANSWER) instanceof final ProvenAnswer answer) {
            answer.finish();
        }
    }

    /**
     * Checks a request's one signature against the request as read: label, method, raw target,
     * host, content type, body, a time within the window and a nonce not seen in it.
     *
     * @return The request's nonce, or {@code null} when it is refused.
     */
    private byte @Nullable [] verified(final HttpServletRequest request, final byte[] body) {
        final List<String> signatures = all(request, CallSignature.HEADER);
        final List<String> hosts = all(request, "Host");
        final List<String> types = all(request, "Content-Type");
        if (signatures.size() != 1
                || hosts.size() != 1
                || types.size() > 1
                || request.getHeader("Authorization") != null) {
            return null;
        }
        final CallSignature.Signed signed = CallSignature.read(signatures.getFirst());
        if (signed == null) {
            return null;
        }
        final long now = clock.millis() / 1000;
        if (Math.abs(now - signed.time()) > WINDOW) {
            return null;
        }
        final String query = request.getQueryString();
        final byte[] expected =
                CallSignature.request(
                        token,
                        request.getMethod(),
                        request.getRequestURI() + (query == null ? "" : "?" + query),
                        hosts.getFirst(),
                        types.isEmpty() ? "" : types.getFirst(),
                        body,
                        signed.time(),
                        signed.nonce());
        if (!MessageDigest.isEqual(expected, signed.mac())) {
            return null;
        }
        // Taken only once the signature holds, so no stranger can fill the set.
        if (!nonces.first(CallSignature.base64(signed.nonce()), signed.time(), now)) {
            return null;
        }
        return signed.nonce();
    }

    private static List<String> all(final HttpServletRequest request, final String name) {
        return Collections.list(request.getHeaders(name));
    }

    /** Exactly {@code POST} on the raw path {@code /proof}, without a query. */
    private static boolean isProof(final HttpServletRequest request) {
        return "POST".equals(request.getMethod())
                && PROOF_PATH.equals(request.getRequestURI())
                && request.getQueryString() == null;
    }

    /**
     * Lets a proof's request through only with an {@code application/json} body of at most {@value
     * #MOST_PROOF} bytes holding exactly one member, a challenge of 32 bytes in canonical
     * base64url; anything else answers 400 with an empty body and logs nothing, since a proof is
     * asked of any listener on the port.
     */
    private static void proof(
            final HttpServletRequest request,
            final HttpServletResponse response,
            final FilterChain chain)
            throws IOException, ServletException {
        final byte @Nullable [] challenge = challenge(request.getInputStream(), request);
        if (challenge == null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentLength(0);
            return;
        }
        request.setAttribute(CHALLENGE, new Challenge(challenge));
        chain.doFilter(new ReadBody(request, new byte[0]), response);
    }

    private static byte @Nullable [] challenge(
            final ServletInputStream in, final HttpServletRequest request) throws IOException {
        final List<String> types = all(request, "Content-Type");
        if (types.size() != 1
                || !JSON_TYPES.contains(
                        types.getFirst().toLowerCase(Locale.ROOT).replace(" ", ""))) {
            return null;
        }
        final byte[] body = in.readNBytes(MOST_PROOF + 1);
        if (body.length > MOST_PROOF) {
            return null;
        }
        final JsonNode read;
        try {
            read = JSON.readTree(body);
        } catch (final JacksonException malformed) {
            return null;
        }
        if (read == null || !read.isObject() || read.size() != 1) {
            return null;
        }
        final JsonNode text = read.get("challenge");
        if (text == null || !text.isString()) {
            return null;
        }
        return CallSignature.canonical(text.asString(), CallSignature.CHALLENGE);
    }

    private static void refuse(final HttpServletResponse response) {
        // Only that it happened: what a refused call carried is the caller's, and a loop of
        // refusals must not fill the log with it.
        LOG.info("A call without a valid signature was refused");
        // Set, never sendError: that would hand the call on to Spring's error page.
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", CallSignature.REQUEST);
        response.setContentLength(0);
    }

    /** The request with the body the filter read in its place. */
    private static final class ReadBody extends HttpServletRequestWrapper {

        private final byte[] body;

        private final ByteArrayInputStream in;

        private final ServletInputStream stream;

        ReadBody(final HttpServletRequest request, final byte[] body) {
            super(request);
            this.body = body;
            this.in = new ByteArrayInputStream(body);
            this.stream =
                    new ServletInputStream() {
                        @Override
                        public int read() {
                            return in.read();
                        }

                        @Override
                        public int read(final byte[] bytes, final int offset, final int length) {
                            return in.read(bytes, offset, length);
                        }

                        @Override
                        public boolean isFinished() {
                            return in.available() == 0;
                        }

                        @Override
                        public boolean isReady() {
                            return true;
                        }

                        @Override
                        public void setReadListener(final ReadListener listener) {
                            throw new UnsupportedOperationException(
                                    "the planner reads its bodies blocking");
                        }
                    };
        }

        @Override
        public ServletInputStream getInputStream() {
            return stream;
        }

        @Override
        public BufferedReader getReader() {
            final String encoding = getCharacterEncoding();
            return new BufferedReader(
                    new InputStreamReader(
                            new ByteArrayInputStream(body),
                            encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding)));
        }
    }
}
