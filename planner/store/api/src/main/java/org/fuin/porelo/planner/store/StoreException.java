package org.fuin.porelo.planner.store;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
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
                StoreException.ItemsDirectoryRefused,
                StoreException.StaleRevision,
                StoreException.Changed,
                StoreException.BasisIncomplete,
                StoreException.NotSavable,
                StoreException.InStory,
                StoreException.Busy,
                StoreException.PartlySaved,
                StoreException.LabelsNotKept {

    private static final long serialVersionUID = 1L;

    private final String subject;

    private StoreException(final String subject, final String message) {
        super(message);
        this.subject = subject;
    }

    /**
     * The kinds a store gives before it writes anything of the write refused: a write refused with
     * one of them wrote nothing, every other failure may have written part of it.
     */
    public static final java.util.Set<String> BEFORE_WRITING =
            java.util.Set.of(
                    "Changed", "BasisIncomplete", "StaleRevision", "NotSavable", "NoForgeAccess");

    /**
     * Tells whether a write's failure is a refusal given before anything was written.
     *
     * @param cause what the write threw
     * @return true only for a refusal of a kind in {@link #BEFORE_WRITING}
     */
    public static boolean refusedBeforeWriting(final Throwable cause) {
        return cause instanceof StoreException refusal
                && BEFORE_WRITING.contains(refusal.getClass().getSimpleName());
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

    /**
     * The directory a local project's stories and issues are kept in cannot be used: it is missing,
     * reached through a link or not the account's alone, or the kept choice of it cannot be read.
     * The planner never falls back to another directory meanwhile.
     */
    public static final class ItemsDirectoryRefused extends StoreException {
        private static final long serialVersionUID = 1L;

        private final @Nullable String directory;

        /**
         * Names the directory and why, the words free of any path.
         *
         * @param directory the directory refused, absolute, or null where none can be named
         * @param reason why, in fixed words
         */
        public ItemsDirectoryRefused(final @Nullable String directory, final String reason) {
            super(directory == null ? "" : directory, reason);
            this.directory = directory;
        }

        /**
         * Answers the directory refused.
         *
         * @return the directory, absolute, or null where none can be named
         */
        public @Nullable String directory() {
            return directory;
        }
    }

    /** A target changed, disappeared or no longer names one item. */
    public static final class Changed extends StoreException {
        private static final long serialVersionUID = 1L;

        private final List<String> ids;

        /** Names every changed target; an empty refusal is invalid. */
        public Changed(final List<String> ids) {
            super(first(ids), "The planning items changed: " + String.join(", ", ids));
            this.ids = List.copyOf(ids);
        }

        /** Answers the changed targets. */
        public List<String> ids() {
            return ids;
        }
    }

    /** The caller supplied no version for one or more targets. */
    public static final class BasisIncomplete extends StoreException {
        private static final long serialVersionUID = 1L;

        private final List<String> missing;

        /** Names every target whose version is missing. */
        public BasisIncomplete(final List<String> missing) {
            super(first(missing), "The edit basis lacks: " + String.join(", ", missing));
            this.missing = List.copyOf(missing);
        }

        /** Answers the targets whose versions were not supplied. */
        public List<String> missing() {
            return missing;
        }
    }

    private static String first(final List<String> ids) {
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("A refusal must name an item");
        }
        return ids.getFirst();
    }

    /** The store moved since the revision a save was checked against. */
    public static final class StaleRevision extends StoreException {
        private static final long serialVersionUID = 1L;

        private final String current;

        private final @Nullable String changed;

        /**
         * Names the revision the store holds now.
         *
         * @param current the current revision
         */
        public StaleRevision(final String current) {
            super(current, "The planning changed since it was shown");
            this.current = current;
            this.changed = null;
        }

        /**
         * Names the revision the store holds now and the item found changed.
         *
         * @param current the current revision
         * @param changed the story or issue found changed, by its id
         */
        public StaleRevision(final String current, final String changed) {
            super(current, "The planning changed since it was shown: " + changed + " changed");
            this.current = current;
            this.changed = changed;
        }

        /**
         * Answers the item found changed.
         *
         * @return its id, or null when the revision alone differed
         */
        public @Nullable String changed() {
            return changed;
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

        private final String field;

        /**
         * Names the item and why.
         *
         * @param item the story's or the issue's id
         * @param reason why it cannot be written
         */
        public NotSavable(final String item, final String reason) {
            this(item, reason, "");
        }

        /**
         * Names the item, why, and the field of the request whose value the store cannot keep.
         *
         * @param item the story's or the issue's id, or empty for a new one
         * @param reason why it cannot be written
         * @param field the request's field, such as {@code mustBeTrue}, or empty when it is about
         *     none
         */
        public NotSavable(final String item, final String reason, final String field) {
            super(item, reason);
            this.field = field;
        }

        /**
         * The field of the request whose value the store cannot keep.
         *
         * @return its name, or empty when the refusal is about none
         */
        public String field() {
            return field;
        }
    }

    /** An issue a new story would take that another story holds already. */
    public static final class InStory extends StoreException {
        private static final long serialVersionUID = 1L;

        private final String story;

        private final String field;

        /**
         * Names the issue, the story holding it and the field of the request that named it.
         *
         * @param issue the issue's id
         * @param story the story's id on the board, its tracker reference, or empty when neither is
         *     known
         * @param field the field of the request that named the issue, such as {@code existing[0]}
         */
        public InStory(final String issue, final String story, final String field) {
            super(
                    issue,
                    "The issue "
                            + issue
                            + (story.isEmpty() ? " has a story already" : " belongs to " + story)
                            + "; an issue has one story.");
            this.story = story;
            this.field = field;
        }

        /**
         * Answers the story holding the issue.
         *
         * @return its id or reference, or empty when neither is known
         */
        public String story() {
            return story;
        }

        /**
         * Answers the field of the request that named the issue.
         *
         * @return the field
         */
        public String field() {
            return field;
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

    /**
     * The tracker, read back after a write of an item's labels, does not carry the topic labels
     * asked, as GitHub drops them without an error for an account that may not set them; what else
     * the write saved stands.
     */
    public static final class LabelsNotKept extends StoreException {
        private static final long serialVersionUID = 1L;

        private final String item;

        /**
         * Names the repository and the item.
         *
         * @param repository the item's repository
         * @param item the story's or the issue's id
         */
        public LabelsNotKept(final String repository, final String item) {
            super(repository, "The tracker did not keep the labels asked.");
            this.item = item;
        }

        /**
         * Answers the item whose labels were not kept.
         *
         * @return the story's or the issue's id
         */
        public String item() {
            return item;
        }
    }
}
