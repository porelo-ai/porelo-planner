package org.fuin.porelo.planner.store.github;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Rewrites the references of a Markdown project's text for GitHub Issues: a link to an issue's or a
 * story's row in an index becomes the tracker reference of the item carried for it, and a link to
 * one that is not carried becomes its plain text, never a guess. A link in a code span or a fenced
 * block, and one to any page but an index of the project's repositories, stays as it is.
 */
public final class ReferenceRewrite {

    /** Finds the tracker reference of a carried item. */
    @FunctionalInterface
    public interface Carried {

        /**
         * Answers the reference of an item carried over.
         *
         * @param repository the repository the link names
         * @param id the item's id, as the row's anchor names it
         * @return its tracker reference, or empty when no item of that repository and id is carried
         */
        Optional<String> reference(String repository, String id);
    }

    /**
     * A text with its references rewritten.
     *
     * @param text the text
     * @param rewritten each reference rewritten, as {@code <id> -> <reference>}, in the text's
     *     order
     * @param leftAsText each id whose link was left as plain text, since nothing is carried for it
     */
    public record Rewritten(String text, List<String> rewritten, List<String> leftAsText) {

        /** Copies the lists. */
        public Rewritten {
            rewritten = List.copyOf(rewritten);
            leftAsText = List.copyOf(leftAsText);
        }
    }

    /** A Markdown link with its text and target. */
    private static final Pattern LINK = Pattern.compile("\\[([^\\]\\n]*)\\]\\(([^)\\s]+)\\)");

    /** A target to an index's row: its page and the row's anchor, an issue's or a story's. */
    private static final Pattern ROW =
            Pattern.compile(
                    "(?<page>[^#]*)README\\.md#(?<anchor>[a-z]{1,3}[0-9]{1,5}(?:\\.[0-9]{1,3})?|st[0-9]{1,5})");

    /** An index of a repository, which holds the rows of its issues or of its stories. */
    private static final Pattern INDEX =
            Pattern.compile("issues/README\\.md|issues/[^/]+/README\\.md|stories/README\\.md");

    /** A code span on one line, opened and closed by one run of backticks of the same length. */
    private static final Pattern CODE_SPAN =
            Pattern.compile("(`++)[^`\\n]*+(?:(?!\\1)`+[^`\\n]*+)*+\\1");

    /** The text of a link that is the id alone, perhaps with its repository or emphasized. */
    private static final Pattern ID_TEXT =
            Pattern.compile(
                    "^[*_`]*(?:[A-Za-z0-9._-]{1,100}#)?([A-Z]{1,3}[0-9]{1,5}(?:\\.[0-9]{1,3})?)[*_`]*$");

    private ReferenceRewrite() {}

    /**
     * Rewrites a text's references.
     *
     * @param text the text, as its file holds it
     * @param repository the repository the text stands in, which a relative link names
     * @param folder the directory the text's file stands in, such as {@code issues}, which a
     *     relative link starts from
     * @param webs each repository's web address by its name, as {@code project.yml} gives it, which
     *     a link to another repository's index starts with
     * @param carried finds the reference of each item carried
     * @return the text rewritten, with what was rewritten and what was left as text
     */
    public static Rewritten rewrite(
            final String text,
            final String repository,
            final String folder,
            final Map<String, String> webs,
            final Carried carried) {
        final List<String> rewritten = new ArrayList<>();
        final List<String> left = new ArrayList<>();
        final StringBuilder out = new StringBuilder(text.length());
        final BodySections.Fence fence = new BodySections.Fence();
        for (final String line : text.split("(?<=\n)", -1)) {
            if (fence.inside(line.replaceFirst("\r?\n$", ""))) {
                out.append(line);
                continue;
            }
            out.append(line(line, repository, folder, webs, carried, rewritten, left));
        }
        return new Rewritten(out.toString(), rewritten, left);
    }

    private static String line(
            final String line,
            final String repository,
            final String folder,
            final Map<String, String> webs,
            final Carried carried,
            final List<String> rewritten,
            final List<String> left) {
        final List<int[]> spans = new ArrayList<>();
        final Matcher code = CODE_SPAN.matcher(line);
        while (code.find()) {
            spans.add(new int[] {code.start(), code.end()});
        }
        final StringBuilder out = new StringBuilder(line.length());
        final Matcher link = LINK.matcher(line);
        int at = 0;
        while (link.find()) {
            if (inSpan(spans, link.start())) {
                continue;
            }
            final String replaced =
                    replaced(
                            link.group(1),
                            link.group(2),
                            repository,
                            folder,
                            webs,
                            carried,
                            rewritten,
                            left);
            if (replaced == null) {
                continue;
            }
            out.append(line, at, link.start()).append(replaced);
            at = link.end();
        }
        return out.append(line.substring(at)).toString();
    }

    /** The link's replacement, or null when it is no reference to a row of the project. */
    private static @Nullable String replaced(
            final String words,
            final String target,
            final String repository,
            final String folder,
            final Map<String, String> webs,
            final Carried carried,
            final List<String> rewritten,
            final List<String> left) {
        final Matcher row = ROW.matcher(target);
        if (!row.matches()) {
            return null;
        }
        final String page = row.group("page") + "README.md";
        final String named =
                page.contains("://") ? forge(page, webs) : relative(page, folder, repository);
        if (named == null) {
            // A page of another project, or no index, holds no item of this one, so its link stays.
            return null;
        }
        final String id = row.group("anchor").toUpperCase(Locale.ROOT);
        final Optional<String> reference = carried.reference(named, id);
        if (reference.isEmpty()) {
            left.add(id);
            return words;
        }
        rewritten.add(id + " -> " + reference.get());
        final Matcher idText = ID_TEXT.matcher(words);
        return idText.matches() && idText.group(1).equals(id)
                ? reference.get()
                : words + " (" + reference.get() + ")";
    }

    /**
     * The repository whose index an address on the web names: one starting with a repository's web
     * address, its host and owner included, then a revision and an index; else null.
     */
    private static @Nullable String forge(final String page, final Map<String, String> webs) {
        for (final Map.Entry<String, String> web : webs.entrySet()) {
            final String blob = web.getValue() + "/blob/";
            if (!page.regionMatches(true, 0, blob, 0, blob.length())) {
                continue;
            }
            final String rest = page.substring(blob.length());
            final int slash = rest.indexOf('/');
            if (slash > 0 && INDEX.matcher(rest.substring(slash + 1)).matches()) {
                return web.getKey();
            }
        }
        return null;
    }

    /**
     * The repository a relative link names, the text's own, when the page it reaches from the
     * text's folder is one of its indexes; else null, such as for a link out of the repository.
     */
    private static @Nullable String relative(
            final String page, final String folder, final String repository) {
        final List<String> path = new ArrayList<>();
        // A link starting with a slash starts at the repository's root.
        for (final String part : page.startsWith("/") ? new String[0] : folder.split("/")) {
            if (!part.isEmpty()) {
                path.add(part);
            }
        }
        final String[] parts = page.split("/", -1);
        for (int i = 0; i < parts.length - 1; i++) {
            final String part = parts[i];
            if (part.equals("..")) {
                if (path.isEmpty()) {
                    return null;
                }
                path.removeLast();
            } else if (!part.isEmpty() && !part.equals(".")) {
                path.add(part);
            }
        }
        path.add(parts[parts.length - 1]);
        return INDEX.matcher(String.join("/", path)).matches() ? repository : null;
    }

    private static boolean inSpan(final List<int[]> spans, final int at) {
        for (final int[] span : spans) {
            if (at >= span[0] && at < span[1]) {
                return true;
            }
        }
        return false;
    }
}
