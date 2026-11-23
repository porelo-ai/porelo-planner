package org.fuin.porelo.planner.store.files;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The planner's private files on Windows, under {@code %LOCALAPPDATA%\porelo-planner}: each
 * directory it makes gets an access control list of one entry, full control for the account,
 * inherited by what is made in it and by nothing from above; each is checked by owner and entries,
 * and every directory above by who could rename, delete or re-permission it.
 *
 * <p>Java names accounts and groups only in the system's language, so one PowerShell call, taken by
 * its full path, sets and reads the lists by SID; {@link WindowsAccess} holds the rules. Files are
 * not checked one by one: no other account can make, link or replace an entry in a directory that
 * is the account's alone.
 */
public final class WindowsPrivateFiles implements PrivateFiles {

    /** A drive's absolute path, never a UNC, device or {@code \\?\} path. */
    private static final Pattern DRIVE_PATH = Pattern.compile("[A-Za-z]:\\\\[^\\x00-\\x1f]*");

    private static final Duration LIMIT = Duration.ofSeconds(60);

    private static final Logger LOG = LoggerFactory.getLogger(WindowsPrivateFiles.class);

    /**
     * Protects each directory named in {@code PORELO_PROTECT}, then prints the account and each
     * path of {@code PORELO_PATHS} in {@link WindowsAccess}'s form. The paths come through the
     * environment, so none is ever read as code.
     */
    static final String SCRIPT =
            """
            $ErrorActionPreference = 'Stop'
            Import-Module -Name "$PSHOME\\Modules\\Microsoft.PowerShell.Security\\Microsoft.PowerShell.Security.psd1"
            Import-Module -Name "$PSHOME\\Modules\\Microsoft.PowerShell.Management\\Microsoft.PowerShell.Management.psd1"
            $sidType = [System.Security.Principal.SecurityIdentifier]
            $self = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
            function Lines($text) { @($text -split "`n" | Where-Object { $_ -ne '' }) }
            foreach ($p in (Lines $env:PORELO_PROTECT)) {
              $acl = New-Object System.Security.AccessControl.DirectorySecurity
              $acl.SetOwner($self)
              $acl.SetAccessRuleProtection($true, $false)
              $rule = New-Object System.Security.AccessControl.FileSystemAccessRule($self, 'FullControl', 'ContainerInherit, ObjectInherit', 'None', 'Allow')
              $acl.AddAccessRule($rule)
              Set-Acl -LiteralPath $p -AclObject $acl
            }
            "self $($self.Value)"
            $i = 0
            foreach ($p in (Lines $env:PORELO_PATHS)) {
              $item = Get-Item -LiteralPath $p -Force
              $reparse = [int](($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0)
              $kind = if ($item.PSIsContainer) { 'd' } else { 'f' }
              $acl = Get-Acl -LiteralPath $p
              "path $i $($acl.GetOwner($sidType).Value) $reparse $kind"
              foreach ($r in $acl.GetAccessRules($true, $true, $sidType)) {
                $type = if ($r.AccessControlType -eq 'Allow') { 'A' } else { 'D' }
                "ace $i $type $($r.IdentityReference.Value) $([int]$r.FileSystemRights) $([int]$r.InheritanceFlags) $([int]$r.PropagationFlags)"
              }
              $i++
            }
            """;

    /** Runs the script with the two lists and answers what it printed. */
    public interface Shell {

        /**
         * Runs it.
         *
         * @param protect the directories to protect, each on its own line
         * @param paths the paths to read, each on its own line
         * @return what it printed
         */
        String run(String protect, String paths);
    }

    private final Map<String, String> environment;

    private final Shell shell;

    /** The deepest directory of each call {@link #prepare} checked and passed, until it is used. */
    private final ConcurrentMap<Request, Path> passed = new ConcurrentHashMap<>();

    WindowsPrivateFiles(final Map<String, String> environment, final Shell shell) {
        this.environment = Map.copyOf(environment);
        this.shell = shell;
    }

