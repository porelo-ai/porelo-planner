package org.fuin.porelo.planner.store.github;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.fuin.porelo.forge.github.Delivery;
import org.fuin.porelo.forge.github.GitHubClient;
import org.fuin.porelo.forge.github.GitHubException;
import org.fuin.porelo.forge.github.Page;
import org.fuin.porelo.forge.github.model.IssuesCreateLabelRequest;
import org.fuin.porelo.forge.github.model.IssuesUpdateLabelRequest;
import org.fuin.porelo.forge.github.model.Label;
import org.fuin.porelo.planner.store.RepositoryWarning;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.TopicLabels;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Each repository's labels as last read, compared with the topic labels and GitHub's defaults. They
 * are read only when asked - at the project's first read and at each refresh, and one repository's
 * at its correction's preview and confirm - and kept between, so a plain read of the board asks
 * GitHub nothing about them; a correction's calls change the labels kept as they change GitHub's.
 */
public final class RepositoryLabels {

    private static final Logger LOG = LoggerFactory.getLogger(RepositoryLabels.class);

    /** The most labels a page carries, as the list is asked for them. */
    static final int PAGE_SIZE = 100;

    /** The most pages read of one repository's labels; a list longer is a fault. */
    static final int MOST_PAGES = 30;

    /** One repository's labels as GitHub keeps them. */
    public interface Forge {

        /**
         * Asks for a page of a repository's labels.
         *
         * @param token the person's token
         * @param owner the repository's owner
         * @param repository the repository's name
         * @param page the page, from 1
         * @return the page
         */
        Page<Label> labels(String token, String owner, String repository, int page);

        /**
         * Deletes a label by its name.
         *
         * @param token the person's token
         * @param owner the repository's owner
         * @param repository the repository's name
         * @param name the label's name
         */
        void delete(String token, String owner, String repository, String name);

        /**
         * Creates a label.
         *
         * @param token the person's token
         * @param owner the repository's owner
         * @param repository the repository's name
         * @param name the label's name
         * @param color its color
         * @param description its description
         */
        void create(
                String token,
                String owner,
                String repository,
                String name,
                String color,
                String description);

        /**
         * Sets a label's color and description, keeping its name.
         *
         * @param token the person's token
         * @param owner the repository's owner
         * @param repository the repository's name
         * @param name the label's name
         * @param color its color
         * @param description its description
         */
        void update(
                String token,
                String owner,
                String repository,
                String name,
                String color,
                String description);

        /**
         * Asks through the forge client's REST calls.
         *
         * @param client the client
         * @return the calls
         */
        static Forge over(final GitHubClient client) {
            return new Forge() {
                @Override
                public Page<Label> labels(
                        final String token,
                        final String owner,
                        final String repository,
                        final int page) {
                    return client.labels(token, owner, repository, page, PAGE_SIZE);
                }

                @Override
                public void delete(
                        final String token,
                        final String owner,
                        final String repository,
                        final String name) {
                    client.deleteLabel(token, owner, repository, name);
                }

                @Override
                public void create(
                        final String token,
                        final String owner,
                        final String repository,
                        final String name,
                        final String color,
                        final String description) {
                    client.createLabel(
                            token,
                            owner,
                            repository,
                            new IssuesCreateLabelRequest(name, color, description));
                }

                @Override
                public void update(
                        final String token,
                        final String owner,
                        final String repository,
                        final String name,
                        final String color,
                        final String description) {
                    client.updateLabel(
                            token,
                            owner,
                            repository,
                            name,
                            new IssuesUpdateLabelRequest(null, color, description, null));
                }
            };
        }
    }

