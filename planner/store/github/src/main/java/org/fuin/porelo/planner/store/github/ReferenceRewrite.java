package org.fuin.porelo.planner.store.github;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Rewrites the references of a Markdown project's text for GitHub Issues: a link to an issue's or a
 * story's row in an index becomes the tracker reference of the item carried for it, and a link to
 * one that is not carried becomes its plain text, never a guess. A link in a code span or a fenced
 * block, and one to a page outside the project's repositories, stays as it is.
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

    /** A page on the forge: its host, owner and repository, then a path inside it. */
    private static final Pattern FORGE_PAGE =
            Pattern.compile("https://[^/]+/[^/]+/(?<repository>[^/]+)/blob/[^/]+/.*");

    /** A code span on one line. */
    private static final Pattern CODE_SPAN = Pattern.compile("`[^`\\n]*`");

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
     * @param repositories the project's repositories
     * @param carried finds the reference of each item carried
     * @return the text rewritten, with what was rewritten and what was left as text
     */
    public static Rewritten rewrite(
            final String text,
            final String repository,
            final List<String> repositories,
            final Carried carried) {
        final List<String> rewritten = new ArrayList<>();
        final List<String> left = new ArrayList<>();
        final StringBuilder out = new StringBuilder(text.length());
        boolean fenced = false;
        String fence = "";
        for (final String line : text.split("(?<=\n)", -1)) {
            final String stripped = line.strip();
            if (!fenced && (stripped.startsWith("```") || stripped.startsWith("~~~"))) {
                fenced = true;
                fence = stripped.substring(0, 3);
                out.append(line);
                continue;
            }
            if (fenced) {
                if (stripped.startsWith(fence)) {
                    fenced = false;
                }
                out.append(line);
                continue;
            }
            out.append(line(line, repository, repositories, carried, rewritten, left));
        }
        return new Rewritten(out.toString(), rewritten, left);
    }

    private static String line(
            final String line,
            final String repository,
            final List<String> repositories,
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
                            repositories,
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
            final List<String> repositories,
            final Carried carried,
            final List<String> rewritten,
            final List<String> left) {
        final Matcher row = ROW.matcher(target);
        if (!row.matches()) {
            return null;
        }
        final String page = row.group("page");
        final String named;
        if (page.contains("://")) {
            final Matcher forge = FORGE_PAGE.matcher(target);
            if (!forge.matches()) {
                return null;
            }
            named = forge.group("repository");
            if (!repositories.contains(named)) {
                // A page of another project is no item of this one, so its link stays.
                return null;
            }
        } else {
            named = repository;
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

    private static boolean inSpan(final List<int[]> spans, final int at) {
        for (final int[] span : spans) {
            if (at >= span[0] && at < span[1]) {
                return true;
            }
        }
        return false;
    }
}