    /**
     * The real one, running Windows PowerShell from the system's own directory.
     *
     * @param environment the planner's environment
     * @return the files
     */
    public static WindowsPrivateFiles of(final Map<String, String> environment) {
        return new WindowsPrivateFiles(
                environment, (protect, paths) -> powershell(environment, protect, paths));
    }

    @Override
    public boolean windows() {
        return true;
    }

    @Override
    public Path base(final Kind kind, final Map<String, String> environment) {
        return Path.of(drivePath(environment, "LOCALAPPDATA"));
    }

    @Override
    public List<String> steps(final Kind kind) {
        return switch (kind) {
            case DATA -> List.of("porelo-planner");
            case RUNTIME -> List.of("porelo-planner");
            case STATE -> List.of("porelo-planner", "state");
            case CACHE -> List.of("porelo-planner", "cache");
            case LOGS -> List.of("porelo", "logs");
        };
    }

    @Override
    public Path directories(
            final Path base, final boolean create, final Kind kind, final List<String> steps) {
        final Request request = new Request(base, create, kind, steps);
        final Path kept = passed.remove(request);
        if (kept != null) {
            return kept;
        }
        try {
            final Layout layout = lay(request);
            final List<Path> asked = layout.asked();
            final WindowsAccess access;
            try {
                access =
                        WindowsAccess.parse(
                                timed(
                                        "the "
                                                + kind.name().toLowerCase(Locale.ROOT)
                                                + " directories",
                                        () -> shell.run(lines(layout.made()), lines(asked))),
                                asked.size());
            } catch (final RuntimeException e) {
                undo(layout.made());
                throw e;
            }
            check(layout, access, asked::indexOf);
            return layout.directory();
        } catch (final IOException e) {
            throw new UncheckedIOException(base + " cannot be used: " + e.getMessage(), e);
        }
    }

    @Override
    public void prepare(final List<Request> requests) {
        final List<Layout> layouts = new ArrayList<>();
        for (final Request request : requests) {
            try {
                layouts.add(lay(request));
            } catch (final IOException | RuntimeException e) {
                LOG.warn(
                        "The {} directories are checked on their own: {}",
                        name(request),
                        e.getMessage());
            }
        }
        if (layouts.isEmpty()) {
            return;
        }
        // Each path once: the kinds share the directories on their way.
        final Map<Path, Integer> index = new LinkedHashMap<>();
        final Set<Path> made = new LinkedHashSet<>();
        for (final Layout layout : layouts) {
            for (final Path path : layout.asked()) {
                index.putIfAbsent(path, index.size());
            }
            made.addAll(layout.made());
        }
        final WindowsAccess access;
        try {
            access =
                    WindowsAccess.parse(
                            timed(
                                    "the start's directories",
                                    () -> shell.run(lines(made), lines(index.keySet()))),
                            index.size());
        } catch (final RuntimeException e) {
            undo(new ArrayList<>(made));
            LOG.warn("The start's directories are checked one kind at a time: {}", e.getMessage());
            return;
        }
        for (final Layout layout : layouts) {
            try {
                check(layout, access, index::get);
                passed.put(layout.request(), layout.directory());
            } catch (final RuntimeException e) {
                LOG.warn(
                        "The {} directories are checked on their own: {}",
                        name(layout.request()),
                        e.getMessage());
            }
        }
    }

    @Override
    public void forget() {
        passed.clear();
    }

    private static String name(final Request request) {
        return request.kind().name().toLowerCase(Locale.ROOT);
    }

    /**
     * A call's directories as Java finds and makes them, before Windows is asked about them.
     *
     * @param request the call
     * @param above the base and every directory above it, the root first
     * @param own the steps below the base
     * @param made the steps made now, each below the one before
     * @param directory the deepest step
     */
    private record Layout(
            Request request, List<Path> above, List<Path> own, List<Path> made, Path directory) {

        List<Path> asked() {
            final List<Path> asked = new ArrayList<>(above);
            asked.addAll(own);
            return asked;
        }
    }