    /** A list of labels longer than the planner reads. */
    private static final class TooMany extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TooMany() {
            super("More labels than the planner reads", null, false, false);
        }
    }

    /** A label as kept: its color, lower-case, and its description, empty when it has none. */
    private record Kept(String color, String description) {}

    /** A store that keeps no repository's labels: it reads none and makes no call. */
    public static final RepositoryLabels NONE = new RepositoryLabels(null);

    private final @Nullable Forge forge;

    /** Each repository's labels as last read, by repository, then by lower-cased label name. */
    private final Map<String, Map<String, Kept>> kept = new LinkedHashMap<>();

    /** Each repository whose labels could not be read the last time, with why. */
    private final Map<String, String> faults = new LinkedHashMap<>();

    private boolean read;

    /**
     * Keeps the labels read through a forge.
     *
     * @param forge the forge's calls, or null for a store that keeps none
     */
    public RepositoryLabels(final @Nullable Forge forge) {
        this.forge = forge;
    }

    /**
     * Whether the labels are kept: a store with them names its repositories' warnings and makes a
     * correction's calls.
     *
     * @return true when a forge was given
     */
    public boolean kept() {
        return forge != null;
    }

    /**
     * Whether the labels were read once.
     *
     * @return true after the first {@link #read}
     */
    public synchronized boolean everRead() {
        return read;
    }

    /**
     * Reads every repository's labels anew, each to its last page; one that cannot be read is a
     * fault naming GitHub's status alone, and its warning is left out until the next read.
     *
     * @param token the person's token
     * @param owner the repositories' owner
     * @param repositories the project's repositories
     */
    public synchronized void read(
            final String token, final String owner, final List<String> repositories) {
        final Forge calls = forge;
        if (calls == null) {
            return;
        }
        read = true;
        kept.clear();
        faults.clear();
        for (final String repository : repositories) {
            try {
                kept.put(repository, labelsOf(calls, token, owner, repository));
            } catch (final GitHubException e) {
                faults.put(repository, "GitHub answered status " + e.status());
            } catch (final TooMany e) {
                faults.put(repository, "it has more than " + MOST_PAGES * PAGE_SIZE + " labels");
            } catch (final RuntimeException e) {
                faults.put(repository, "GitHub did not answer");
                LOG.warn(
                        "The labels of a repository could not be read: {}",
                        e.getClass().getSimpleName());
            }
        }
    }

    /**
     * Reads one repository's labels anew to their last page, as {@link #read} reads each, and keeps
     * them in place of those read before, so a correction is computed from the labels as they stand
     * now; one that cannot be read is a fault until the next read, and the read is refused.
     *
     * @param token the person's token
     * @param owner the repository's owner
     * @param repository the repository's name
     * @throws StoreException.NotSavable when GitHub refused the read, named by its status alone, or
     *     the repository has more labels than the planner reads
     * @throws GitHubException for a read GitHub did not answer, or refused for the login or its
     *     rate limit, which the store's refusals answer
     * @throws UnsupportedOperationException when no labels are kept
     */
    public synchronized void reread(
            final String token, final String owner, final String repository) {
        final Forge calls = forge;
        if (calls == null) {
            throw new UnsupportedOperationException("no repository's labels are kept");
        }
        kept.remove(repository);
        try {
            kept.put(repository, labelsOf(calls, token, owner, repository));
            faults.remove(repository);
        } catch (final GitHubException e) {
            faults.put(repository, "GitHub answered status " + e.status());
            if (refusedCall(e)) {
                throw unread(repository, "GitHub answered status " + e.status());
            }
            throw e;
        } catch (final TooMany e) {
            final String why = "it has more than " + MOST_PAGES * PAGE_SIZE + " labels";
            faults.put(repository, why);
            throw unread(repository, why);
        } catch (final RuntimeException e) {
            faults.put(repository, "GitHub did not answer");
            throw e;
        }
    }

    private static StoreException.NotSavable unread(final String repository, final String why) {
        return new StoreException.NotSavable(
                repository,
                "The labels of repository " + repository + " could not be read: " + why);
    }

    /**
     * Notes that the labels could not be read at all, as when no token is at hand; each repository
     * is a fault until the next read.
     *
     * @param repositories the project's repositories
     * @param why why, in the planner's words
     */
    public synchronized void unread(final List<String> repositories, final String why) {
        if (forge == null) {
            return;
        }
        read = true;
        kept.clear();
        faults.clear();
        repositories.forEach(repository -> faults.put(repository, why));
    }

    private static Map<String, Kept> labelsOf(
            final Forge calls, final String token, final String owner, final String repository) {
        final Map<String, Kept> labels = new LinkedHashMap<>();
        for (int page = 1; ; page++) {
            if (page > MOST_PAGES) {
                throw new TooMany();
            }
            final Page<Label> answer = calls.labels(token, owner, repository, page);
            for (final Label label : answer.items()) {
                labels.put(
                        label.name().toLowerCase(Locale.ROOT),
                        new Kept(
                                label.color().toLowerCase(Locale.ROOT),
                                Objects.requireNonNullElse(label.description(), "")));
            }
            if (!answer.more()) {
                return labels;
            }
        }
    }

    /**
     * The warnings of the repositories whose labels are not the topic labels, as last read, each
     * offering its correction.
     *
     * @param repositories the project's repositories, in the board's order
     * @return the warnings, in that order
     */
    public synchronized List<RepositoryWarning> warnings(final List<String> repositories) {
        final List<RepositoryWarning> warnings = new ArrayList<>();
        for (final String repository : repositories) {
            final Map<String, Kept> labels = kept.get(repository);
            if (labels != null) {
                final RepositoryWarning warning = compared(repository, labels);
                if (warning != null) {
                    warnings.add(warning);
                }
            }
        }
        return List.copyOf(warnings);
    }

    /**
     * The faults of the repositories whose labels could not be read the last time.
     *
     * @param repositories the project's repositories, in the board's order
     * @return the faults, in that order
     */
    public synchronized List<String> faults(final List<String> repositories) {
        final List<String> found = new ArrayList<>();
        for (final String repository : repositories) {
            final String why = faults.get(repository);
            if (why != null) {
                found.add("The labels of repository " + repository + " could not be read: " + why);
            }
        }
        return List.copyOf(found);
    }

    private static @Nullable RepositoryWarning compared(
            final String repository, final Map<String, Kept> labels) {
        final List<String> missing = new ArrayList<>();
        final List<String> differing = new ArrayList<>();
        for (final TopicLabels.Label topic : TopicLabels.ALL) {
            final Kept held = labels.get(topic.name());
            if (held == null) {
                missing.add(topic.name());
            } else if (!held.color().equals(topic.color())
                    || !held.description().equals(topic.description())) {
                differing.add(topic.name());
            }
        }
        final List<String> defaults =
                TopicLabels.DEFAULTS.stream().filter(labels::containsKey).toList();
        if (missing.isEmpty() && defaults.isEmpty() && differing.isEmpty()) {
            return null;
        }
        return new RepositoryWarning(
                        repository,
                        RepositoryWarning.TOPIC_LABELS,
                        missing,
                        defaults,
                        differing,
                        null)
                .offered();
    }

    /**
     * Makes a correction's calls on one repository in their order and keeps the labels as each call
     * left them; a call GitHub refuses stops the calls after it, those made before it stay made.
     *
     * @param token the person's token
     * @param owner the repository's owner
     * @param repository the repository's name
     * @param calls the calls
     * @throws StoreException.NotSavable for a call GitHub refused, named by its status alone
     * @throws GitHubException for a call GitHub did not answer, or refused for the login or its
     *     rate limit, which the store's refusals answer
     * @throws UnsupportedOperationException when no labels are kept
     */
    public synchronized void correct(
            final String token,
            final String owner,
            final String repository,
            final List<RepositoryWarning.Call> calls) {
        final Forge made = forge;
        if (made == null) {
            throw new UnsupportedOperationException("no repository's labels are kept");
        }
        final Map<String, Kept> labels =
                kept.computeIfAbsent(repository, r -> new LinkedHashMap<>());
        for (final RepositoryWarning.Call call : calls) {
            final String name = call.label();
            final String key = name.toLowerCase(Locale.ROOT);
            try {
                switch (call.action()) {
                    case DELETE -> {
                        made.delete(token, owner, repository, name);
                        labels.remove(key);
                    }
                    case CREATE -> {
                        final String color = Objects.requireNonNull(call.color());
                        final String description = Objects.requireNonNull(call.description());
                        made.create(token, owner, repository, name, color, description);
                        labels.put(key, new Kept(color, description));
                    }
                    case UPDATE -> {
                        final String color = Objects.requireNonNull(call.color());
                        final String description = Objects.requireNonNull(call.description());
                        made.update(token, owner, repository, name, color, description);
                        labels.put(key, new Kept(color, description));
                    }
                }
            } catch (final GitHubException e) {
                if (refusedCall(e)) {
                    throw new StoreException.NotSavable(
                            repository, "GitHub refused the call with status " + e.status());
                }
                throw e;
            }
        }
    }

    /** A refusal of the call itself, not of the login, its rate limit or an unanswered call. */
    private static boolean refusedCall(final GitHubException e) {
        return e.delivery() != Delivery.SENT_UNANSWERED
                && e.status() >= 400
                && e.status() < 500
                && e.status() != 401
                && e.status() != 403
                && e.status() != 429;
    }
}
