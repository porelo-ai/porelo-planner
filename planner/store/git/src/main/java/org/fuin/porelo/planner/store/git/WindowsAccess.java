package org.fuin.porelo.planner.store.git;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What Windows says about a path's owner and access control list, read by SID so that no account or
 * group name of the system's language is compared, and the rules that keep the planner's files to
 * the account alone.
 *
 * <p>An answer is the lines one PowerShell call prints: {@code self <sid>}, then for each path
 * {@code path <index> <owner sid> <reparse 0|1> <d|f>} and one {@code ace <index> <A|D> <sid>
 * <rights> <inheritance> <propagation>} per entry, the last two as .NET numbers their flags.
 *
 * @param self the account the process runs as
 * @param paths each path asked about, in the order asked
 */
public record WindowsAccess(String self, List<WindowsAccess.Entry> paths) {

    /** SYSTEM, which can read everything anyway, as root can. */
    static final String SYSTEM = "S-1-5-18";

    /** The local Administrators group, which can read everything anyway, as root can. */
    static final String ADMINISTRATORS = "S-1-5-32-544";

    /** The owner of the system's own directories. */
    static final String TRUSTED_INSTALLER =
            "S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464";

    /** Removes the object itself. */
    static final long DELETE = 0x10000L;

    /** Changes the object's access control list. */
    static final long WRITE_DAC = 0x40000L;

    /** Takes the object's ownership. */
    static final long WRITE_OWNER = 0x80000L;

    /** Removes or renames any entry of a directory. */
    static final long DELETE_CHILD = 0x40L;

    /** Every right, in the generic form an entry may carry. */
    static final long GENERIC_ALL = 0x10000000L;

    /** Writing, in the generic form an entry may carry. */
    static final long GENERIC_WRITE = 0x40000000L;

    /** Full control of a file or directory. */
    static final long FULL_CONTROL = 0x1F01FFL;

    /** Inherited by directories made below. */
    static final int CONTAINER_INHERIT = 1;

    /** Inherited by files made below. */
    static final int OBJECT_INHERIT = 2;

    /** Not passed on below the next level. */
    static final int NO_PROPAGATE = 1;

    /** Applies only below, not to the path itself. */
    static final int INHERIT_ONLY = 2;

    /** Any right that lets another account move a directory away or change who may reach it. */
    private static final long REPLACES =
            DELETE | WRITE_DAC | WRITE_OWNER | DELETE_CHILD | GENERIC_ALL | GENERIC_WRITE;

    private static final Pattern SID = Pattern.compile("S-1-[0-9]+(-[0-9]+){1,14}");

    /**
     * One entry of an access control list.
     *
     * @param allow whether it allows, not denies
     * @param sid whom it names
     * @param rights its access mask
     * @param inheritance who below inherits it: {@link #CONTAINER_INHERIT}, {@link #OBJECT_INHERIT}
     * @param propagation how: {@link #NO_PROPAGATE}, {@link #INHERIT_ONLY}
     */
    public record Ace(boolean allow, String sid, long rights, int inheritance, int propagation) {

        /**
         * Whether it applies only to what is made below the path, not to the path.
         *
         * @return true when inherit-only
         */
        public boolean inheritOnly() {
            return (propagation & INHERIT_ONLY) != 0;
        }
    }

    /**
     * One path's owner and access control list.
     *
     * @param owner the owner's SID
     * @param reparse whether the path is a reparse point: a symbolic link, a junction or a mount
     * @param directory whether it is a directory
     * @param aces its entries
     */
    public record Entry(String owner, boolean reparse, boolean directory, List<Ace> aces) {

        /** Copies the entries, so a record cannot change after its check. */
        public Entry {
            aces = List.copyOf(aces);
        }
    }

    /** Copies the paths, so a record cannot change after its check. */
    public WindowsAccess {
        paths = List.copyOf(paths);
    }

