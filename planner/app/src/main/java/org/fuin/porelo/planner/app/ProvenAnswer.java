package org.fuin.porelo.planner.app;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * An answer to a signed request that proves itself: its body is held until the call ends, then sent
 * with an HMAC under the token over the request's nonce, the status and the body; the opening of an
 * event stream carries one over the status and the nonce and its events go out at once.
 */
final class ProvenAnswer extends HttpServletResponseWrapper {

    private static final String EVENT_STREAM = "text/event-stream";

    private final AccessToken token;
    private final byte[] nonce;
    private final ByteArrayOutputStream held = new ByteArrayOutputStream();
    private @Nullable ServletOutputStream stream;
    private @Nullable PrintWriter writer;
    private boolean streaming;
    private boolean errorSent;
    private boolean finished;

    /**
     * Wraps an answer.
     *
     * @param response The answer.
     * @param token The planner's token.
     * @param nonce The verified request's nonce.
     */
    ProvenAnswer(final HttpServletResponse response, final AccessToken token, final byte[] nonce) {
        super(response);
        this.token = token;
        this.nonce = nonce.clone();
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (writer != null) {
            throw new IllegalStateException("getWriter() was called before");
        }
        if (stream == null) {
            stream = startsStream() ? super.getOutputStream() : new Held();
        }
        return stream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (stream != null) {
            throw new IllegalStateException("getOutputStream() was called before");
        }
        if (writer == null) {
            writer =
                    startsStream()
                            ? super.getWriter()
                            : new PrintWriter(
                                    new OutputStreamWriter(
                                            held, Charset.forName(getCharacterEncoding())));
        }
        return writer;
    }

    @Override
    public void flushBuffer() throws IOException {
        if (streaming) {
            super.flushBuffer();
        }
    }

    @Override
    public boolean isCommitted() {
        return streaming || errorSent ? super.isCommitted() : finished;
    }

    @Override
    public void resetBuffer() {
        held.reset();
        super.resetBuffer();
    }

    @Override
    public void reset() {
        held.reset();
        super.reset();
    }

    @Override
    public void sendError(final int status) throws IOException {
        errorSent = true;
        super.sendError(status);
    }

    @Override
    public void sendError(final int status, final String message) throws IOException {
        errorSent = true;
        super.sendError(status, message);
    }

    /**
     * Sends the held body with its proof; nothing for a stream, whose opening carried its proof, or
     * for an error whose answer the error dispatch writes and proves.
     *
     * @throws IOException When the answer cannot be written.
     */
    void finish() throws IOException {
        if (finished || streaming || errorSent) {
            return;
        }
        finished = true;
        if (writer != null) {
            writer.flush();
        }
        final byte[] body = held.toByteArray();
        final HttpServletResponse response = (HttpServletResponse) getResponse();
        response.setHeader(
                CallSignature.ANSWER_HEADER,
                CallSignature.answerHeader(
                        CallSignature.answer(token, nonce, response.getStatus(), body)));
        response.setContentLength(body.length);
        if (body.length > 0) {
            response.getOutputStream().write(body);
        }
        response.flushBuffer();
    }

    /** Proves the opening of an event stream before its first byte, which commits it. */
    private boolean startsStream() {
        final String type = getContentType();
        if (type == null
                || !type.toLowerCase(Locale.ROOT).startsWith(EVENT_STREAM)
                || isCommitted()) {
            return false;
        }
        streaming = true;
        setHeader(
                CallSignature.ANSWER_HEADER,
                CallSignature.answerHeader(CallSignature.answer(token, nonce, getStatus(), null)));
        return true;
    }

    /** The body, held until the call ends. */
    private final class Held extends ServletOutputStream {

        @Override
        public void write(final int b) {
            held.write(b);
        }

        @Override
        public void write(final byte[] bytes, final int offset, final int length) {
            held.write(bytes, offset, length);
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(final WriteListener listener) {
            throw new UnsupportedOperationException("the planner writes its answers blocking");
        }
    }
}