    /**
     * Checks a base's form, makes what is missing and tells the paths to ask about; a step made
     * here is removed again when a later one fails, so none is left unprotected.
     */
    private static Layout lay(final Request request) throws IOException {
        final Path base = request.base();
        if (!DRIVE_PATH.matcher(base.toString()).matches()
                || !base.isAbsolute()
                || !base.equals(base.normalize())) {
            throw new IllegalStateException(base + " is not a drive's path in its plain form");
        }
        if (request.create() && !Files.exists(base, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(base);
        }
        final Path real = base.toRealPath();
        if (!real.toString().equals(base.toString())) {
            throw new IllegalStateException(base + " is reached through a link, as " + real);
        }
        final List<Path> above = new ArrayList<>();
        for (Path dir = base; dir != null; dir = dir.getParent()) {
            above.addFirst(dir);
        }
        final List<Path> own = new ArrayList<>();
        final List<Path> made = new ArrayList<>();
        Path directory = base;
        try {
            for (final String step : request.steps()) {
                directory = directory.resolve(step);
                own.add(directory);
                try {
                    Files.createDirectory(directory);
                    made.add(directory);
                } catch (final FileAlreadyExistsException exists) {
                    // Its rights are checked below, whoever made it; but nothing is made behind a
                    // link or junction first.
                    requirePlainDirectory(directory);
                }
            }
        } catch (final IOException | RuntimeException e) {
            undo(made);
            throw e;
        }
        return new Layout(request, above, own, made, directory);
    }

    /** Refuses a call's directories that are not the account's as its kind requires. */
    private static void check(
            final Layout layout, final WindowsAccess access, final ToIntFunction<Path> index) {
        final List<Path> above = layout.above();
        for (final Path path : above) {
            access.requireSafeAncestor(path, index.applyAsInt(path));
        }
        // The base too, whatever is asked: an account that may add to %LOCALAPPDATA% could make
        // the planner's directory first.
        final Path base = above.getLast();
        access.requireOwnersAlone(base, index.applyAsInt(base), true, false);
        final List<Path> own = layout.own();
        for (int i = 0; i < own.size(); i++) {
            final Path path = own.get(i);
            if (layout.request().kind() == Kind.LOGS && i < own.size() - 1) {
                // Shared with other parts of Porelo: held only to the rules of a way there.
                access.requireSafeAncestor(path, index.applyAsInt(path));
            } else {
                access.requireOwnersAlone(path, index.applyAsInt(path), true, true);
            }
        }
    }

    /**
     * Removes the steps just made, the deepest first, when their protection was not set: one left
     * would keep only what it inherited, and pass no check again. One not empty is left, and said.
     * A second planner that found one there meanwhile then fails on what it writes in it, never on
     * an unprotected directory.
     */
    private static void undo(final List<Path> made) {
        for (final Path path : made.reversed()) {
            try {
                Files.delete(path);
            } catch (final DirectoryNotEmptyException e) {
                LOG.warn("{} was made but not protected, and is not empty: it stays", path);
            } catch (final IOException e) {
                LOG.warn(
                        "{} was made but not protected, and cannot be removed: {}",
                        path,
                        e.getMessage());
            }
        }
    }

    private static void requirePlainDirectory(final Path directory) throws IOException {
        final BasicFileAttributes attributes =
                Files.readAttributes(
                        directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IllegalStateException(
                    directory + " is not a directory, or is a link or junction");
        }
    }

    @Override
    public void requireExistingDirectory(final Path directory) {
        if (!DRIVE_PATH.matcher(directory.toString()).matches()
                || !directory.isAbsolute()
                || !directory.equals(directory.normalize())) {
            throw new IllegalStateException(directory + " is not an absolute plain drive path");
        }
        final List<Path> paths = new ArrayList<>();
        for (Path path = directory; path != null; path = path.getParent()) {
            paths.addFirst(path);
        }
        final WindowsAccess access =
                WindowsAccess.parse(
                        timed("the items directory", () -> shell.run("", lines(paths))),
                        paths.size());
        for (int at = 0; at < paths.size(); at++) {
            access.requireSafeAncestor(paths.get(at), at);
        }
        access.requireOwnersAlone(directory, paths.size() - 1, true, true);
    }

    @Override
    public void requireItemFile(final Path file) {
        // The directory is checked as the account's alone, passing its control to every entry
        // made in it, so a file in it is checked only for being a plain file, never a reparse
        // point; asking its owner here would start one PowerShell per item at every read.
        requireOwnFile(file, false);
    }

    @Override
    public FileAttribute<?>[] ownerOnlyFile() {
        // The directory's one inherited entry is the file's.
        return new FileAttribute<?>[0];
    }

    @Override
    public void requireOwnFile(final Path file, final boolean alone) {
        try {
            final BasicFileAttributes attributes =
                    Files.readAttributes(
                            file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isOther()) {
                throw new IllegalStateException(file + " is not a file, or is a link");
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(file + " cannot be checked: " + e.getMessage(), e);
        }
    }

    /**
     * A variable naming an absolute path on a drive.
     *
     * @param environment the environment
     * @param name the variable
     * @return its value
     * @throws IllegalStateException when it is unset, relative, a UNC or device path, or names
     *     {@code ..}
     */
    public static String drivePath(final Map<String, String> environment, final String name) {
        String value = environment.getOrDefault(name, "");
        // Windows reads a variable's name in any case; a copied environment may not.
        for (final Map.Entry<String, String> variable : environment.entrySet()) {
            if (value.isEmpty() && variable.getKey().equalsIgnoreCase(name)) {
                value = variable.getValue();
            }
        }
        if (!DRIVE_PATH.matcher(value).matches()
                || List.of(value.split("\\\\", -1)).contains("..")
                || List.of(value.split("\\\\", -1)).contains(".")) {
            throw new IllegalStateException(
                    name + " is '" + value + "', not an absolute path on a drive");
        }
        return value;
    }

    private static String lines(final Collection<Path> paths) {
        final StringBuilder text = new StringBuilder();
        for (final Path path : paths) {
            text.append(path).append('\n');
        }
        return text.toString();
    }

    /**
     * Runs one PowerShell call and logs when it starts and how long it took, so a start that waits
     * on a slow PowerShell shows which call it waits on.
     *
     * @param what what the call checks, in words, never a path
     * @param call the call
     * @return what it printed
     */
    public static String timed(final String what, final Supplier<String> call) {
        LOG.info("PowerShell checks {}", what);
        final long start = System.nanoTime();
        try {
            final String printed = call.get();
            LOG.info("PowerShell checked {} in {} ms", what, millisSince(start));
            return printed;
        } catch (final RuntimeException e) {
            LOG.info("PowerShell failed on {} after {} ms", what, millisSince(start));
            throw e;
        }
    }

    private static long millisSince(final long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private static String powershell(
            final Map<String, String> environment, final String protect, final String paths) {
        final ProcessBuilder builder = powershellCall(environment, protect, paths);
        return answerOnceMore(builder, builder.command().getFirst(), LIMIT);
    }

    /**
     * Runs a program as {@link #answer} does, and once more when it printed nothing at all within
     * the limit: a Windows PowerShell has been seen to hang silently on start where the same call
     * answers in a fraction of a second. A second silence, or any other failure, is refused.
     *
     * @param builder the program, ready to start
     * @param program its name for a refusal
     * @param limit how long each start may take
     * @return its standard output
     */
    static String answerOnceMore(
            final ProcessBuilder builder, final String program, final Duration limit) {
        try {
            return answer(builder, program, limit);
        } catch (final Silent e) {
            LOG.info(
                    "{} printed nothing within {} s; started once more",
                    program,
                    limit.toSeconds());
            try {
                return answer(builder, program, limit);
            } catch (final RuntimeException again) {
                again.addSuppressed(e);
                throw again;
            }
        }
    }

    /** A program that printed nothing at all before its limit ran out. */
    static final class Silent extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        Silent(final String message) {
            super(message);
        }
    }

    /**
     * Prepares the call of Windows PowerShell that runs the script, with the system's modules only.
     *
     * @param environment the planner's environment, naming {@code SystemRoot}
     * @param protect the paths to make the account's alone
     * @param paths the paths to check
     * @return the call, not yet started
     */
    public static ProcessBuilder powershellCall(
            final Map<String, String> environment, final String protect, final String paths) {
        final String system =
                drivePath(environment, "SystemRoot") + "\\System32\\WindowsPowerShell\\v1.0";
        final String program = system + "\\powershell.exe";
        final ProcessBuilder builder =
                new ProcessBuilder(
                        program,
                        "-NoLogo",
                        "-NoProfile",
                        "-NonInteractive",
                        "-ExecutionPolicy",
                        "Bypass",
                        "-EncodedCommand",
                        Base64.getEncoder()
                                .encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE)));
        builder.environment().put("PORELO_PROTECT", protect);
        builder.environment().put("PORELO_PATHS", paths);
        // PowerShell 7's module path, inherited when it started the planner, breaks Set-Acl here;
        // the script imports its modules by $PSHOME, past an all-users copy or a disabled autoload.
        builder.environment().keySet().removeIf(name -> name.equalsIgnoreCase("PSModulePath"));
        builder.environment().put("PSModulePath", system + "\\Modules");
        builder.redirectInput(ProcessBuilder.Redirect.from(Path.of("NUL").toFile()));
        return builder;
    }

