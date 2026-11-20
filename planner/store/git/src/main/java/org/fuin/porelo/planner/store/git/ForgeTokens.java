package org.fuin.porelo.planner.store.git;

import java.net.URI;
import java.util.Locale;
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
     * @param name the repository's name a refusal of the address itself reports
     * @return the token, which the caller keeps no longer than its call
     * @throws StoreException.NoForgeAccess when no token can be had for a plain https address,
     *     naming its origin and never the token, so the window can offer to hand one on
     * @throws StoreException.CredentialRefused when the address is not a plain https one, naming
     *     the repository
     */
    public String token(final URI origin, final String name) {
        try {
            return login.token(origin.toString(), name, LentLogin.LIMIT);
        } catch (final StoreException.CredentialRefused refused) {
            throw missing(origin, refused);
        }
    }

    /** The forge's access as missing, or the refusal itself for an address that is no origin. */
    private static StoreException missing(
            final URI address, final StoreException.CredentialRefused refused) {
        final String host = address.getHost();
        if (!"https".equals(address.getScheme())
                || host == null
                || address.getRawUserInfo() != null
                || address.getPort() != -1) {
            return refused;
        }
        try {
            return new StoreException.NoForgeAccess(
                    "https://" + host.toLowerCase(Locale.ROOT), null);
        } catch (final IllegalArgumentException noOrigin) {
            return refused;
        }
    }
}
