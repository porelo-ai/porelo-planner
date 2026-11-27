package org.fuin.porelo.planner.store;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A role of a project's tasks, as its work rules define it: a name, how a task of the role ends and
 * the parts of a start, under the names Porelo's {@code Start} takes them by. The planner knows no
 * role by its name and hands Porelo only the parts, never the role.
 *
 * @param name the role's name in the work rules
 * @param end how a task of the role ends
 * @param workspace the kind of the role's workspace
 * @param readLanded whether a task of the role also reads the forwarded work of the tasks whose
 *     build it reads
 * @param projectServices whether a task of the role has the project's services
 * @param writeAs the account a task's released words are written as at a pull request
 * @param rights the role's actions and judgments in one list, in Porelo's order; empty for a role
 *     holding no right
 */
public record Role(
        String name,
        End end,
        Workspace workspace,
        boolean readLanded,
        boolean projectServices,
        WriteAs writeAs,
        List<Right> rights) {

    /** Longest name of a role. */
    public static final int LONGEST = 64;

    /** How a task of a role ends, the planner's own fixed list. */
    public enum End {
        /** It pushes its work to the gate. */
        PUSHES_WORK("pushes work"),
        /** It reports findings about the work it read. */
        REPORTS_FINDINGS("reports findings"),
        /** It gives points at the place it works at; a role that may reject ends so too. */
        GIVES_POINTS("gives points"),
        /** It hands back a result file. */
        HANDS_BACK_RESULT("hands back a result");

        private final String words;

        End(final String words) {
            this.words = words;
        }

        /**
         * Answers the end kind in plain words, for a fault a person reads.
         *
         * @return the words
         */
        public String words() {
            return words;
        }
    }

    /** The kind of a workspace, in the words Porelo's task row records it by. */
    public enum Workspace {
        /** The task's own work: its branch and a gate that takes pushes. */
        OWN("own"),
        /** Another task's work, frozen: the one push or pull request the start names as place. */
        FOREIGN("foreign"),
        /** One repository's main, read only, without a gate. */
        READ_ONLY("readOnly"),
        /** Every repository's main, read only, without a gate. */
        ALL_REPOSITORIES("allRepositories"),
        /** No workspace and no gate. */
        NONE("none");

        private final String word;

        Workspace(final String word) {
            this.word = word;
        }

        /**
         * Answers Porelo's word for the kind.
         *
         * @return the word
         */
        public String word() {
            return word;
        }

        /**
         * Answers the kind Porelo's word names.
         *
         * @param word the word
         * @return the kind, or empty for a word Porelo does not have
         */
        public static Optional<Workspace> of(final String word) {
            for (final Workspace kind : values()) {
                if (kind.word.equals(word)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    /** The account a task's released words are written as at a pull request, in Porelo's words. */
    public enum WriteAs {
        /** The account that opened the pull request. */
        AUTHOR,
        /** The forge's account for reviews. */
        REVIEW;

        /**
         * Answers Porelo's word for the account.
         *
         * @return the word
         */
        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * Answers the account Porelo's word names.
         *
         * @param word the word
         * @return the account, or empty for a word Porelo does not have
         */
        public static Optional<WriteAs> of(final String word) {
            for (final WriteAs account : values()) {
                if (account.word().equals(word)) {
                    return Optional.of(account);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * What a task may do, Porelo's fixed words in Porelo's order: the actions at its place, the
     * rights of its own endpoint and the one judgment. What each allows is Porelo's to say.
     */
    public enum Right {
        /** Push work to the gate. */
        PUSH("push"),
        /** Answer in the threads at its place. */
        REPLY("reply"),
        /** Open a thread at its place. */
        OPEN_THREADS("openThreads"),
        /** Mark a thread at its place as resolved. */
        RESOLVE_THREADS("resolveThreads"),
        /** Start tasks of its own project. */
        START("start"),
        /** Stop the tasks it started. */
        STOP("stop"),
        /** Report its own end. */
        DONE("done"),
        /** Hand back a result. */
        REPORT("report"),
        /** Reject the waiting push that is its place, the only judgment. */
        REJECT("reject");

        private final String word;

        Right(final String word) {
            this.word = word;
        }

        /**
         * Answers Porelo's word for the right.
         *
         * @return the word
         */
        public String word() {
            return word;
        }

        /**
         * Answers the right Porelo's word names.
         *
         * @param word the word
         * @return the right, or empty for a word Porelo does not have
         */
        public static Optional<Right> of(final String word) {
            for (final Right right : values()) {
                if (right.word.equals(word)) {
                    return Optional.of(right);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * A rule a role breaks: one of Porelo's own, which its {@code Start} would refuse as {@code
     * StartRuleBroken} with the same word, or one the planner keeps for how a task ends.
     */
    public enum Broken {
        /** A role at another task's work holds the project's services or {@code push}. */
        FOREIGN_WORK_LEADS_OUT("foreignWorkLeadsOut"),
        /** A role at another task's work names no right at all. */
        FOREIGN_WORK_NAMES_NO_RIGHTS("foreignWorkNamesNoRights"),
        /** A role holds {@code reject} together with {@code push}. */
        REJECT_WITH_PUSH("rejectWithPush"),
        /** A role holds {@code reject} without reading another task's work. */
        REJECT_OVER_OWN_WORK("rejectOverOwnWork"),
        /** A role holds {@code push} outside its own work. */
        PUSH_WITHOUT_OWN_WORK("pushWithoutOwnWork"),
        /** A role that pushes work holds no {@code push}; the planner's rule. */
        END_NEEDS_PUSH("endNeedsPush"),
        /** A role holds {@code push} and does not end by pushing work, so nobody reads its push. */
        PUSH_NEEDS_END("pushNeedsEnd"),
        /** A role gives points and has no place to give them at: no other task's work. */
        POINTS_NEED_ANOTHERS_WORK("pointsNeedAnothersWork"),
        /** A role holds {@code reject} and does not end by giving points. */
        REJECT_NEEDS_POINTS("rejectNeedsPoints"),
        /**
         * A role reports findings at another task's work and cannot open a thread there, so nothing
         * it finds stands where the work waits.
         */
        FINDINGS_NEED_THREADS("findingsNeedThreads"),
        /** A role at another task's work neither reports findings nor gives points about it. */
        FOREIGN_WORK_NEEDS_READING("foreignWorkNeedsReading"),
        /** A role says its findings in its answer and reads no landed work to find them in. */
        FINDINGS_NEED_LANDED_WORK("findingsNeedLandedWork"),
        /** A role reads landed work and has no workspace to read it in. */
        READ_LANDED_NEEDS_WORKSPACE("readLandedNeedsWorkspace"),
        /** A role gives points and can neither open a thread nor reject. */
        POINTS_NEED_THREADS("pointsNeedThreads"),
        /** A role that reports findings or hands back a result holds no {@code report}. */
        END_NEEDS_REPORT("endNeedsReport");

        private final String word;

        Broken(final String word) {
            this.word = word;
        }

        /**
         * Answers the rule's word, Porelo's own where the rule is Porelo's.
         *
         * @return the word
         */
        public String word() {
            return word;
        }
    }

    /**
     * Copies the rights into Porelo's order, each once, and refuses a name that is blank, longer
     * than {@link #LONGEST} characters or holds a control character or a format character, such as
     * one that changes the direction of text.
     */
    public Role {
        if (name.isBlank()
                || name.codePointCount(0, name.length()) > LONGEST
                || name.codePoints()
                        .anyMatch(
                                c ->
                                        Character.isISOControl(c)
                                                || c == 0x85
                                                || c == 0x2028
                                                || c == 0x2029
                                                // A direction or invisible format character
                                                // would turn or hide the text shown around it.
                                                || Character.getType(c) == Character.FORMAT)) {
            throw new IllegalArgumentException(
                    "A role's name is blank, longer than "
                            + LONGEST
                            + " characters or holds a control or format character");
        }
        rights = ordered(rights);
    }

    private static List<Right> ordered(final Collection<Right> rights) {
        return rights.isEmpty()
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(EnumSet.copyOf(rights)));
    }

    /**
     * Whether the role holds a right.
     *
     * @param right the right
     * @return whether it is among the role's rights
     */
    public boolean holds(final Right right) {
        return rights.contains(right);
    }

    /**
     * Whether a task of the role ends by pushing its work.
     *
     * @return whether it pushes work
     */
    public boolean pushesWork() {
        return end == End.PUSHES_WORK;
    }

    /**
     * Whether a task of the role ends by reporting findings.
     *
     * @return whether it reports findings
     */
    public boolean reportsFindings() {
        return end == End.REPORTS_FINDINGS;
    }

    /**
     * Whether a task of the role ends by giving points at its place.
     *
     * @return whether it gives points
     */
    public boolean givesPoints() {
        return end == End.GIVES_POINTS;
    }

    /**
     * Whether a task of the role ends by handing back a result.
     *
     * @return whether it hands back a result
     */
    public boolean handsBackResult() {
        return end == End.HANDS_BACK_RESULT;
    }

    /**
     * Whether a task of the role reads work and builds none: it ends by findings or by points.
     *
     * @return whether it reads
     */
    public boolean reads() {
        return reportsFindings() || givesPoints();
    }

    /**
     * Whether a task of the role works at another task's work, which its start names as place.
     *
     * @return whether its workspace is another task's work
     */
    public boolean readsAnothersWork() {
        return workspace == Workspace.FOREIGN;
    }

    /**
     * Whether a task of the role reads a build's forwarded work and says what it found in its
     * answer; at another task's work, what a task finds stands at the place instead.
     *
     * @return whether its answer holds its findings
     */
    public boolean answersWithFindings() {
        return reportsFindings() && !readsAnothersWork();
    }

    /**
     * Whether the role holds a judgment.
     *
     * @return whether it may reject
     */
    public boolean judges() {
        return holds(Right.REJECT);
    }

    /**
     * Whether four eyes bind the role: one that reports findings, gives points or holds a judgment
     * is never bound to the task whose work it reads.
     *
     * @return whether a task of the role must be another than the one that built the work
     */
    public boolean needsOtherEyes() {
        return reportsFindings() || givesPoints() || judges();
    }

    /**
     * Answers every rule the role breaks, Porelo's own first, so a role Porelo would refuse at
     * Start is refused when the work rules load.
     *
     * @return the rules broken, empty for a sound role
     */
    public List<Broken> broken() {
        final List<Broken> broken = new ArrayList<>();
        final boolean foreign = readsAnothersWork();
        final boolean push = holds(Right.PUSH);
        if (foreign && (projectServices || push)) {
            broken.add(Broken.FOREIGN_WORK_LEADS_OUT);
        }
        if (foreign && rights.isEmpty()) {
            broken.add(Broken.FOREIGN_WORK_NAMES_NO_RIGHTS);
        }
        if (judges() && push) {
            broken.add(Broken.REJECT_WITH_PUSH);
        }
        if (judges() && !foreign) {
            broken.add(Broken.REJECT_OVER_OWN_WORK);
        }
        if (push && workspace != Workspace.OWN) {
            broken.add(Broken.PUSH_WITHOUT_OWN_WORK);
        }
        if (pushesWork() && !push) {
            broken.add(Broken.END_NEEDS_PUSH);
        }
        if (push && !pushesWork()) {
            broken.add(Broken.PUSH_NEEDS_END);
        }
        if (givesPoints() && !foreign) {
            broken.add(Broken.POINTS_NEED_ANOTHERS_WORK);
        }
        if (judges() && !givesPoints()) {
            broken.add(Broken.REJECT_NEEDS_POINTS);
        }
        if (reportsFindings() && foreign && !holds(Right.OPEN_THREADS)) {
            broken.add(Broken.FINDINGS_NEED_THREADS);
        }
        if (foreign && !reads()) {
            broken.add(Broken.FOREIGN_WORK_NEEDS_READING);
        }
        if (answersWithFindings() && !readLanded) {
            broken.add(Broken.FINDINGS_NEED_LANDED_WORK);
        }
        if (readLanded && workspace == Workspace.NONE) {
            broken.add(Broken.READ_LANDED_NEEDS_WORKSPACE);
        }
        if (givesPoints() && !holds(Right.OPEN_THREADS) && !judges()) {
            broken.add(Broken.POINTS_NEED_THREADS);
        }
        if ((reportsFindings() || handsBackResult()) && !holds(Right.REPORT)) {
            broken.add(Broken.END_NEEDS_REPORT);
        }
        return List.copyOf(broken);
    }
}
