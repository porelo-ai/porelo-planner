package org.fuin.porelo.planner.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.fuin.porelo.planner.core.IssuePlace;
import org.fuin.porelo.planner.core.NextStep;
import org.fuin.porelo.planner.core.ReviewOpen;
import org.fuin.porelo.planner.core.RunPlace;
import org.fuin.porelo.planner.core.WorkOrder;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.store.Role;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.cache.BoardCopy;
import org.fuin.porelo.planner.store.cache.ForyEncoding;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/**
 * The executable's check of its own image: every bound type is filled, written and read back, so a
 * type the image cannot bind is found by running the executable, not on a person's computer.
 *
 * <p>Each line names a type and how many values its written form holds, so the executable's answer
 * can be held to the JVM's: a value the image drops shows as a smaller count.
 */
final class SerializationCheck {

    /** The command's name. */
    static final String NAME = "check-serialization";

    /** The exit code when a type is not read back as it was written. */
    static final int FAILED = 1;

    /** How deep records are filled; deeper ones stay empty, as GitHub's own nest without end. */
    private static final int DEPTH = 6;

    /**
     * The texts a record is made with, the first it takes kept: a plain word, an issue's reference
     * and a UTC time, for a record that holds one as text, as the work record does.
     */
    private static final List<String> TEXTS =
            List.of("sample", "porelo-ai/sample#1", "2026-01-02T03:04:05Z"); // not-a-citation

    private static final Instant WHEN = Instant.parse("2026-01-02T03:04:05Z");

    /** Answers may omit optional fields, unlike the strict work record on disk. */
    private static final JsonMapper ANSWERS = JsonMapper.builder().build();

    /** The forge client's own mapper, with its readers for GitHub's untyped unions. */
    private final ObjectMapper json = forgeMapper();

    private SerializationCheck() {}

    /**
     * Writes and reads back every bound type, one line each.
     *
     * @param out where each type's line goes
     * @param err where each failure goes
     * @return 0 when every type came back as it was written, {@value #FAILED} otherwise
     */
    static int run(final PrintStream out, final PrintStream err) {
        final SerializationCheck check = new SerializationCheck();
        boolean failed = false;
        for (final Class<?> type : BoundTypes.GITHUB) {
            try {
                out.println(type.getSimpleName() + " " + check.json(type) + " values");
            } catch (final IOException | RuntimeException | LinkageError e) {
                err.println(type.getName() + " is not read back: " + e);
                failed = true;
            }
        }
        for (final Class<?> type : BoundTypes.OWN) {
            try {
                out.println(type.getSimpleName() + " " + own(type) + " values");
            } catch (final RuntimeException | LinkageError e) {
                err.println(type.getName() + " is not read back: " + e);
                failed = true;
            }
        }
        try {
            out.println(BoardCopy.class.getSimpleName() + " " + check.copy() + " records");
        } catch (final IOException | RuntimeException | LinkageError e) {
            err.println(BoardCopy.class.getName() + " is not read back: " + e);
            failed = true;
        }
        return failed ? FAILED : 0;
    }

    /**
     * The mapper the forge client reads GitHub's answers with, as the planner builds the client;
     * the forge keeps it to its package, so it is reached the one way the image registers.
     */
    static ObjectMapper forgeMapper() {
        try {
            final java.lang.reflect.Method mapper =
                    Class.forName(BoundTypes.FORGE_JSON)
                            .getDeclaredMethod(
                                    "mapper", org.fuin.porelo.forge.github.Strictness.class);
            mapper.setAccessible(true);
            return (ObjectMapper)
                    mapper.invoke(null, org.fuin.porelo.forge.github.Strictness.LENIENT);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("the forge client's mapper is not reached: " + e, e);
        }
    }

