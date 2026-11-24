package org.fuin.porelo.planner.store;

import java.util.List;
import java.util.Set;

/**
 * One change of one item, judged on that item's version and on the items its rule read: the {@link
 * Basis} holds the versions the change was judged on, the target's among them, and the {@link
 * Recheck} the read set its rule checks again before the write. A create has no target version yet,
 * and a comment and a repository's labels carry none; each command is named by its own id, which is
 * what keeps a comment from being written twice.
 */
public sealed interface Command
        permits Command.Write,
                Command.SetFields,
                Command.SetText,
                Command.SetBlockers,
                Command.SetStoryIssues,
                Command.CorrectLabels,
                Command.Create,
                Command.Comment {

    /**
     * Answers the command's own id.
     *
     * @return the id, unique within its group
     */
    String id();

    /**
     * Answers the versions the command was judged on.
     *
     * @return the target's version and those of the items its rule read
     */
    Basis basis();

    /**
     * Answers the read set its rule checks again right before the write.
     *
     * @return the recheck, {@link Recheck#none()} for a command whose rule reads nothing else
     */
    Recheck recheck();

    /**
     * An issue's or a story's state, worker, priority or reason, as one item's part of {@link
     * Writes}.
     *
     * @param id the command's id
     * @param target the issue or story written
     * @param writes what is written, naming the target alone
     * @param basis the versions judged on
     * @param recheck the read set checked again
     */
    record Write(String id, String target, Writes writes, Basis basis, Recheck recheck)
            implements Command {

        /** Refuses writes that name another item than the target, or none. */
        public Write {
            named(id);
            Ids.issue(target);
            if (!writes.targets().equals(Set.of(target))) {
                throw new IllegalArgumentException("A write command writes its target alone.");
            }
        }
    }

    /**
     * An issue's fields.
     *
     * @param id the command's id
     * @param issue the issue
     * @param fields the fields set
     * @param basis the versions judged on
     * @param recheck the read set checked again
     */
    record SetFields(String id, String issue, Fields fields, Basis basis, Recheck recheck)
            implements Command {

        /** Checks the ids. */
        public SetFields {
            named(id);
            Ids.issue(issue);
        }
    }

    /**
     * An issue's or a story's title or text.
     *
     * @param id the command's id
     * @param item the issue or story
     * @param edit the edit
     * @param basis the versions judged on
     * @param recheck the read set checked again
     */
    record SetText(String id, String item, TextEdit edit, Basis basis, Recheck recheck)
            implements Command {

        /** Checks the ids. */
        public SetText {
            named(id);
            Ids.issue(item);
        }
    }

    /**
     * The issues an issue is blocked by.
     *
     * @param id the command's id
     * @param issue the issue
     * @param blockedBy its blockers, all of them
     * @param basis the versions judged on
     * @param recheck the read set checked again
     */
    record SetBlockers(
            String id, String issue, List<String> blockedBy, Basis basis, Recheck recheck)
            implements Command {

        /** Checks the ids and copies the blockers. */
        public SetBlockers {
            named(id);
            Ids.issue(issue);
            blockedBy = List.copyOf(blockedBy);
        }
    }

    /**
     * A story's issues.
     *
     * @param id the command's id
     * @param story the story
     * @param issues its issues, all of them, in order
     * @param basis the versions judged on
     * @param recheck the read set checked again
     */
    record SetStoryIssues(
            String id, String story, List<String> issues, Basis basis, Recheck recheck)
            implements Command {

        /** Checks the ids and copies the issues. */
        public SetStoryIssues {
            named(id);
            Ids.story(story);
            issues = List.copyOf(issues);
        }
    }

    /**
     * The calls on one repository's labels, which carry no item's version.
     *
     * @param id the command's id
     * @param repository the repository's name
     * @param calls the calls, in order
     */
    record CorrectLabels(String id, String repository, List<RepositoryWarning.Call> calls)
            implements Command {

        /** Checks the id and the name and copies the calls. */
        public CorrectLabels {
            named(id);
            Ids.name(repository);
            calls = List.copyOf(calls);
        }

        @Override
        public Basis basis() {
            return Basis.none();
        }

        @Override
        public Recheck recheck() {
            return Recheck.none();
        }
    }

    /**
     * An issue appended to, which carries no version: written once by its command's id.
     *
     * @param id the command's id
     * @param issue the issue
     * @param text the comment
     */
    record Comment(String id, String issue, String text) implements Command {

        /** Checks the ids. */
        public Comment {
            named(id);
            Ids.issue(issue);
        }

        @Override
        public Basis basis() {
            return Basis.none();
        }

        @Override
        public Recheck recheck() {
            return Recheck.none();
        }
    }

    /**
     * A new item, which has no version yet: the command carries the versions of the items its rule
     * read, and its outcome names what was made.
     */
    sealed interface Create extends Command
            permits Command.CreateIssue,
                    Command.CreateIdea,
                    Command.CreateStory,
                    Command.AddUnlisted {}

    /**
     * A new issue.
     *
     * @param id the command's id
     * @param issue what the owner account wrote
     * @param basis the versions of what its rule read, the story it goes under among them
     * @param recheck the read set checked again
     */
    record CreateIssue(String id, NewIssue issue, Basis basis, Recheck recheck) implements Create {

        /** Checks the id. */
        public CreateIssue {
            named(id);
        }
    }

    /**
     * A new idea, a story with its issues.
     *
     * @param id the command's id
     * @param idea the idea
     * @param basis the versions of what its rule read
     * @param recheck the read set checked again
     */
    record CreateIdea(String id, NewIdea idea, Basis basis, Recheck recheck) implements Create {

        /** Checks the id. */
        public CreateIdea {
            named(id);
        }
    }

    /**
     * A new story over issues that stand.
     *
     * @param id the command's id
     * @param story the story
     * @param basis the versions of what its rule read, the issues it takes among them
     * @param recheck the read set checked again
     */
    record CreateStory(String id, StoryFromIssues story, Basis basis, Recheck recheck)
            implements Create {

        /** Checks the id. */
        public CreateStory {
            named(id);
        }
    }

    /**
     * An issue from outside the project, added to it.
     *
     * @param id the command's id
     * @param issue the issue
     * @param basis the versions judged on
     * @param recheck the read set checked again
     */
    record AddUnlisted(String id, String issue, Basis basis, Recheck recheck) implements Create {

        /** Checks the ids. */
        public AddUnlisted {
            named(id);
            Ids.issue(issue);
        }
    }

    /**
     * Refuses a blank command id.
     *
     * @param id the id
     */
    private static void named(final String id) {
        if (id.isBlank()) {
            throw new IllegalArgumentException("A command is named by an id.");
        }
    }
}
