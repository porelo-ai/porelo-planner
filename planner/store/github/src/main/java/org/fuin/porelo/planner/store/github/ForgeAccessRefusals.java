package org.fuin.porelo.planner.store.github;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.fuin.porelo.forge.github.GitHubException;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.TokenSource;
import org.jspecify.annotations.Nullable;

/**
 * Turns GitHub's refusal of the person's token into {@link StoreException.NoForgeAccess}, naming
 * the forge and the right GitHub says the call needs, so the window can offer to hand a token on; a
 * refusal after a part of a save was written is {@link StoreException.PartlySaved} instead, and
 * every other failure is answered as {@link AccessRefusals#DEFAULT} answers it.
 */
public final class ForgeAccessRefusals implements AccessRefusals {

    /** A permission's name as GitHub writes it, such as {@code organization_projects}. */
    private static final Pattern PERMISSION = Pattern.compile("[a-z][a-z0-9_]{0,62}");

    private final String forge;

    /**
     * Makes the refusals of one forge.
     *
     * @param forge the forge's origin
     * @throws IllegalArgumentException if it is not an https origin without user, port, path, query
     *     or fragment
     */
    public ForgeAccessRefusals(final URI forge) {
        this.forge = TokenSource.checkedOrigin(forge).toString();
    }

    @Override
    public StoreException refusal(
            final String repository, final RuntimeException failure, final String token) {
        if (failure instanceof BoardWriter.AfterWriting after) {
            final RuntimeException cause = after.failure();
            if (cause instanceof StoreException.PartlySaved partly) {
                return partly;
            }
            return new StoreException.PartlySaved(
                    repository,
                    List.of(),
                    accessRefused(cause)
                            ? "GitHub refused the person's access after a part of the save was"
                                    + " written"
                            : "A part of the save was written before: "
                                    + Scrubbed.text(
                                            String.valueOf(cause.getMessage()), List.of(token)));
        }
        if (failure instanceof GitHubException refused && accessRefused(refused)) {
            return new StoreException.NoForgeAccess(forge, right(refused.acceptedPermissions()));
        }
        return DEFAULT.refusal(repository, failure, token);
    }

    /** A 401 or 403 that is no rate limit: GitHub asks for another token, not for a wait. */
    private static boolean accessRefused(final RuntimeException failure) {
        return failure instanceof GitHubException refused
                && (refused.status() == 401 || refused.status() == 403)
                && !refused.rateLimited();
    }

    /**
     * The first permission GitHub names as accepted, in words such as {@code Organization
     * projects}, or null when it names none or one that is no plain name.
     */
    static @Nullable String right(final @Nullable String accepted) {
        if (accepted == null) {
            return null;
        }
        final String first = accepted.split("[,;]", 2)[0];
        final int equals = first.indexOf('=');
        final String name = (equals < 0 ? first : first.substring(0, equals)).strip();
        if (!PERMISSION.matcher(name).matches()) {
            return null;
        }
        final String words = name.replace('_', ' ');
        return words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
    }
}
