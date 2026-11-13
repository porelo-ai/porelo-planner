package org.fuin.porelo.planner.app;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.fuin.porelo.planner.app.answers.Proof;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proves to a caller that the planner listening on this port holds the token, before the caller
 * sends it anything of the person's: an HMAC under the token over the port and the caller's
 * challenge, which the access filter read and checked.
 */
@RestController
public class ProofController {

    private final AccessToken token;

    /**
     * Proves under the planner's token.
     *
     * @param token The token.
     */
    public ProofController(final AccessToken token) {
        this.token = token;
    }

    /**
     * Answers the proof for the challenge the access filter let through.
     *
     * @param request The request, its challenge set by the access filter.
     * @param response The answer, set to 400 with an empty body for a request the filter let
     *     through on another path, which carries no challenge.
     * @return The proof, or {@code null} without a challenge.
     */
    @PostMapping(path = AccessFilter.PROOF_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public @Nullable Proof proof(
            final HttpServletRequest request, final HttpServletResponse response) {
        final AccessFilter.Challenge challenge = AccessFilter.challenge(request);
        if (challenge == null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return null;
        }
        return new Proof(
                CallSignature.base64(
                        CallSignature.proof(token, request.getLocalPort(), challenge.bytes())));
    }
}