    /**
     * Runs a program and answers what it printed, refusing a failure or an answer that is late.
     *
     * @param builder the program, ready to start
     * @param program its name for a refusal
     * @param limit how long it may take
     * @return its standard output
     */
    public static String answer(
            final ProcessBuilder builder, final String program, final Duration limit) {
        final Process process;
        try {
            process = builder.start();
        } catch (final IOException e) {
            throw new UncheckedIOException(program + " cannot be run: " + e.getMessage(), e);
        }
        try {
            // Each output on a platform thread of its own: a reader queued behind busy work, or a
            // virtual thread waiting for a carrier a blocking pipe read holds, leaves the program
            // blocked on a full pipe.
            final Output out = new Output(process.getInputStream(), "output");
            final Output err = new Output(process.getErrorStream(), "errors");
            final long deadline = System.nanoTime() + limit.toNanos();
            if (!process.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
                final String late =
                        program
                                + " did not answer within "
                                + limit.toSeconds()
                                + " s; "
                                + out.sofar()
                                + ", "
                                + err.sofar();
                throw out.silent() && err.silent()
                        ? new Silent(late)
                        : new IllegalStateException(late);
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(
                        program
                                + " exited "
                                + process.exitValue()
                                + ": "
                                + rest(err.task, deadline, program).strip());
            }
            return rest(out.task, deadline, program);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(program + " was interrupted", e);
        } finally {
            process.destroyForcibly();
        }
    }

