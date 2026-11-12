package org.fuin.porelo.planner.store.git.stories;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a commit message that says who asked for a change or made it.
 *
 * <p>A heuristic: a match is refused, and a pass proves nothing, since a message can say who in
 * words no list holds - that stays with the reading of each hand-over. A word list alone is crude
 * ("asked" is a plain verb), so it matches two narrower things: a party named, and a phrase that
 * gives credit, taken whole rather than as single words.
 */
final class Who {

    /** The roles and agents' names a message names a party by, and any {@code @handle}. */
    static final List<Pattern> PARTIES =
            List.of(
                    Pattern.compile("\\boperators?\\b", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("\\bcoordinators?\\b", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("\\bsmith\\b", Pattern.CASE_INSENSITIVE),
                    // Agent Core, Agent Matrix: an agent's name is "Agent" and a capitalized word.
                    Pattern.compile("\\bAgent [A-Z][\\w-]*"),
                    // Not after a word or a slash, so 'actions/checkout@v7' and a mail address are
                    // none.
                    Pattern.compile("(?<![\\w.@/-])@[A-Za-z0-9][A-Za-z0-9-]*"));

    /**
     * The phrases that give credit: "at X's word", "as X chose", "asked by", "per X", "X's review".
     */
    static final List<Pattern> CREDITS =
            List.of(
                    Pattern.compile(
                            "\\bat (?:the )?[\\w-]+['\\u2019]s word\\b", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("\\bas (?:the )?[\\w-]+ chose\\b", Pattern.CASE_INSENSITIVE),
                    Pattern.compile(
                            "\\b(?:asked|found|decided|reported|accepted) by\\b",
                            Pattern.CASE_INSENSITIVE),
                    // A name or a handle after it, so "a key made per run" is none.
                    Pattern.compile("\\bper (?:the )?(?:@|[A-Z])[\\w-]*"),
                    Pattern.compile(
                            "\\b[\\w-]+['\\u2019]s (?:review|test)s?\\b",
                            Pattern.CASE_INSENSITIVE));

    private Who() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns what in a text names a party or gives credit.
     *
     * @param text the message's words, trailers left out: a trailer names a person on purpose
     * @param names the commit's own author and committer, each matched as a whole name
     * @return each match as it stands in the text, once; empty when nothing was found
     */
    static List<String> named(String text, Collection<String> names) {
        final List<Pattern> patterns = new ArrayList<>(PARTIES);
        patterns.addAll(CREDITS);
        for (final String name : names) {
            if (!name.isBlank()) {
                patterns.add(
                        Pattern.compile(
                                "(?<!\\w)" + Pattern.quote(name.strip()) + "(?!\\w)",
                                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            }
        }
        final Set<String> found = new LinkedHashSet<>();
        for (final Pattern pattern : patterns) {
            final Matcher match = pattern.matcher(text);
            while (match.find()) {
                found.add(match.group());
            }
        }
        return List.copyOf(found);
    }
}