    /** Writes a filled record or answer with the planner's JSON, and reads it back. */
    private static int own(final Class<?> type) {
        if (type == org.fuin.porelo.planner.app.answers.ProblemParameters.class) {
            sparseParameters();
        }
        final Object sample = sample(type);
        final JsonMapper mapper =
                type.getPackageName().equals("org.fuin.porelo.planner.app.answers")
                        ? ANSWERS
                        : WorkRecord.JSON;
        final String written = mapper.writerFor(type).writeValueAsString(sample);
        final Object read = mapper.readerFor(type).readValue(written);
        if (!sample.equals(read)) {
            throw new IllegalStateException("written " + written + ", read back differently");
        }
        return mapper.readTree(written).size();
    }

    /**
     * Reads a sparse refusal's parameters back with the answer mapper, checking omitted members.
     *
     * @return the sparse parameters after the round trip
     */
    static tools.jackson.databind.JsonNode sparseParameters() {
        final Class<org.fuin.porelo.planner.app.answers.ProblemParameters> type =
                org.fuin.porelo.planner.app.answers.ProblemParameters.class;
        final Object sample = ANSWERS.readValue("{\"item\":\"AB1\"}", type);
        final String written = ANSWERS.writerFor(type).writeValueAsString(sample);
        final Object read = ANSWERS.readerFor(type).readValue(written);
        final tools.jackson.databind.JsonNode tree = ANSWERS.valueToTree(read);
        if (!sample.equals(read) || tree.size() != 1 || !"AB1".equals(tree.path("item").asText())) {
            throw new IllegalStateException(
                    "Sparse problem parameters did not retain only their named item.");
        }
        return tree;
    }

    /**
     * The instance of the planner's own type the check writes and reads back: the outbox and the
     * work record as the planner writes them, every field of an answer filled, including optional
     * and nullable fields whose loss must change the count.
     *
     * @param type the type
     * @return its sample
     */
    static Object sample(final Class<?> type) {
        if (type == org.fuin.porelo.planner.app.answers.BoardDelta.class) {
            return boardDelta();
        }
        return type == CommandOutbox.Document.class
                ? outbox()
                : type == WorkRecord.Document.class
                        ? workRecord()
                        : Objects.requireNonNull(fill(type, 0), type.getName());
    }

    /** A stream delta with every replacement filled and consecutive connection frames. */
    private static org.fuin.porelo.planner.app.answers.BoardDelta boardDelta() {
        final org.fuin.porelo.planner.app.answers.BoardDelta filled =
                (org.fuin.porelo.planner.app.answers.BoardDelta)
                        Objects.requireNonNull(
                                fill(org.fuin.porelo.planner.app.answers.BoardDelta.class, 0));
        return new org.fuin.porelo.planner.app.answers.BoardDelta(
                1L,
                0L,
                "before",
                "after",
                filled.stories(),
                filled.removedStories(),
                filled.noStory(),
                filled.removedIssues(),
                filled.storyOrder(),
                filled.noStoryOrder(),
                filled.states(),
                filled.phases(),
                filled.work(),
                filled.faults(),
                filled.reasons(),
                filled.repositories(),
                filled.repositoryWarnings(),
                filled.unlisted(),
                filled.open(),
                filled.rules(),
                filled.heldSaves(),
                filled.tracker());
    }

