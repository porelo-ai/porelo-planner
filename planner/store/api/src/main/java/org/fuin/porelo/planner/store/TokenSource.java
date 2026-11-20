package org.fuin.porelo.planner.store;

import java.net.URI;
import java.util.Locale;

/** Gives the person's token for a forge, asked for every call and kept by nobody here. */
@FunctionalInterface
public interface TokenSource {

    /**
     * Answers the person's token for the forge.
     *
     * @param origin the forge's origin, checked by {@link #checkedOrigin(URI)}
     * @return the token, never blank
     * @throws org.fuin.porelo.planner.store.StoreException.CredentialRefused if no token is lent
     *     for it
     */
    String token(URI origin);

    /**
     * Refuses any address but an {@code https} origin without user, port, path, query or fragment,
     * so a token is never asked for, or sent to, an address a changed file slipped in.
     *
     * @param origin the address
     * @return the same address
     * @throws IllegalArgumentException if it is not such an origin
     */
    static URI checkedOrigin(final URI origin) {
        final String host = origin.getHost();
        final boolean plain =
                "https".equals(origin.getScheme())
                        && host != null
                        && host.equals(host.toLowerCase(Locale.ROOT))
                        && origin.getRawUserInfo() == null
                        && origin.getPort() == -1
                        && (origin.getRawPath() == null || origin.getRawPath().isEmpty())
                        && origin.getRawQuery() == null
                        && origin.getRawFragment() == null
                        && origin.toString().equals("https://" + host);
        if (!plain) {
            throw new IllegalArgumentException(
                    "A forge is an https origin without user, port, path, query or fragment");
        }
        return origin;
    }
}
