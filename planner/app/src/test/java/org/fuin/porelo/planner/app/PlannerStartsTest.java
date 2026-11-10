package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlannerStartsTest {

    @LocalServerPort private int port;

    @Test
    void thePlannerStartsAndSaysItIsUpOnTheLoopbackAddress() throws Exception {
        // A planner that does not start leaves the window without a board.
        final HttpResponse<String> answer =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(
                                                URI.create("http://127.0.0.1:" + port + "/health"))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        assertThat(answer.statusCode()).as("the health call's status").isEqualTo(200);
        assertThat(answer.body()).as("the health call's answer").contains("\"status\":\"UP\"");
    }

    @Test
    void thePlannerCannotBeReachedOnAnotherAddressOfThisComputer() throws Exception {
        // A planner on every address could be reached from another computer on the network.
        final InetAddress other = anotherAddress();
        assertThatThrownBy(
                        () -> {
                            try (Socket socket = new Socket()) {
                                socket.connect(new InetSocketAddress(other, port), 1000);
                            }
                        })
                .as("a connection to %s:%d", other.getHostAddress(), port)
                .isInstanceOf(IOException.class);
    }

    /** An address of this computer other than the loopback one; the test fails without one. */
    private static InetAddress anotherAddress() throws SocketException {
        for (final NetworkInterface face :
                Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (face.isUp() && !face.isLoopback()) {
                for (final InetAddress address : Collections.list(face.getInetAddresses())) {
                    if (address instanceof Inet4Address) {
                        return address;
                    }
                }
            }
        }
        throw new IllegalStateException(
                "this computer has no IPv4 address but the loopback one to try the planner on");
    }
}