    /**
     * A work record as the planner writes it, its runs filled and one keeping an issue's place with
     * the answer read there and its times, which the filled runs cannot hold: a place takes exactly
     * one member, and an answer's commits and times their own forms.
     *
     * @return the record
     */
    static WorkRecord.Document workRecord() {
        final WorkRecord.Document filled =
                (WorkRecord.Document)
                        Objects.requireNonNull(fill(WorkRecord.Document.class, 0), "work record");
        final ReviewOpen answer =
                new ReviewOpen(
                        "0123456789abcdef0123456789abcdef01234567",
                        ReviewOpen.Decision.RETURNED,
                        null,
                        null,
                        WHEN,
                        List.of(new ReviewOpen.Point("thread", "a.txt", 3, "owner", WHEN)),
                        1,
                        0,
                        true,
                        null,
                        "v1");
        final IssuePlace place =
                new IssuePlace(
                        RunPlace.gate("sample", "task"),
                        false,
                        "task",
                        List.of("00000000-0000-0000-0000-000000000002"),
                        IssuePlace.Review.of(answer));
        // A forwarded push names the branch its commit landed on, not a commit.
        final IssuePlace forwarded =
                new IssuePlace(
                                RunPlace.gate(null, "task"),
                                false,
                                "task",
                                List.of("00000000-0000-0000-0000-000000000003"),
                                IssuePlace.Review.of(
                                        new ReviewOpen(
                                                "0123456789abcdef0123456789abcdef01234567",
                                                ReviewOpen.Decision.APPROVED,
                                                "89abcdef0123456789abcdef0123456789abcdef",
                                                "main",
                                                WHEN,
                                                List.of(),
                                                0,
                                                0,
                                                true,
                                                null,
                                                "v2")))
                        .reviewing("0123456789abcdef0123456789abcdef01234567")
                        // A run the person started by hand works at the forwarded place.
                        .runAgain(WHEN.toString());
        final Map<String, WorkRun.Run> runs = new LinkedHashMap<>(filled.runs());
        runs.put(
                "ST1",
                new WorkRun.Run(
                        "00000000-0000-0000-0000-000000000001",
                        "ST1",
                        "Building",
                        "AGENT",
                        "",
                        "START",
                        2,
                        List.of(),
                        List.of(),
                        WHEN.toString(),
                        List.of(rework(), review()),
                        Map.of(
                                "AA1",
                                NextStep.Progress.start(2),
                                "AA2",
                                NextStep.Progress.start(2)),
                        false,
                        false,
                        null,
                        Map.of("AA1", place, "AA2", forwarded),
                        List.of(REWORK, REVIEW)));
        return new WorkRecord.Document(
                filled.version(),
                filled.project(),
                filled.repository(),
                filled.branch(),
                filled.generation(),
                runs,
                filled.ideas(),
                filled.results(),
                filled.followed(),
                filled.reports());
    }

    /** The id of the author's rework a read of its kept push offered. */
    static final String REWORK = "00000000-0000-0000-0000-000000000004";

    /** The id of the reading of the author's push at its gate push, offered beside the rework. */
    static final String REVIEW = "00000000-0000-0000-0000-000000000005";

    /** A role that builds: it pushes its own work with the project's services. */
    static final Role BUILDING =
            new Role(
                    "author",
                    Role.End.PUSHES_WORK,
                    Role.Workspace.OWN,
                    false,
                    true,
                    Role.WriteAs.AUTHOR,
                    List.of(Role.Right.PUSH, Role.Right.REPLY));

    /** A role that reads another task's work at its place and gives points there. */
    static final Role READING =
            new Role(
                    "reviewer",
                    Role.End.GIVES_POINTS,
                    Role.Workspace.FOREIGN,
                    false,
                    false,
                    Role.WriteAs.REVIEW,
                    List.of(Role.Right.REPLY, Role.Right.OPEN_THREADS));

    /** An author's rework offered at its kept push, as a read of that place offers it. */
    private static WorkRun.Offer rework() {
        final WorkOrder.Task task =
                new WorkOrder.Task(
                                "sample",
                                List.of("AA1"),
                                BUILDING,
                                "agent",
                                "model",
                                null,
                                "AGENT",
                                "Answer each point open at the gate push task.",
                                List.of(),
                                "",
                                null,
                                null,
                                List.of(),
                                null)
                        .again(RunPlace.gate(null, "task"));
        return offer(
                REWORK,
                task,
                new WorkRule(
                        List.of("Reading"),
                        "Review",
                        "Reading",
                        null,
                        WorkRule.Per.REPOSITORY,
                        List.of("AGENT"),
                        "Rework {issues}",
                        Map.of("agent", "model"),
                        Map.of(),
                        null,
                        BUILDING),
                "Reworking",
                new NextStep.Settings("AGENT", "agent", "model"),
                new WorkRun.StartRefusal(
                        "AdapterNotInstalled", Map.of("adapter", "maven"), "Not installed."));
    }

