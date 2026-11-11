package org.fuin.porelo.planner.store.git;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The planning files of one commit as one recursive {@code git ls-tree -l} listed them, with the
 * texts of its plain files when one {@code git cat-file --batch} could read them all.
 */
final class CommitFiles {

    /** The top-level paths whose files a commit's snapshot holds. */
    static final List<String> PLANNING = List.of("project.yml", "issues", "stories", "stats");

    private static final Pattern OBJECT = Pattern.compile("[0-9a-f]{40}([0-9a-f]{24})?");

    private static final Pattern MODE = Pattern.compile("[0-7]{6}");

    private static final Pattern SIZE = Pattern.compile("-|[0-9]{1,18}");

    /**
     * One entry of the tree.
     *
     * @param mode its mode, such as {@code 100644}
     * @param type {@code blob}, {@code tree} or {@code commit}
     * @param object its object id
     * @param size its size in bytes, {@code -1} for a tree or a submodule
     */
    record Entry(String mode, String type, String object, long size) {

        /** Answers whether it is a file the store reads: plain or executable, never a link. */
        boolean plain() {
            return type.equals("blob") && (mode.equals("100644") || mode.equals("100755"));
        }
    }

    private final Map<String, Entry> entries;

    private final @Nullable Map<String, byte[]> texts;

    private CommitFiles(
            final Map<String, Entry> entries, final @Nullable Map<String, byte[]> texts) {
        this.entries = entries;
        this.texts = texts;
    }

    /**
     * Answers whether a path is one a snapshot answers for: {@code project.yml} or below one of the
     * planning directories.
     *
     * @param path a checked path
     * @return whether it is one
     */
    static boolean file(final String path) {
        return path.equals("project.yml") || directory(path) && path.indexOf('/') > 0;
    }

    /**
     * Answers whether a directory is one a snapshot lists: a planning directory or one below it.
     *
     * @param directory a checked path
     * @return whether it is one
     */
    static boolean directory(final String directory) {
        final int slash = directory.indexOf('/');
        final String top = slash < 0 ? directory : directory.substring(0, slash);
        return !top.equals("project.yml") && PLANNING.contains(top);
    }

    /**
     * Reads the output of {@code git ls-tree -r -t -l -z}.
     *
     * @param listing the output
     * @return each entry by its path
     * @throws GitFailure when the output is not of that form
     */
    static Map<String, Entry> entries(final byte[] listing) {
        final Map<String, Entry> found = new TreeMap<>();
        for (final String record : new String(listing, StandardCharsets.UTF_8).split("\0")) {
            if (record.isEmpty()) {
                continue;
            }
            // "<mode> <type> <object> <size>\t<path>"
            final int tab = record.indexOf('\t');
            final String[] head = tab < 0 ? new String[0] : record.substring(0, tab).split(" +");
            if (head.length != 4
                    || !MODE.matcher(head[0]).matches()
                    || !List.of("blob", "tree", "commit").contains(head[1])
                    || !OBJECT.matcher(head[2]).matches()
                    || !SIZE.matcher(head[3]).matches()) {
                throw new GitFailure(GitFailure.Kind.FAILED, "git ls-tree answered otherwise");
            }
            found.put(
                    record.substring(tab + 1),
                    new Entry(
                            head[0],
                            head[1],
                            head[2],
                            head[3].equals("-") ? -1 : Long.parseLong(head[3])));
        }
        return found;
    }

    /**
     * The blobs a snapshot reads: those of the plain files within the bound, each once.
     *
     * @param entries the entries
     * @param fileBytes the largest file read
     * @return each blob's id with its size
     */
    static Map<String, Long> wanted(final Map<String, Entry> entries, final int fileBytes) {
        final Map<String, Long> blobs = new java.util.LinkedHashMap<>();
        for (final Entry entry : entries.values()) {
            if (entry.plain() && entry.size() <= fileBytes) {
                blobs.put(entry.object(), entry.size());
            }
        }
        return blobs;
    }