    /**
     * Reads the lines of one call.
     *
     * @param output what the call printed
     * @param asked how many paths it was asked about
     * @return what it said
     * @throws IllegalStateException when the answer is not exactly in its form, or misses a path
     */
    public static WindowsAccess parse(final String output, final int asked) {
        String self = null;
        final List<String> owners = new ArrayList<>();
        final List<Boolean> reparse = new ArrayList<>();
        final List<Boolean> directory = new ArrayList<>();
        final List<List<Ace>> aces = new ArrayList<>();
        for (final String raw : output.split("\r?\n", -1)) {
            if (raw.isEmpty()) {
                continue;
            }
            final String[] field = raw.split(" ", -1);
            switch (field[0]) {
                case "self" -> {
                    if (field.length != 2 || self != null) {
                        throw unreadable(raw);
                    }
                    self = sid(field[1], raw);
                }
                case "path" -> {
                    if (field.length != 5 || index(field[1], raw) != owners.size()) {
                        throw unreadable(raw);
                    }
                    owners.add(sid(field[2], raw));
                    reparse.add(flag(field[3], raw));
                    directory.add(kind(field[4], raw));
                    aces.add(new ArrayList<>());
                }
                case "ace" -> {
                    if (field.length != 7 || index(field[1], raw) != owners.size() - 1) {
                        throw unreadable(raw);
                    }
                    final boolean allow =
                            switch (field[2]) {
                                case "A" -> true;
                                case "D" -> false;
                                default -> throw unreadable(raw);
                            };
                    final long rights;
                    try {
                        rights = Integer.parseInt(field[4]) & 0xffffffffL;
                    } catch (final NumberFormatException e) {
                        throw unreadable(raw);
                    }
                    aces.getLast()
                            .add(
                                    new Ace(
                                            allow,
                                            sid(field[3], raw),
                                            rights,
                                            small(field[5], raw),
                                            small(field[6], raw)));
                }
                default -> throw unreadable(raw);
            }
        }
        if (self == null || owners.size() != asked) {
            throw new IllegalStateException(
                    "Windows answered for "
                            + owners.size()
                            + " of "
                            + asked
                            + " path(s)"
                            + (self == null ? ", without the account" : ""));
        }
        final List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < owners.size(); i++) {
            entries.add(new Entry(owners.get(i), reparse.get(i), directory.get(i), aces.get(i)));
        }
        return new WindowsAccess(self, entries);
    }

    /**
     * Refuses a path of the planner's own that is not the account's alone: an owner other than the
     * account, SYSTEM or Administrators, an entry for anyone but the account, SYSTEM and
     * Administrators - even one that applies only to what is made below it - a reparse point, or
     * the wrong kind.
     *
     * @param path the path, for the refusal's words
     * @param index its place in the answer
     * @param directory whether it must be a directory, not a file
     * @param inheriting whether the account's full control must also pass to everything made in it,
     *     as Git's files there take nothing else
     * @throws IllegalStateException when it is not the account's alone
     */
    public void requireOwnersAlone(
            final Object path, final int index, final boolean directory, final boolean inheriting) {
        final Entry entry = paths.get(index);
        if (entry.reparse() || entry.directory() != directory) {
            throw new IllegalStateException(
                    path
                            + " is not a "
                            + (directory ? "directory" : "file")
                            + ", or is a link or junction");
        }
        // SYSTEM and Administrators can read everything anyway; an elevated planner's own are
        // theirs.
        if (!trusted(entry.owner())) {
            throw new IllegalStateException(
                    path + " belongs to " + entry.owner() + ", not to this account (" + self + ")");
        }
        for (final Ace ace : entry.aces()) {
            if (ace.allow() && !trusted(ace.sid())) {
                throw new IllegalStateException(
                        path + " lets " + ace.sid() + " in, not only this account");
            }
        }
        if (inheriting && entry.aces().stream().noneMatch(this::passesOn)) {
            throw new IllegalStateException(
                    path + " does not pass this account's full control to what is made in it");
        }
    }

    private boolean passesOn(final Ace ace) {
        return ace.allow()
                && ace.sid().equals(self)
                && (ace.rights() & FULL_CONTROL) == FULL_CONTROL
                && (ace.inheritance() & (CONTAINER_INHERIT | OBJECT_INHERIT))
                        == (CONTAINER_INHERIT | OBJECT_INHERIT)
                && ace.propagation() == 0;
    }

    /**
     * Refuses a directory on the way to the planner's own that another account could rename,
     * delete, re-permission or take over, or that is a reparse point: each lets the place be
     * swapped between the check and the write.
     *
     * @param path the path, for the refusal's words
     * @param index its place in the answer
     * @throws IllegalStateException when another account could swap what lies below it
     */
    public void requireSafeAncestor(final Object path, final int index) {
        final Entry entry = paths.get(index);
        if (entry.reparse() || !entry.directory()) {
            throw new IllegalStateException(path + " is not a directory, or is a link or junction");
        }
        if (!trusted(entry.owner()) && !entry.owner().equals(TRUSTED_INSTALLER)) {
            throw new IllegalStateException(
                    path
                            + " belongs to "
                            + entry.owner()
                            + ", neither this account nor the system");
        }
        for (final Ace ace : entry.aces()) {
            if (ace.allow()
                    && !ace.inheritOnly()
                    && !trusted(ace.sid())
                    && (ace.rights() & REPLACES) != 0) {
                throw new IllegalStateException(
                        path
                                + " lets "
                                + ace.sid()
                                + " rename, delete or re-permission what lies below it");
            }
        }
    }

    private boolean trusted(final String sid) {
        return Set.of(self, SYSTEM, ADMINISTRATORS).contains(sid);
    }

    private static String sid(final String text, final String line) {
        if (!SID.matcher(text).matches()) {
            throw unreadable(line);
        }
        return text;
    }

    private static int index(final String text, final String line) {
        try {
            return Integer.parseInt(text);
        } catch (final NumberFormatException e) {
            throw unreadable(line);
        }
    }

    private static boolean flag(final String text, final String line) {
        return switch (text) {
            case "0" -> false;
            case "1" -> true;
            default -> throw unreadable(line);
        };
    }

    private static int small(final String text, final String line) {
        final int value = index(text, line);
        if (value < 0 || value > 3) {
            throw unreadable(line);
        }
        return value;
    }

    private static boolean kind(final String text, final String line) {
        return switch (text) {
            case "d" -> true;
            case "f" -> false;
            default -> throw unreadable(line);
        };
    }

    private static IllegalStateException unreadable(final String line) {
        return new IllegalStateException("Windows answered a line out of its form: '" + line + "'");
    }
}