    /** A reading of the author's work at its gate push, which it reads through that place. */
    private static WorkRun.Offer review() {
        final WorkOrder.Task task =
                new WorkOrder.Task(
                                "sample",
                                List.of("AA1"),
                                READING,
                                "agent",
                                "model",
                                null,
                                "AGENT",
                                "Read the work at the gate push task.",
                                List.of(),
                                "",
                                null,
                                null,
                                List.of(),
                                null)
                        .at(RunPlace.gate(null, "task"));
        return offer(
                REVIEW,
                task,
                new WorkRule(
                        List.of("Review"),
                        "Reading",
                        null,
                        "Reworking",
                        WorkRule.Per.REPOSITORY,
                        List.of("AGENT"),
                        "Read {issues}",
                        Map.of("agent", "model"),
                        Map.of(),
                        null,
                        READING),
                "Reading");
    }

    /** An offer no person chose settings for and Porelo refused no start of. */
    private static WorkRun.Offer offer(
            final String id, final WorkOrder.Task task, final WorkRule rule, final String state) {
        return new WorkRun.Offer(
                id,
                task,
                new NextStep.Binding("ST1", "sample", List.of("AA1"), false, state, "AGENT", rule),
                "OFFERED",
                null,
                null,
                null,
                null,
                null,
                "",
                List.of(),
                List.of(),
                null);
    }

    private static WorkRun.Offer offer(
            final String id,
            final WorkOrder.Task task,
            final WorkRule rule,
            final String state,
            final NextStep.Settings rework,
            final WorkRun.StartRefusal refusal) {
        return new WorkRun.Offer(
                id,
                task,
                new NextStep.Binding("ST1", "sample", List.of("AA1"), false, state, "AGENT", rule),
                "OFFERED",
                null,
                null,
                null,
                null,
                null,
                "",
                List.of(),
                List.of(),
                null,
                false,
                "",
                rework,
                refusal);
    }

    /**
     * An outbox as the planner writes it: a command held for every refusal kind it keeps, each with
     * the try that ended there, one in every other state, and a group kept from another source.
     *
     * @return the outbox
     */
    static CommandOutbox.Document outbox() {
        final CommandOutbox.Changes changes =
                (CommandOutbox.Changes)
                        Objects.requireNonNull(fill(CommandOutbox.Changes.class, 1), "changes");
        final List<CommandOutbox.Entry> entries = new ArrayList<>();
        for (final String kind : new java.util.TreeSet<>(CommandOutbox.REASONS)) {
            entries.add(
                    entry(
                            entries.size(),
                            changes,
                            entries.size() % 2 == 0
                                    ? CommandOutbox.State.HELD
                                    : CommandOutbox.State.REFUSED,
                            new CommandOutbox.Why(kind, "AA1", "")));
        }
        for (final CommandOutbox.State state :
                List.of(
                        CommandOutbox.State.QUEUED,
                        CommandOutbox.State.IN_FLIGHT,
                        CommandOutbox.State.DONE)) {
            entries.add(entry(entries.size(), changes, state, CommandOutbox.Why.NONE));
        }
        return new CommandOutbox.Document(
                CommandOutbox.FORMAT,
                "project",
                new CommandOutbox.Source("https://example.org/store.git", ""),
                entries,
                List.of(
                        new CommandOutbox.Earlier(
                                new CommandOutbox.Source("https://example.org/store.git", "old"),
                                List.of(
                                        entry(
                                                entries.size(),
                                                changes,
                                                CommandOutbox.State.HELD,
                                                new CommandOutbox.Why("Changed", "AA1", ""))))));
    }

