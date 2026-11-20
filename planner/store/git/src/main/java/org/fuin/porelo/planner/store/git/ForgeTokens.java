package org.fuin.porelo.planner.store.git;

import java.net.URI;
import java.util.Map;
import org.fuin.porelo.planner.store.StoreException;

/**
 * The token a forge's API takes, asked of Porelo's credential helper as Git asks it, so a store
 * that calls a forge's API gets the same login Git gets without knowing Git or the helper.
 */
public final class ForgeTokens {

    private final LentLogin login;

    private ForgeTokens(final LentLogin login) {
        this.login = login;
    }

    /**
     * The tokens Porelo lends through the executable {@code PORELO_EXECUTABLE} names, once the
     * platform's own check passes it.
     *
     * @param environment the planner's environment
     * @return the tokens; each refused when the variable is not set or the executable is refused
     */
    public static ForgeTokens of(final Map<String, String> environment) {
        return new ForgeTokens(LentLogin.of(environment));
    }

    /**
     * Asks Porelo's helper for the token of an https address.
     *
     * @param origin the https address the token is for, without a login, query or fragment
     * @param name the repository's name a refusal reports
     * @return the token, which the caller keeps no longer than its call
     * @throws StoreException.CredentialRefused when no token can be had, naming the repository and
     *     never the token
     */
    public String token(final URI origin, final String name) {
        return login.token(origin.toString(), name, LentLogin.LIMIT);
    }
}
