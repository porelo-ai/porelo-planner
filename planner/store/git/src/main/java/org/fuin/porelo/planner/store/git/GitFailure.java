package org.fuin.porelo.planner.store.git;

/** A {@code git} call that failed, with a message already free of secrets. */
public final class GitFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why it failed, as far as the planner must tell it apart. */
    public enum Kind {
        /** The login was missing or refused, or would have needed a prompt. */
        LOGIN,
        /** The remote gave no answer in time. */
        UNREACHABLE,
        /** Any other failure. */
        FAILED,
        /** The remote refused a push because its branch is not where the push expected it. */
        REJECTED
    }

    private final Kind kind;

    /**
     * Makes a failure.
     *
     * @param kind why it failed
     * @param message what happened, free of secrets
     */
    public GitFailure(final Kind kind, final String message) {
        super(message);
        this.kind = kind;
    }

    /**
     * Says why it failed.
     *
     * @return the kind
     */
    public Kind kind() {
        return kind;
    }
}