    /**
     * How many bytes {@code git cat-file --batch} prints for the blobs.
     *
     * @param blobs each blob's id with its size
     * @return the bytes
     */
    static long printed(final Map<String, Long> blobs) {
        long total = 0;
        for (final Map.Entry<String, Long> blob : blobs.entrySet()) {
            total += header(blob.getKey(), blob.getValue()).length + blob.getValue() + 1;
        }
        return total;
    }

    /**
     * The input of {@code git cat-file --batch}: one id per line.
     *
     * @param blobs the blobs' ids
     * @return the input
     */
    static byte[] input(final Collection<String> blobs) {
        final StringBuilder input = new StringBuilder();
        for (final String blob : blobs) {
            input.append(blob).append('\n');
        }
        return input.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Reads the output of {@code git cat-file --batch} fed the blobs' ids in their order, byte by
     * byte as its headers announce them.
     *
     * @param out the output
     * @param blobs each blob's id with the size the tree lists
     * @return each blob's bytes by its id
     * @throws GitFailure when a blob is missing, of another type or size, or the output is short or
     *     longer
     */
    static Map<String, byte[]> blobs(final byte[] out, final Map<String, Long> blobs) {
        final Map<String, byte[]> read = new HashMap<>();
        int at = 0;
        for (final Map.Entry<String, Long> blob : blobs.entrySet()) {
            final byte[] header = header(blob.getKey(), blob.getValue());
            final long end = (long) at + header.length + blob.getValue() + 1;
            if (end > out.length
                    || !java.util.Arrays.equals(
                            out, at, at + header.length, header, 0, header.length)
                    || out[(int) end - 1] != '\n') {
                throw new GitFailure(
                        GitFailure.Kind.FAILED,
                        "git cat-file answered otherwise for " + blob.getKey());
            }
            read.put(
                    blob.getKey(),
                    java.util.Arrays.copyOfRange(out, at + header.length, (int) end - 1));
            at = (int) end;
        }
        if (at != out.length) {
            throw new GitFailure(GitFailure.Kind.FAILED, "git cat-file answered more than asked");
        }
        return read;
    }

    private static byte[] header(final String blob, final long size) {
        return (blob + " blob " + size + "\n").getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Makes a snapshot.
     *
     * @param entries each entry by its path
     * @param texts each read blob's bytes by its id, or {@code null} when each file is read alone
     * @return the snapshot
     */
    static CommitFiles of(
            final Map<String, Entry> entries, final @Nullable Map<String, byte[]> texts) {
        return new CommitFiles(Map.copyOf(entries), texts == null ? null : Map.copyOf(texts));
    }

    /**
     * Answers a path's entry.
     *
     * @param path the path
     * @return its entry, or {@code null} when the commit has none
     */
    @Nullable Entry entry(final String path) {
        return entries.get(path);
    }

    /**
     * Answers a blob's bytes, if the snapshot holds them.
     *
     * @param object the blob's id
     * @return its bytes, or {@code null} when each file is read alone
     */
    byte @Nullable [] bytes(final String object) {
        if (texts == null) {
            return null;
        }
        final byte[] bytes = texts.get(object);
        if (bytes == null) {
            throw new IllegalStateException("A plain file's blob was not read: " + object);
        }
        return bytes;
    }

    /**
     * Lists the entries of one type directly under a directory, as {@code git ls-tree <dir>/} does.
     *
     * @param directory the directory
     * @param type {@code blob} or {@code tree}
     * @return their paths, sorted
     */
    List<String> under(final String directory, final String type) {
        final List<String> found = new ArrayList<>();
        final String prefix = directory + "/";
        for (final Map.Entry<String, Entry> each : entries.entrySet()) {
            final String path = each.getKey();
            if (path.startsWith(prefix)
                    && path.indexOf('/', prefix.length()) < 0
                    && each.getValue().type().equals(type)) {
                found.add(path);
            }
        }
        found.sort(null);
        return List.copyOf(found);
    }
}