    private static CommandOutbox.Entry entry(
            final int number,
            final CommandOutbox.Changes changes,
            final CommandOutbox.State state,
            final CommandOutbox.Why why) {
        final String group = "00000000-0000-0000-0000-" + String.format("%012d", number);
        return new CommandOutbox.Entry(
                group,
                group + "/0",
                "Write",
                "AA1",
                Map.of("AA1", "v1"),
                changes,
                state,
                why,
                number % 2 == 0,
                number % 2 == 0 ? "ST1" : "",
                state == CommandOutbox.State.HELD || state == CommandOutbox.State.REFUSED
                        ? List.of(
                                new CommandOutbox.Try(
                                        WHEN,
                                        state,
                                        why,
                                        new CommandOutbox.Answer(
                                                422, "Validation Failed", "C0DE:1234:ABCD")))
                        : List.of());
    }

    /** Writes a filled instance as JSON, reads it back and answers how many values it held. */
    private int json(final Class<?> type) throws IOException {
        final Object sample = Objects.requireNonNull(fill(sampleType(type), 0), type.getName());
        final String written = json.writeValueAsString(sample);
        final Object read = json.readerFor(type).readValue(written);
        final String again = json.writeValueAsString(read);
        if (!written.equals(again)) {
            throw new IllegalStateException("written " + written + ", read back as " + again);
        }
        final JsonNode tree = json.readTree(written);
        if (tree.isEmpty()) {
            throw new IllegalStateException("written without a value: " + written);
        }
        return tree.size();
    }

    /** Writes a filled copy as Fory does, reads it back and answers how many records it held. */
    private int copy() throws IOException {
        final BoardCopy sample = (BoardCopy) Objects.requireNonNull(fill(BoardCopy.class, 0));
        final ForyEncoding fory = new ForyEncoding();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        fory.encode(sample, bytes);
        final BoardCopy read =
                fory.decode(new ByteArrayInputStream(bytes.toByteArray()), bytes.size());
        if (!sample.equals(read)) {
            throw new IllegalStateException("the copy read back differs from the one written");
        }
        final Set<Class<?>> records = new LinkedHashSet<>();
        records(read, records);
        return records.size();
    }

    /** The first bound type an interface's answer is made of, or the type itself. */
    private static Class<?> sampleType(final Class<?> type) {
        if (!type.isInterface()) {
            return type;
        }
        return BoundTypes.GITHUB.stream()
                .filter(made -> !made.isInterface() && type.isAssignableFrom(made))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no type makes " + type.getName()));
    }

