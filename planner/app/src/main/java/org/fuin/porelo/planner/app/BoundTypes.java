package org.fuin.porelo.planner.app;

import java.util.List;
import java.util.stream.Stream;
import org.fuin.porelo.forge.github.model.Enterprise;
import org.fuin.porelo.forge.github.model.Integration;
import org.fuin.porelo.forge.github.model.IntegrationPermissions;
import org.fuin.porelo.forge.github.model.Issue;
import org.fuin.porelo.forge.github.model.IssueComment;
import org.fuin.porelo.forge.github.model.IssueField;
import org.fuin.porelo.forge.github.model.IssueFieldOptionsInner;
import org.fuin.porelo.forge.github.model.IssueFieldValue;
import org.fuin.porelo.forge.github.model.IssueFieldValueMultiSelectOptionsInner;
import org.fuin.porelo.forge.github.model.IssueFieldValueSingleSelectOption;
import org.fuin.porelo.forge.github.model.IssueLabelsInner;
import org.fuin.porelo.forge.github.model.IssueType;
import org.fuin.porelo.forge.github.model.IssuesAddBlockedByDependencyRequest;
import org.fuin.porelo.forge.github.model.IssuesAddSubIssueRequest;
import org.fuin.porelo.forge.github.model.IssuesCreateLabelRequest;
import org.fuin.porelo.forge.github.model.IssuesCreateRequest;
import org.fuin.porelo.forge.github.model.IssuesCreateRequestIssueFieldValuesInner;
import org.fuin.porelo.forge.github.model.IssuesRemoveSubIssueRequest;
import org.fuin.porelo.forge.github.model.IssuesReprioritizeSubIssueRequest;
import org.fuin.porelo.forge.github.model.IssuesUpdate200Response;
import org.fuin.porelo.forge.github.model.IssuesUpdateCommentRequest;
import org.fuin.porelo.forge.github.model.IssuesUpdateLabelRequest;
import org.fuin.porelo.forge.github.model.IssuesUpdateRequest;
import org.fuin.porelo.forge.github.model.IssuesUpdateRequestIssueFieldValuesInner;
import org.fuin.porelo.forge.github.model.Label;
import org.fuin.porelo.forge.github.model.PrivateUser;
import org.fuin.porelo.forge.github.model.PublicUser;
import org.fuin.porelo.forge.github.model.SimpleUser;
import org.fuin.porelo.forge.github.model.UsersGetAuthenticated200Response;
import org.fuin.porelo.planner.app.answers.BatchSaved;
import org.fuin.porelo.planner.app.answers.Breach;
import org.fuin.porelo.planner.app.answers.Breaches;
import org.fuin.porelo.planner.app.answers.IssueParts;
import org.fuin.porelo.planner.app.answers.ItemResult;
import org.fuin.porelo.planner.app.answers.Outline;
import org.fuin.porelo.planner.app.answers.OutlineIssue;
import org.fuin.porelo.planner.app.answers.OutlineStory;
import org.fuin.porelo.planner.app.answers.Problem;
import org.fuin.porelo.planner.app.answers.ProblemParameters;
import org.fuin.porelo.planner.app.answers.ReadingReply;
import org.fuin.porelo.planner.app.answers.SearchAnswer;
import org.fuin.porelo.planner.app.answers.SearchMatch;
import org.fuin.porelo.planner.app.answers.TaskEventReply;
import org.fuin.porelo.planner.app.answers.TextParts;
import org.fuin.porelo.planner.store.cache.BoardCopy;
import org.fuin.porelo.planner.store.cache.ChangeCursor;
import org.fuin.porelo.planner.store.cache.OverlaidItem;
import org.fuin.porelo.planner.store.cache.TrackedItem;
import org.fuin.porelo.planner.store.cache.TrackedUnlisted;
import org.fuin.porelo.planner.store.cache.TrackedWait;
import org.fuin.porelo.planner.store.cache.TrackerBoard;
import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Every type the planner writes or reads by reflection, which a native image keeps only for the
 * types named: one list for the image's hints and for the executable's own check of them.
 */
final class BoundTypes {

