package org.fuin.porelo.planner.store.git;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

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
final class WindowsPrivateFiles implements PrivateFiles {

    /** A drive's absolute path, never a UNC, device or {@code \\?\} path. */
    private static final Pattern DRIVE_PATH = Pattern.compile("[A-Za-z]:\\\\[^\\x00-\\x1f]*");

    private static final Duration LIMIT = Duration.ofSeconds(60);

    /**
     * Protects each directory named in {@code PORELO_PROTECT}, then prints the account and each
     * path of {@code PORELO_PATHS} in {@link WindowsAccess}'s form. The paths come through the
     * environment, so none is ever read as code.
     */
    static final String SCRIPT =
            """
            $ErrorActionPreference = 'Stop'
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
    interface Shell {

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
    static WindowsPrivateFiles of(final Map<String, String> environment) {
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
            case RUNTIME -> List.of("porelo-planner");
            case STATE -> List.of("porelo-planner", "state");
            case CACHE -> List.of("porelo-planner", "cache");
            case LOGS -> List.of("porelo", "logs");
        };
    }

    @Override
    public Path directories(
            final Path base, final boolean create, final Kind kind, final List<String> steps) {
        if (!DRIVE_PATH.matcher(base.toString()).matches()
                || !base.isAbsolute()
                || !base.equals(base.normalize())) {
            throw new IllegalStateException(base + " is not a drive's path in its plain form");
        }
        try {
            if (create && !Files.exists(base, LinkOption.NOFOLLOW_LINKS)) {
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
            for (final String step : steps) {
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
            final List<Path> asked = new ArrayList<>(above);
            asked.addAll(own);
            final WindowsAccess access =
                    WindowsAccess.parse(shell.run(lines(made), lines(asked)), asked.size());
            for (int i = 0; i < above.size(); i++) {
                access.requireSafeAncestor(above.get(i), i);
            }
            // The base too, whatever is asked: an account that may add to %LOCALAPPDATA% could make
            // the planner's directory first.
            access.requireOwnersAlone(base, above.size() - 1, true, false);
            for (int i = 0; i < own.size(); i++) {
                if (kind == Kind.LOGS && i < own.size() - 1) {
                    // Shared with other parts of Porelo: held only to the rules of a way there.
                    access.requireSafeAncestor(own.get(i), above.size() + i);
                } else {
                    access.requireOwnersAlone(own.get(i), above.size() + i, true, true);
                }
            }
            return directory;
        } catch (final IOException e) {
            throw new UncheckedIOException(base + " cannot be used: " + e.getMessage(), e);
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
    static String drivePath(final Map<String, String> environment, final String name) {
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

    private static String lines(final List<Path> paths) {
        final StringBuilder text = new StringBuilder();
        for (final Path path : paths) {
            text.append(path).append('\n');
        }
        return text.toString();
    }

    private static String powershell(
            final Map<String, String> environment, final String protect, final String paths) {
        final String program =
                drivePath(environment, "SystemRoot")
                        + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";
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
        builder.redirectInput(ProcessBuilder.Redirect.from(Path.of("NUL").toFile()));
        return answer(builder, program, LIMIT);
    }

    /**
     * Runs a program and answers what it printed, refusing a failure or an answer that is late.
     *
     * @param builder the program, ready to start
     * @param program its name for a refusal
     * @param limit how long it may take
     * @return its standard output
     */
    static String answer(final ProcessBuilder builder, final String program, final Duration limit) {
        final Process process;
        try {
            process = builder.start();
        } catch (final IOException e) {
            throw new UncheckedIOException(program + " cannot be run: " + e.getMessage(), e);
        }
        try {
            // Each output on a thread of its own: a reader queued behind busy work leaves the
            // program blocked on a full pipe.
            final FutureTask<String> out = read(process.getInputStream());
            final FutureTask<String> err = read(process.getErrorStream());
            final long deadline = System.nanoTime() + limit.toNanos();
            if (!process.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(
                        program + " did not answer within " + limit.toSeconds() + " s");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(
                        program
                                + " exited "
                                + process.exitValue()
                                + ": "
                                + rest(err, deadline, program).strip());
            }
            return rest(out, deadline, program);
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

    private static FutureTask<String> read(final InputStream stream) {
        final FutureTask<String> task =
                new FutureTask<>(
                        () -> {
                            try (stream) {
                                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                            }
                        });
        Thread.ofVirtual().start(task);
        return task;
    }
}
