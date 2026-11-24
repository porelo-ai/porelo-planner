package org.fuin.porelo.planner.app;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.fuin.porelo.planner.store.CallTimes;

/**
 * The forge's HTTP client, noting each blocking call's duration and status in the call it serves,
 * named by the forge client's method that made it; neither its address nor its body is kept.
 */
final class TimedHttpClient extends HttpClient {

    /** The packages of the forge clients, whose outermost method names a call. */
    private static final String FORGE = "org.fuin.porelo.forge.";

    private final HttpClient http;

    /**
     * Wraps a client.
     *
     * @param http the client every call goes through
     */
    TimedHttpClient(final HttpClient http) {
        this.http = http;
    }

    @Override
    public <T> HttpResponse<T> send(
            final HttpRequest request, final HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        final long start = System.nanoTime();
        int status = 0;
        try {
            final HttpResponse<T> answer = http.send(request, handler);
            status = answer.statusCode();
            return answer;
        } finally {
            CallTimes.forge(caller(), status, System.nanoTime() - start);
        }
    }

    /**
     * The forge client's method that a call from outside it entered: the last of the forge's own
     * frames above this one.
     */
    static String caller() {
        return StackWalker.getInstance()
                .walk(
                        frames -> {
                            String named = "unknown";
                            for (final StackWalker.StackFrame frame :
                                    (Iterable<StackWalker.StackFrame>) frames.skip(1)::iterator) {
                                final String type = frame.getClassName();
                                if (type.startsWith(FORGE)) {
                                    named =
                                            type.substring(type.lastIndexOf('.') + 1)
                                                    + "."
                                                    + frame.getMethodName();
                                } else if (!type.equals(TimedHttpClient.class.getName())) {
                                    break;
                                }
                            }
                            return named;
                        });
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
        return http.sendAsync(request, handler);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            final HttpRequest request,
            final HttpResponse.BodyHandler<T> handler,
            final HttpResponse.PushPromiseHandler<T> promises) {
        return http.sendAsync(request, handler, promises);
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return http.cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return http.connectTimeout();
    }

    @Override
    public Redirect followRedirects() {
        return http.followRedirects();
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return http.proxy();
    }

    @Override
    public SSLContext sslContext() {
        return http.sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
        return http.sslParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return http.authenticator();
    }

    @Override
    public Version version() {
        return http.version();
    }

    @Override
    public Optional<Executor> executor() {
        return http.executor();
    }

    @Override
    public void shutdown() {
        http.shutdown();
    }

    @Override
    public boolean awaitTermination(final Duration duration) throws InterruptedException {
        return http.awaitTermination(duration);
    }

    @Override
    public boolean isTerminated() {
        return http.isTerminated();
    }

    @Override
    public void shutdownNow() {
        http.shutdownNow();
    }

    @Override
    public void close() {
        http.close();
    }
}