    private static String rest(
            final FutureTask<String> output, final long deadline, final String program)
            throws InterruptedException {
        try {
            return output.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (final ExecutionException e) {
            throw new IllegalStateException(
                    program + "'s output cannot be read: " + String.valueOf(e.getCause()), e);
        } catch (final TimeoutException e) {
            throw new IllegalStateException(program + "'s output did not end in time", e);
        }
    }

    /**
     * One output of a program, read to its end on a platform thread of its own, with what it read
     * so far counted for a refusal that names how far the program got.
     */
    private static final class Output {

        private final FutureTask<String> task;
        private final AtomicLong bytes = new AtomicLong(-1);
        private final String name;

        Output(final InputStream stream, final String name) {
            this.name = name;
            this.task =
                    new FutureTask<>(
                            () -> {
                                bytes.set(0);
                                final ByteArrayOutputStream read = new ByteArrayOutputStream();
                                try (stream) {
                                    final byte[] buffer = new byte[8192];
                                    int count;
                                    while ((count = stream.read(buffer)) != -1) {
                                        read.write(buffer, 0, count);
                                        bytes.addAndGet(count);
                                    }
                                }
                                return read.toString(StandardCharsets.UTF_8);
                            });
            Thread.ofPlatform().daemon().name("powershell-" + name).start(task);
        }

        /** Whether nothing was read so far. */
        boolean silent() {
            return bytes.get() <= 0;
        }

        /** How much was read so far, in words: never what was read, which may name a path. */
        String sofar() {
            final long read = bytes.get();
            return read < 0 ? "its " + name + " not yet read" : read + " bytes of its " + name;
        }
    }
}
