package org.fuin.porelo.planner.store;

import java.net.URI;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * An open issue of one of the project's repositories that is no item of the project, as one made by
 * hand on the forge is; it is not on the board.
 *
 * @param id its tracker reference
 * @param repository the name of the repository that holds it
 * @param title its title, as the forge holds it
 * @param kind whether the forge marks it as a story or as an issue
 * @param link where a person reads it on the forge
 * @param author the login of the account that opened it, or null when the forge named none of a
 *     login's form
 * @param authorAssociation how that account stands to the repository, as the forge names it, or
 *     null when it named none the planner knows
 */
public record UnlistedIssue(
        String id,
        String repository,
        String title,
        Kind kind,
        URI link,
        @Nullable String author,
        @Nullable String authorAssociation) {

    /**
     * A login as a forge gives it: letters, digits and single hyphens inside, at most 39, and an
     * app's account ending in {@code [bot]}.
     */
    private static final Pattern LOGIN =
            Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}(?:\\[bot])?");

    /** The ways an author can stand to a repository, as GitHub names them. */
    public static final Set<String> ASSOCIATIONS =
            Set.of(
                    "OWNER",
                    "MEMBER",
                    "COLLABORATOR",
                    "CONTRIBUTOR",
                    "FIRST_TIME_CONTRIBUTOR",
                    "FIRST_TIMER",
                    "MANNEQUIN",
                    "NONE");

    /** What an issue made outside the project stands for. */
    public enum Kind {

        /** An issue of work. */
        ISSUE,

        /** A story, as the forge's issue type says. */
        STORY
    }

    /** Requires a tracker reference within its own repository, and every part. */
    public UnlistedIssue {
        Ids.name(repository);
        // Only a reference names an issue a later add can find again on the forge.
        if (!Ids.isReference(id) || !Ids.repositoryOf(id).equals(repository)) {
            throw new IllegalArgumentException(
                    "An issue outside the project is named by no reference into " + repository);
        }
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(link, "link");
        if (author != null && !isLogin(author)) {
            throw new IllegalArgumentException("An author is named by a login");
        }
        if (authorAssociation != null && !ASSOCIATIONS.contains(authorAssociation)) {
            throw new IllegalArgumentException("An author's association is none the forge names");
        }
    }

    /**
     * An issue whose author is not known.
     *
     * @param id its tracker reference
     * @param repository the name of the repository that holds it
     * @param title its title, as the forge holds it
     * @param kind whether the forge marks it as a story or as an issue
     * @param link where a person reads it on the forge
     */
    public UnlistedIssue(
            final String id,
            final String repository,
            final String title,
            final Kind kind,
            final URI link) {
        this(id, repository, title, kind, link, null, null);
    }

    /**
     * Whether a text has a login's form.
     *
     * @param login the text
     * @return true for a login a forge can give
     */
    public static boolean isLogin(final String login) {
        return LOGIN.matcher(login).matches();
    }
}