    /** Adds the record types an object is made of, itself included. */
    private static void records(final @Nullable Object value, final Set<Class<?>> found) {
        if (value instanceof Iterable<?> items) {
            items.forEach(item -> records(item, found));
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(item -> records(item, found));
        } else if (value != null && value.getClass().isRecord()) {
            found.add(value.getClass());
            for (final RecordComponent component : value.getClass().getRecordComponents()) {
                try {
                    records(component.getAccessor().invoke(value), found);
                } catch (final ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    /** A value of a type with every part set, records filled to {@value #DEPTH} deep. */
    static @Nullable Object fill(final Type type, final int depth) {
        if (type instanceof ParameterizedType parameterized) {
            final Class<?> raw = (Class<?>) parameterized.getRawType();
            final Type[] arguments = parameterized.getActualTypeArguments();
            if (List.class.isAssignableFrom(raw)) {
                return listOf(element(arguments[0], depth + 1));
            }
            if (Set.class.isAssignableFrom(raw)) {
                return setOf(element(arguments[0], depth + 1));
            }
            if (Map.class.isAssignableFrom(raw)) {
                final Map<Object, Object> map = new LinkedHashMap<>();
                final Object key = element(arguments[0], depth + 1);
                final Object value = element(arguments[1], depth + 1);
                if (key != null && value != null) {
                    map.put(key, value);
                }
                return map;
            }
            return fill(raw, depth);
        }
        if (!(type instanceof Class<?> plain)) {
            return "sample";
        }
        return plainValue(plain, depth);
    }

    /**
     * An element of a collection, or none when its record's own rules refuse a value filled without
     * regard to the others, such as a task that must work an issue its run names.
     */
    private static @Nullable Object element(final Type type, final int depth) {
        try {
            return fill(type, depth);
        } catch (final IllegalStateException e) {
            return null;
        }
    }

    private static @Nullable Object plainValue(final Class<?> type, final int depth) {
        if (type == String.class || type == Object.class) {
            return "sample";
        }
        if (type == long.class || type == Long.class) {
            return 7L;
        }
        if (type == int.class || type == Integer.class) {
            return 3;
        }
        if (type == boolean.class || type == Boolean.class) {
            return true;
        }
        if (type == double.class || type == Double.class) {
            return 1.5;
        }
        if (type == float.class || type == Float.class) {
            return 2.5f;
        }
        if (type == BigDecimal.class) {
            return new BigDecimal("4.5");
        }
        if (type == URI.class) {
            return URI.create("https://example.org/sample");
        }
        if (type == Instant.class) {
            return WHEN;
        }
        if (type == OffsetDateTime.class) {
            return WHEN.atOffset(ZoneOffset.UTC);
        }
        if (type == LocalDate.class) {
            return LocalDate.of(2026, 1, 2);
        }
        if (type == UUID.class) {
            return new UUID(1, 2);
        }
        if (type.isEnum()) {
            return type.getEnumConstants()[0];
        }
        if (type.isInterface() && depth <= DEPTH) {
            return BoundTypes.all().stream()
                    .filter(made -> !made.isInterface() && type.isAssignableFrom(made))
                    .findFirst()
                    .map(made -> plainValue(made, depth))
                    .orElse(null);
        }
        if (depth > DEPTH || type.isInterface() || type.isArray() || type.isPrimitive()) {
            return null;
        }
        if (type.isRecord()) {
            return record(type, depth);
        }
        return type.getName().startsWith("org.fuin.porelo.") ? bean(type, depth) : null;
    }

    /**
     * A record made through its canonical constructor with each component filled; its text first as
     * a plain word, then as an issue's reference, for a record that holds one.
     */
    private static Object record(final Class<?> type, final int depth) {
        final RecordComponent[] components = type.getRecordComponents();
        final Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
        }
        final Constructor<?> canonical;
        try {
            canonical = type.getDeclaredConstructor(types);
        } catch (final NoSuchMethodException e) {
            throw new IllegalStateException("cannot make " + type.getName() + ": " + e, e);
        }
        RuntimeException refused = null;
        for (final String text : TEXTS) {
            final Object[] values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                final Object value =
                        types[i] == String.class
                                ? text
                                : fill(components[i].getGenericType(), depth + 1);
                values[i] =
                        value == null && types[i].isPrimitive() ? plainValue(types[i], 0) : value;
            }
            try {
                return canonical.newInstance(values);
            } catch (final java.lang.reflect.InvocationTargetException e) {
                refused =
                        new IllegalStateException(
                                "cannot make " + type.getName() + ": " + e.getCause(),
                                e.getCause());
            } catch (final ReflectiveOperationException e) {
                throw new IllegalStateException("cannot make " + type.getName() + ": " + e, e);
            }
        }
        throw Objects.requireNonNull(refused);
    }

    /** A bean made through its constructor, each property set through its setter. */
    private static @Nullable Object bean(final Class<?> type, final int depth) {
        try {
            final Object bean = type.getConstructor().newInstance();
            for (final java.lang.reflect.Method method : type.getMethods()) {
                if (method.getName().startsWith("set")
                        && method.getParameterCount() == 1
                        && method.getReturnType() == void.class) {
                    final Object value = fill(method.getGenericParameterTypes()[0], depth + 1);
                    if (value != null) {
                        method.invoke(bean, value);
                    }
                }
            }
            return bean;
        } catch (final NoSuchMethodException e) {
            return null;
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("cannot make " + type.getName() + ": " + e, e);
        }
    }

    private static List<Object> listOf(final @Nullable Object value) {
        final List<Object> list = new ArrayList<>();
        if (value != null) {
            list.add(value);
        }
        return List.copyOf(list);
    }

    private static Set<Object> setOf(final @Nullable Object value) {
        return value == null ? Set.of() : Set.of(value);
    }
}
