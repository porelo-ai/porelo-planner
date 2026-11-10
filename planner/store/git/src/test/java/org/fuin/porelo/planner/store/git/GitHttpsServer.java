package org.fuin.porelo.planner.store.git;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.jspecify.annotations.Nullable;

/**
 * Serves the bare repositories of one directory over HTTPS through {@code git http-backend}, with a
 * certificate made for this run alone, and asks for a login when one is set.
 */
public final class GitHttpsServer implements AutoCloseable {

    private static final String PASS = "test-keystore-of-this-run";

    private final HttpsServer server;

    private final Path caFile;

    private GitHttpsServer(final HttpsServer server, final Path caFile) {
        this.server = server;
        this.caFile = caFile;
    }

    /**
     * Starts a server.
     *
     * @param root the directory of bare repositories
     * @param work a directory of the test's own for the key
     * @param login {@code user:password} it asks for, or {@code null} for none
     * @return the server
     * @throws Exception when it cannot start
     */
    public static GitHttpsServer start(
            final Path root, final Path work, final @Nullable String login) throws Exception {
        final Path keystore = work.resolve("server.p12");
        final Path ca = work.resolve("ca.pem");
        final String keytool =
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        run(
                List.of(
                        keytool,
                        "-genkeypair",
                        "-alias",
                        "server",
                        "-keyalg",
                        "EC",
                        "-groupname",
                        "secp256r1",
                        "-dname",
                        "CN=localhost",
                        "-ext",
                        "SAN=dns:localhost",
                        "-validity",
                        "2",
                        "-keystore",
                        keystore.toString(),
                        "-storetype",
                        "PKCS12",
                        "-storepass:env",
                        "STOREPASS",
                        "-keypass:env",
                        "STOREPASS"));
        run(
                List.of(
                        keytool,
                        "-exportcert",
                        "-rfc",
                        "-alias",
                        "server",
                        "-file",
                        ca.toString(),
                        "-keystore",
                        keystore.toString(),
                        "-storepass:env",
                        "STOREPASS"));
        final KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, PASS.toCharArray());
        }
        final KeyManagerFactory keys =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, PASS.toCharArray());
        final SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(keys.getKeyManagers(), null, null);
        final HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext("/", exchange -> serve(exchange, root, login));
        server.start();
        return new GitHttpsServer(server, ca);
    }

    /**
     * Names a repository's clone URL.
     *
     * @param name the bare repository's directory name
     * @return its URL
     */
    public String url(final String name) {
        return "https://localhost:" + server.getAddress().getPort() + "/" + name;
    }

    /**
     * Names the certificate a client must trust.
     *
     * @return the PEM file
     */
    public Path ca() {
        return caFile;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static void serve(
            final HttpExchange exchange, final Path root, final @Nullable String login)
            throws IOException {
        try (exchange) {
            if (login != null) {
                final String expected =
                        "Basic "
                                + Base64.getEncoder()
                                        .encodeToString(login.getBytes(StandardCharsets.UTF_8));
                if (!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"git\"");
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }
            }
            final ProcessBuilder cgi = new ProcessBuilder("git", "http-backend");
            final Map<String, String> env = cgi.environment();
            env.clear();
            env.put("PATH", "/usr/bin:/bin");
            env.put("GIT_CONFIG_GLOBAL", "/dev/null");
            env.put("GIT_CONFIG_NOSYSTEM", "1");
            env.put("GIT_PROJECT_ROOT", root.toString());
            env.put("GIT_HTTP_EXPORT_ALL", "1");
            // A save pushes; a forge takes a push from a signed-in person alike.
            env.put("GIT_CONFIG_COUNT", "1");
            env.put("GIT_CONFIG_KEY_0", "http.receivepack");
            env.put("GIT_CONFIG_VALUE_0", "true");
            env.put("REQUEST_METHOD", exchange.getRequestMethod());
            env.put("PATH_INFO", exchange.getRequestURI().getPath());
            final String query = exchange.getRequestURI().getRawQuery();
            env.put("QUERY_STRING", query == null ? "" : query);
            final String type = exchange.getRequestHeaders().getFirst("Content-Type");
            env.put("CONTENT_TYPE", type == null ? "" : type);
            final String encoding = exchange.getRequestHeaders().getFirst("Content-Encoding");
            if (encoding != null) {
                env.put("HTTP_CONTENT_ENCODING", encoding);
            }
            final String protocol = exchange.getRequestHeaders().getFirst("Git-Protocol");
            if (protocol != null) {
                env.put("GIT_PROTOCOL", protocol);
            }
            env.put("REMOTE_ADDR", "127.0.0.1");
            final Process process = cgi.start();
            try (OutputStream in = process.getOutputStream()) {
                exchange.getRequestBody().transferTo(in);
            }
            final byte[] output = process.getInputStream().readAllBytes();
            int split = indexOf(output, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            int skip = 4;
            if (split < 0) {
                split = indexOf(output, "\n\n".getBytes(StandardCharsets.US_ASCII));
                skip = 2;
            }
            int status = 200;
            for (final String line :
                    new String(output, 0, split, StandardCharsets.US_ASCII).split("\r?\n")) {
                final int colon = line.indexOf(':');
                final String name = line.substring(0, colon).trim();
                final String value = line.substring(colon + 1).trim();
                if (name.equalsIgnoreCase("Status")) {
                    status = Integer.parseInt(value.substring(0, 3));
                } else {
                    exchange.getResponseHeaders().add(name, value);
                }
            }
            final int start = split + skip;
            final int length = output.length - start;
            exchange.sendResponseHeaders(status, length == 0 ? -1 : length);
            if (length > 0) {
                exchange.getResponseBody().write(output, start, length);
            }
        }
    }

    private static int indexOf(final byte[] data, final byte[] part) {
        outer:
        for (int i = 0; i + part.length <= data.length; i++) {
            for (int j = 0; j < part.length; j++) {
                if (data[i + j] != part[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static void run(final List<String> command) throws IOException, InterruptedException {
        final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("STOREPASS", PASS);
        final Process process = builder.start();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        process.getInputStream().transferTo(out);
        if (process.waitFor() != 0) {
            throw new IOException(
                    command.get(1) + " failed: " + out.toString(StandardCharsets.UTF_8));
        }
    }
}