    /** The forge client binds GitHub's REST answers and requests to these. */
    static final List<Class<?>> GITHUB =
            List.of(
                    IssueComment.class,
                    IssuesUpdateCommentRequest.class,
                    IssuesUpdateRequest.class,
                    IssuesUpdate200Response.class,
                    Issue.class,
                    // A story made outside the project is told by its issue type alone.
                    IssueType.class,
                    Integration.class,
                    IntegrationPermissions.class,
                    Enterprise.class,
                    IssuesCreateRequest.class,
                    IssuesAddSubIssueRequest.class,
                    IssuesAddBlockedByDependencyRequest.class,
                    IssuesRemoveSubIssueRequest.class,
                    IssuesReprioritizeSubIssueRequest.class,
                    IssueLabelsInner.class,
                    // A repository's labels, read and corrected to the topic labels.
                    Label.class,
                    IssuesCreateLabelRequest.class,
                    IssuesUpdateLabelRequest.class,
                    // The organization's issue fields, which hold an issue's priority, and an
                    // issue's values.
                    IssueField.class,
                    IssueFieldOptionsInner.class,
                    IssueFieldValue.class,
                    IssueFieldValueSingleSelectOption.class,
                    IssueFieldValueMultiSelectOptionsInner.class,
                    IssuesCreateRequestIssueFieldValuesInner.class,
                    IssuesUpdateRequestIssueFieldValuesInner.class,
                    SimpleUser.class,
                    PrivateUser.class,
                    PublicUser.class,
                    UsersGetAuthenticated200Response.class);

    /** Apache Fory makes the board copy's records through their constructors by reflection. */
    static final List<Class<?>> COPY =
            List.of(
                    BoardCopy.class,
                    BoardCopy.Project.class,
                    TrackerBoard.class,
                    TrackedItem.class,
                    TrackedItem.Issue.class,
                    TrackedItem.Links.class,
                    TrackedItem.Link.class,
                    TrackedItem.Value.class,
                    TrackedItem.Choice.class,
                    ChangeCursor.class,
                    ChangeCursor.Page.class,
                    TrackedWait.class,
                    TrackedUnlisted.class,
                    OverlaidItem.class);

    /**
     * The work record and the outbox on disk, and the event and agent answers, as JSON. Each part
     * has its own check line so a field dropped from a nested answer changes its count too.
     */
    static final List<Class<?>> OWN =
            List.of(
                    WorkRecord.Document.class,
                    CommandOutbox.Document.class,
                    TaskEventReply.class,
                    ReadingReply.class,
                    org.fuin.porelo.planner.app.answers.StoryList.class,
                    org.fuin.porelo.planner.app.answers.IssueList.class,
                    Outline.class,
                    OutlineStory.class,
                    OutlineIssue.class,
                    IssueParts.class,
                    TextParts.class,
                    Breaches.class,
                    Breach.class,
                    SearchAnswer.class,
                    SearchMatch.class,
                    BatchSaved.class,
                    ItemResult.class,
                    Problem.class,
                    ProblemParameters.class);

    /** The forge client's JSON, whose mapper the executable's check reads with. */
    static final String FORGE_JSON = "org.fuin.porelo.forge.github.GitHubJson";

    private BoundTypes() {}

    /**
     * Every bound type.
     *
     * @return GitHub's models, the planner's own JSON, then the copy's records
     */
    static List<Class<?>> all() {
        return Stream.of(GITHUB, OWN, COPY).flatMap(List::stream).toList();
    }

    /** Registers every bound type, and what its properties name, for binding in the image. */
    static final class Hints implements RuntimeHintsRegistrar {

        @Override
        public void registerHints(final RuntimeHints hints, final @Nullable ClassLoader loader) {
            new BindingReflectionHintsRegistrar()
                    .registerReflectionHints(
                            hints.reflection(), all().toArray(java.lang.reflect.Type[]::new));
            // Jackson makes an array of a list's element type, which the image keeps only when
            // named, for each type the binding reached.
            final List<String> bound =
                    hints.reflection()
                            .typeHints()
                            .map(hint -> hint.getType().getName())
                            .filter(name -> name.startsWith("org.fuin.porelo."))
                            .toList();
            for (final String name : bound) {
                hints.reflection()
                        .registerType(
                                org.springframework.util.ClassUtils.resolveClassName(name, loader)
                                        .arrayType());
            }
            hints.reflection()
                    .registerType(
                            org.springframework.aot.hint.TypeReference.of(FORGE_JSON),
                            org.springframework.aot.hint.MemberCategory.INVOKE_DECLARED_METHODS);
        }
    }
}
