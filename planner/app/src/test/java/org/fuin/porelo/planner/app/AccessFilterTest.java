package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class AccessFilterTest {

    private static final long NOW = 1_800_000_000L;

    private static final String HOST = "127.0.0.1:4711";

    private static final String JSON_TYPE = "application/json; charset=utf-8";

    private static final String BODY = "{\"idea\":\"a private idea\"}";

    private static final String BOARD = "{\"board\":1}";

    private final AccessToken token = AccessToken.random();

    private final AccessFilter filter =
            new AccessFilter(token, Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));

    private final Logger logger = (Logger) LoggerFactory.getLogger(AccessFilter.class);

    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();

    @BeforeEach
    void listen() {
        lines.start();
        logger.addAppender(lines);
    }

    @AfterEach
    void close() {
        logger.detachAppender(lines);
    }

    /** What the filter handed on, if anything: the body each request the chain saw carried. */
    private static final class Chain implements FilterChain {

        private final List<String> bodies = new ArrayList<>();

        private final Consumer<HttpServletResponse> answer;

        Chain(final Consumer<HttpServletResponse> answer) {
            this.answer = answer;
        }

        Chain() {
            this(response -> {});
        }

        @Override
        public void doFilter(final ServletRequest request, final ServletResponse response)
                throws IOException {
            bodies.add(new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            answer.accept((HttpServletResponse) response);
        }
    }

    @Test
    void aSignedRequestIsHandedOnWithItsBodyAndItsAnswerProvesItself() throws Exception {
        // Harm: the owner's window could not reach its planner, or could not tell its answer from a
        // stranger's forged board.
        final byte[] nonce = Signing.nonce();
        final MockHttpServletRequest request = signed("POST", "/projects/p/ideas", BODY, nonce);
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final Chain chain = new Chain(AccessFilterTest::answerBoard);
        filter.doFilter(request, response, chain);
        assertThat(chain.bodies).as("the body the controller read").containsExactly(BODY);
        assertThat(response.getStatus()).as("the status").isEqualTo(200);
        assertThat(response.getContentAsString()).as("the board").isEqualTo(BOARD);
        assertThat(response.getHeader(Signing.ANSWER_HEADER))
                .as("the answer's proof over the nonce, the status and the body")
                .isEqualTo(Signing.answer(token.text(), nonce, 200, bytes(BOARD)));
        assertThat(lines.list).as("the lines of a call let through").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "no signature",
                "a bearer beside the signature",
                "two signatures",
                "another method",
                "another target",
                "another query",
                "another host",
                "another content type",
                "no content type signed",
                "another body",
                "a time past the window",
                "a time ahead of the window",
                "another token",
                "an unknown label",
                "a padded nonce",
                "a proof's answer as the signature",
                "an answer's proof as the signature"
            })
    void aRequestNotSignedForItselfIsRefusedAndHandedOnNowhere(final String kind) throws Exception {
        // Harm: any program of any account reaches the loopback address, and a caught signature
        // must not carry another request, a later one or one to another planner.
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final Chain chain = new Chain(AccessFilterTest::answerBoard);
        filter.doFilter(forged(kind), response, chain);
        assertRefused(response, chain, kind);
    }

    @Test
    void aSignedRequestSentAgainIsRefused() throws Exception {
        // Harm: a caught request would be carried out a second time, such as a move or a start.
        final byte[] nonce = Signing.nonce();
        final Chain first = new Chain();
        filter.doFilter(
                signed("POST", "/projects/p/ideas", BODY, nonce),
                new MockHttpServletResponse(),
                first);
        assertThat(first.bodies).as("the first request handed on").hasSize(1);
        final MockHttpServletResponse again = new MockHttpServletResponse();
        final Chain chain = new Chain();
        filter.doFilter(signed("POST", "/projects/p/ideas", BODY, nonce), again, chain);
        assertRefused(again, chain, "the same request again");
    }

    @Test
    void aNonceIsForgottenOnlyOnceItsTimeIsRefusedAnyway() {
        // Harm: a set that kept every nonce grows for as long as the planner runs; one that forgot
        // too early lets a caught request through again.
        final SeenNonces seen = new SeenNonces(AccessFilter.WINDOW);
        assertThat(seen.first("a", NOW, NOW)).as("a new nonce").isTrue();
        assertThat(seen.first("a", NOW, NOW + AccessFilter.WINDOW))
                .as("the same nonce at the window's end, while its time still passes")
                .isFalse();
        assertThat(seen.first("b", NOW + AccessFilter.WINDOW + 1, NOW + AccessFilter.WINDOW + 1))
                .as("another nonce after the window")
                .isTrue();
        assertThat(seen.size()).as("the nonces kept once the first one's time is refused").isOne();
    }

    @Test
    void aBodyLargerThanAnyOperationTakesIsRefusedWithoutBeingHeld() throws Exception {
        // Harm: a stranger's endless or chunked body held in memory before it is checked.
        final Counted declared = new Counted(AccessFilter.MOST_BODY + 1L);
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final Chain chain = new Chain();
        filter.doFilter(
                withBody(
                        signed("POST", "/projects/p/ideas", BODY, null),
                        declared,
                        AccessFilter.MOST_BODY + 1L),
                response,
                chain);
        assertRefused(response, chain, "a declared length past the bound");
        assertThat(declared.read).as("the bytes read of a declared length past the bound").isZero();

        lines.list.clear();
        final Counted endless = new Counted(Long.MAX_VALUE);
        final MockHttpServletResponse chunked = new MockHttpServletResponse();
        final Chain chunkedChain = new Chain();
        filter.doFilter(
                withBody(signed("POST", "/projects/p/ideas", BODY, null), endless, -1),
                chunked,
                chunkedChain);
        assertRefused(chunked, chunkedChain, "an endless body without a length");
        assertThat(endless.read)
                .as("the bytes read of an endless body")
                .isEqualTo(AccessFilter.MOST_BODY + 1L);
    }

    @Test
    void anAsyncOrErrorDispatchPassesOnlyAfterTheRequestWasVerifiedAndIsNotVerifiedAgain()
            throws Exception {
        // Harm: a dispatch the filter let through unchecked is a way around it; one it checked
        // again would refuse the changes stream and an error status, whose nonce it has taken.
        for (final DispatcherType type : List.of(DispatcherType.ASYNC, DispatcherType.ERROR)) {
            final MockHttpServletRequest bare = signed("GET", "/projects/p/events", null, null);
            bare.setDispatcherType(type);
            final MockHttpServletResponse refused = new MockHttpServletResponse();
            final Chain bareChain = new Chain();
            filter.doFilter(bare, refused, bareChain);
            assertRefused(refused, bareChain, "a bare " + type + " dispatch");
            lines.list.clear();
        }

        final byte[] nonce = Signing.nonce();
        final MockHttpServletRequest request = signed("GET", "/projects/p/events", null, nonce);
        filter.doFilter(request, new MockHttpServletResponse(), new Chain());
        final Chain async = new Chain();
        request.setDispatcherType(DispatcherType.ASYNC);
        filter.doFilter(request, new MockHttpServletResponse(), async);
        assertThat(async.bodies).as("the async dispatch handed on").hasSize(1);

        final MockHttpServletResponse error = new MockHttpServletResponse();
        error.setStatus(500);
        request.setDispatcherType(DispatcherType.ERROR);
        final Chain errorChain = new Chain(AccessFilterTest::answerBoard);
        filter.doFilter(request, error, errorChain);
        assertThat(errorChain.bodies).as("the error dispatch handed on").hasSize(1);
        assertThat(error.getHeader(Signing.ANSWER_HEADER))
                .as("the error answer's proof")
                .isEqualTo(Signing.answer(token.text(), nonce, 500, bytes(BOARD)));
        assertThat(lines.list).as("the lines of the dispatches of a verified request").isEmpty();
    }

    @Test
    void theOpeningOfAnEventStreamProvesItselfBeforeItsFirstEvent() throws Exception {
        // Harm: a stranger on the port could feed the screen a forged board through the stream.
        final byte[] nonce = Signing.nonce();
        final MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(
                signed("GET", "/projects/p/events", null, nonce),
                response,
                new Chain(
                        answer -> {
                            answer.setContentType("text/event-stream");
                            try {
                                answer.getOutputStream().write(bytes("event:board\n"));
                                answer.flushBuffer();
                            } catch (final IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        }));
        assertThat(response.isCommitted()).as("the stream sent at once").isTrue();
        assertThat(response.getContentAsString()).as("the event").isEqualTo("event:board\n");
        assertThat(response.getHeader(Signing.ANSWER_HEADER))
                .as("the opening's proof over the status and the nonce")
                .isEqualTo(Signing.answer(token.text(), nonce, 200, null));
    }

    @Test
    void theProofAnswersTheHmacOverThePortAndTheChallenge() throws Exception {
        // Harm: the window could not tell its planner from a process that took a freed port.
        final byte[] challenge = new byte[32];
        Arrays.fill(challenge, (byte) 7);
        final MockHttpServletResponse response =
                proof("{\"challenge\":\"" + Signing.base64(challenge) + "\"}", "application/json");
        assertThat(response.getStatus()).as("the proof's status").isEqualTo(200);
        assertThat(
                        JsonMapper.builder()
                                .build()
                                .readTree(response.getContentAsString())
                                .get("proof")
                                .asString())
                .as("the proof")
                .isEqualTo(Signing.proof(token.text(), 4711, challenge));
        assertThat(lines.list).as("the lines of a proof").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "a challenge of 31 bytes",
                "a challenge of 33 bytes",
                "a challenge not in its canonical form",
                "a padded challenge",
                "a challenge in base64 not base64url",
                "an extra key",
                "the challenge twice",
                "a challenge that is no text",
                "no object",
                "not JSON",
                "a body past the bound",
                "another content type",
                "no content type"
            })
    void aProofWithAnythingButOneCanonicalChallengeIsRefusedEmptyAndUnlogged(final String kind)
            throws Exception {
        // Harm: a lax proof is an HMAC oracle under the token for whatever a stranger sends, and a
        // logged refusal lets any account fill the person's log.
        final String good = Signing.base64(new byte[32]);
        final String body =
                switch (kind) {
                    case "a challenge of 31 bytes" -> challenge(Signing.base64(new byte[31]));
                    case "a challenge of 33 bytes" -> challenge(Signing.base64(new byte[33]));
                    case "a challenge not in its canonical form" ->
                            challenge(good.substring(0, 42) + "B");
                    case "a padded challenge" -> challenge(good + "=");
                    case "a challenge in base64 not base64url" -> challenge("/".repeat(42) + "w");
                    case "an extra key" -> "{\"challenge\":\"" + good + "\",\"more\":1}";
                    case "the challenge twice" ->
                            "{\"challenge\":\"" + good + "\",\"challenge\":\"" + good + "\"}";
                    case "a challenge that is no text" -> "{\"challenge\":7}";
                    case "no object" -> "[\"" + good + "\"]";
                    case "not JSON" -> "challenge=" + good;
                    case "a body past the bound" ->
                            "{\"challenge\":\"" + good + "\"" + " ".repeat(100) + "}";
                    default -> challenge(good);
                };
        final String type =
                switch (kind) {
                    case "another content type" -> "text/plain";
                    case "no content type" -> null;
                    default -> "application/json";
                };
        final MockHttpServletResponse response = proof(body, type);
        assertThat(response.getStatus()).as("the status with %s", kind).isEqualTo(400);
        assertThat(response.getContentAsByteArray()).as("the body with %s", kind).isEmpty();
        assertThat(lines.list).as("the lines with %s", kind).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "POST /proof/",
                "POST /proof;a=b",
                "POST //proof",
                "POST /%70roof",
                "POST /PROOF",
                "POST /proof?x=1",
                "GET /proof",
                "OPTIONS /proof",
                "PUT /proof"
            })
    void onlyAPostOnTheRawPathOfTheProofIsExemptFromTheSignature(final String call)
            throws Exception {
        // Harm: a path the server maps to an operation but the exemption also matched would let an
        // unsigned call in.
        final String method = call.substring(0, call.indexOf(' '));
        final String target = call.substring(call.indexOf(' ') + 1);
        final int query = target.indexOf('?');
        final MockHttpServletRequest request =
                new MockHttpServletRequest(method, query < 0 ? target : target.substring(0, query));
        if (query >= 0) {
            request.setQueryString(target.substring(query + 1));
        }
        request.addHeader("Host", HOST);
        request.setContentType("application/json");
        request.setContent(bytes(challenge(Signing.base64(new byte[32]))));
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final Chain chain = new Chain();
        filter.doFilter(request, response, chain);
        assertRefused(response, chain, call);
    }

    private static String challenge(final String text) {
        return "{\"challenge\":\"" + text + "\"}";
    }

    private static byte[] bytes(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private MockHttpServletResponse proof(final String body, final @Nullable String type)
            throws Exception {
        final MockMvc mvc =
                MockMvcBuilders.standaloneSetup(new ProofController(token))
                        .addFilters(filter)
                        .build();
        final MockHttpServletRequestBuilder request =
                MockMvcRequestBuilders.post("/proof")
                        .content(body)
                        .with(
                                each -> {
                                    each.setLocalPort(4711);
                                    return each;
                                });
        if (type != null) {
            request.contentType(type);
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    private void assertRefused(
            final MockHttpServletResponse response, final Chain chain, final String kind) {
        assertThat(chain.bodies).as("what was handed on with %s", kind).isEmpty();
        assertThat(response.getStatus()).as("the status with %s", kind).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate"))
                .as("the challenge with %s", kind)
                .isEqualTo("porelo-planner-request-1");
        assertThat(response.getContentAsByteArray())
                .as("the body with %s, which must say nothing", kind)
                .isEmpty();
        assertThat(response.getHeader(Signing.ANSWER_HEADER))
                .as("an answer's proof with %s", kind)
                .isNull();
        assertThat(response.getHeader("Access-Control-Allow-Origin"))
                .as("a CORS header with %s", kind)
                .isNull();
        assertThat(lines.list)
                .as("the lines with %s", kind)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("A call without a valid signature was refused");
    }

    /** A request signed as the window signs it, under the token, at the filter's time. */
    private MockHttpServletRequest signed(
            final String method,
            final String target,
            final @Nullable String body,
            final byte @Nullable [] nonce) {
        final MockHttpServletRequest request = new MockHttpServletRequest(method, target);
        request.addHeader("Host", HOST);
        request.addHeader("Origin", "http://example.org");
        final byte[] content = body == null ? new byte[0] : bytes(body);
        if (body != null) {
            request.addHeader("Content-Type", JSON_TYPE);
            request.setContent(content);
        }
        request.addHeader(
                Signing.HEADER,
                Signing.header(
                        token.text(),
                        method,
                        target,
                        HOST,
                        body == null ? "" : JSON_TYPE,
                        content,
                        NOW,
                        nonce == null ? Signing.nonce() : nonce));
        return request;
    }

    /** A request whose signature was made for something other than it is. */
    private MockHttpServletRequest forged(final String kind) {
        final String target = "/projects/p/ideas";
        final byte[] body = bytes(BODY);
        final byte[] nonce = Signing.nonce();
        final String signedTarget =
                switch (kind) {
                    case "another target" -> "/projects/q/ideas";
                    default -> target;
                };
        final String type =
                switch (kind) {
                    case "another content type" -> "text/plain";
                    case "no content type signed" -> "";
                    default -> JSON_TYPE;
                };
        final long time =
                switch (kind) {
                    case "a time past the window" -> NOW - AccessFilter.WINDOW - 1;
                    case "a time ahead of the window" -> NOW + AccessFilter.WINDOW + 1;
                    default -> NOW;
                };
        final String header =
                Signing.header(
                        kind.equals("another token") ? "x" + token.text() : token.text(),
                        kind.equals("another method") ? "PUT" : "POST",
                        signedTarget,
                        kind.equals("another host") ? "127.0.0.1:4712" : HOST,
                        type,
                        kind.equals("another body") ? bytes("{\"idea\":\"another\"}") : body,
                        time,
                        nonce);
        final String unsigned = header.substring(0, header.indexOf(";s=") + 3);
        final String sent =
                switch (kind) {
                    case "an unknown label" ->
                            header.replace("porelo-planner-request-1", "porelo-planner-request-2");
                    case "a padded nonce" -> header.replace(";s=", "==;s=");
                    case "a proof's answer as the signature" ->
                            unsigned + Signing.proof(token.text(), 4711, new byte[32]);
                    case "an answer's proof as the signature" -> {
                        final String answer = Signing.answer(token.text(), nonce, 200, body);
                        yield unsigned + answer.substring(answer.indexOf(";s=") + 3);
                    }
                    default -> header;
                };
        final MockHttpServletRequest request = new MockHttpServletRequest("POST", target);
        request.addHeader("Host", HOST);
        request.addHeader("Content-Type", JSON_TYPE);
        request.setContent(body);
        if (kind.equals("another query")) {
            // Signed without the query it is sent with.
            request.setQueryString("a=1");
        }
        if (!kind.equals("no signature")) {
            request.addHeader(Signing.HEADER, sent);
        }
        if (kind.equals("a bearer beside the signature")) {
            request.addHeader("Authorization", "Bearer " + token.text());
        }
        if (kind.equals("two signatures")) {
            request.addHeader(
                    Signing.HEADER,
                    Signing.header(
                            token.text(),
                            "POST",
                            target,
                            HOST,
                            JSON_TYPE,
                            body,
                            NOW,
                            Signing.nonce()));
        }
        return request;
    }

    private static void answerBoard(final HttpServletResponse response) {
        response.setContentType("application/json");
        try {
            response.getOutputStream().write(bytes(BOARD));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The request with a body that counts the bytes read of it, and a declared length. */
    private static MockHttpServletRequest withBody(
            final MockHttpServletRequest request, final Counted body, final long length) {
        final MockHttpServletRequest copy =
                new MockHttpServletRequest("POST", request.getRequestURI()) {
                    @Override
                    public ServletInputStream getInputStream() {
                        return body;
                    }

                    @Override
                    public long getContentLengthLong() {
                        return length;
                    }
                };
        for (final String name : Collections.list(request.getHeaderNames())) {
            for (final String value : Collections.list(request.getHeaders(name))) {
                copy.addHeader(name, value);
            }
        }
        return copy;
    }

    /** A body of a given length, counting what is read of it. */
    private static final class Counted extends ServletInputStream {

        private final long length;

        private long read;

        Counted(final long length) {
            this.length = length;
        }

        @Override
        public int read() {
            if (read == length) {
                return -1;
            }
            read++;
            return 'x';
        }

        @Override
        public boolean isFinished() {
            return read == length;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(final ReadListener listener) {
            throw new UnsupportedOperationException("read blocking");
        }
    }
}
