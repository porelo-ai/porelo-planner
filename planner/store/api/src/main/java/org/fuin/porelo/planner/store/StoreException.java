package org.fuin.porelo.planner.store;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Why the store could not answer; each kind is its own subclass, so the window can say what to do.
 */
public abstract sealed class StoreException extends RuntimeException
        permits StoreException.NoSuchStory,
                StoreException.NoSuchIssue,
                StoreException.WorkflowMissing,
                StoreException.CredentialRefused,
                StoreException.NoForgeAccess,
                StoreException.Unreachable,
                StoreException.Unreadable,
                StoreException.StaleRevision,
                StoreException.NotSavable,
                StoreException.Busy,
                StoreException.PartlySaved {

    private static final long serialVersionUID = 1L;

    private final String subject;

    private StoreException(final String subject, final String message) {
        super(message);
        this.subject = subject;
    }

    /**
     * Answers what the refusal is about.
     *
     * @return the story's or the issue's id, or the repository's name
     */
    public String subject() {
        return subject;
    }

    /** The board has no story of that id. */
    public static final class NoSuchStory extends StoreException {
        private static final long serialVersionUID = 1L;

        /**
         * Names the story.
         *
         * @param id the id asked for
         */
        public NoSuchStory(final String id) {
            super(Ids.story(id), "No story " + id);
        }
    }

    /** The board has no issue of that id. */
    public static final class NoSuchIssue extends StoreException {
        private static final long serialVersionUID = 1L;

        /**
         * Names the issue.
         *
         * @param id the id asked for
         */
        public NoSuchIssue(final String id) {
            super(Ids.issue(id), "No issue " + id);
        }
    }

    /** The project's repository has no workflow. */
    public static final class WorkflowMissing extends StoreException {
        private static final long serialVersionUID = 1L;

        /**
         * Names the repository.
         *
         * @param repository the name of the project's repository
         */
        public WorkflowMissing(final String repository) {
            super(
                    Ids.name(repository),
                    "Repository " + repository + " has no stories/workflow.yml");
        }
    }

    /** A repository refused the person's Git login, or would have asked for one. */
    public static final class CredentialRefused extends StoreException {
        private static final long serialVersionUID = 1L;

        /**
         * Names the repository, never the credential.
         *
         * @param repository the name of the repository that needs a login
         */
        public CredentialRefused(final String repository) {
            super(
                    Ids.name(repository),
                    "Repository " + repository + " needs a Git login that works without a prompt");
        }
    }

    /**
     * The forge's API cannot be used with the person's access: the vault lends no token for its
     * address, or the forge refuses the token for a right it lacks.
     */
    public static final class NoForgeAccess extends StoreException {
        private static final long serialVersionUID = 1L;

        private static final Pattern RIGHT = Pattern.compile("[A-Za-z][A-Za-z0-9 _-]{0,63}");

        private final @Nullable String right;

        /**
         * Names the forge's address and the right it lacks, never the token; the words are fixed.
         *
         * @param address the forge's origin, {@code https} with a host and nothing else
         * @param right the right the forge names as missing, or null when it names none or no token
         *     is lent
         * @throws IllegalArgumentException if the address is no such origin or the right is not a
         *     plain name
         */
        public NoForgeAccess(final String address, final @Nullable String right) {
            super(
                    origin(address),
                    right == null
                            ? "The person's access to " + address + " is missing or refused"
                            : "The person's access to "
                                    + address
                                    + " lacks the right "
                                    + checked(right));
            this.right = right;
        }

        /**
         * Answers the right the forge names as missing.
         *
         * @return the right, or null
         */
        public @Nullable String right() {
            return right;
        }

        private static String origin(final String address) {
            final URI uri;
            try {
                uri = new URI(address);
            } catch (final URISyntaxException unparsable) {
                // Not kept as the cause: its message repeats the address, which may hold a
                // credential.
                throw new IllegalArgumentException("A forge's address is an https origin");
            }
            TokenSource.checkedOrigin(uri);
            return address;
        }

        private static String checked(final String right) {
            if (!RIGHT.matcher(right).matches()) {
                throw new IllegalArgumentException("A forge's right is a plain name");
            }
            return right;
        }
    }

    /** A repository could not be reached in time. */
    public static final class Unreachable extends StoreException {
        private static final long serialVersionUID = 1L;

        /**
         * Names the repository and why, the reason already free of any secret.
         *
         * @param repository the name of the repository
         * @param reason why it could not be reached
         */
        public Unreachable(final String repository, final String reason) {
            super(
                    Ids.name(repository),
                    "Repository " + repository + " could not be reached: " + reason);
        }
    }

    /** A repository was reached, but what it holds could not be read as a store. */
    public static final class Unreadable extends StoreException {
        private static final long serialVersionUID = 1L;

        /**
         * Names the repository and the fault, the fault already free of any secret.
         *
         * @param repository the name of the repository
         * @param fault what could not be read
         */
        public Unreadable(final String repository, final String fault) {
            super(
                    Ids.name(repository),
                    "Repository " + repository + " could not be read: " + fault);
        }
    }

    /** The store moved since the revision a save was checked against. */
    public static final class StaleRevision extends StoreException {
        private static final long serialVersionUID = 1L;

        private final String current;

        /**
         * Names the revision the store holds now.
         *
         * @param current the current revision
         */
        public StaleRevision(final String current) {
            super(current, "The planning changed since it was shown");
            this.current = current;
        }

        /**
         * Answers the revision the store holds now.
         *
         * @return the current revision
         */
        public String current() {
            return current;
        }
    }

    /** An item that cannot be written in its repository. */
    public static final class NotSavable extends StoreException {
        private static final long serialVersionUID = 1L;

        /**
         * Names the item and why.
         *
         * @param item the story's or the issue's id
         * @param reason why it cannot be written
         */
        public NotSavable(final String item, final String reason) {
            super(item, reason);
        }
    }

    /** Another save or a check held the project longer than a save waits. */
    public static final class Busy extends StoreException {
        private static final long serialVersionUID = 1L;

        /**
         * Names the project's own repository.
         *
         * @param repository the repository's name
         */
        public Busy(final String repository) {
            super(repository, "The project is busy with another save or check");
        }
    }

    /** A save whose later repository failed after earlier ones were pushed. */
    public static final class PartlySaved extends StoreException {
        private static final long serialVersionUID = 1L;

        private final java.util.List<String> pushed;

        /**
         * Names what was pushed and what failed.
         *
         * @param failed the repository that failed
         * @param pushed the repositories already pushed, in order
         * @param reason why it failed
         */
        public PartlySaved(
                final String failed, final java.util.List<String> pushed, final String reason) {
            super(failed, reason);
            this.pushed = java.util.List.copyOf(pushed);
        }

        /**
         * Answers the repositories already pushed.
         *
         * @return their names, in order
         */
        public java.util.List<String> pushed() {
            return pushed;
        }
    }
}
