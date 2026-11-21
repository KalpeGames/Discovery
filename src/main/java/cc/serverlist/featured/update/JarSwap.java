package cc.serverlist.featured.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Replaces the mod's jar with a downloaded one. Called from the game's shutdown hook, and when
 * that can't delete the running jar (Windows), run as a separate process that waits for the game
 * to exit and then does the same.
 *
 * <p>It runs from a copy of this one class file, with the jar it replaces already gone, so it
 * must not touch any other class in the mod or anything outside {@code java.base}. That includes
 * inner classes: an anonymous class is a second class file that isn't copied.
 */
public final class JarSwap {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private JarSwap() {
    }

    /** {@code <pid> <old jar> <staged jar> <target jar> <log>} */
    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            System.err.println("usage: JarSwap <pid> <old> <staged> <target> <log>");
            System.exit(2);
        }
        long pid = Long.parseLong(args[0]);
        Path old = Path.of(args[1]);
        Path staged = Path.of(args[2]);
        Path target = Path.of(args[3]);
        Path log = Path.of(args[4]);

        Optional<ProcessHandle> game = ProcessHandle.of(pid);
        if (game.isPresent()) {
            log(log, "Installer waiting for the game (pid " + pid + ") to exit");
            try {
                game.get().onExit().get(5, TimeUnit.MINUTES);
            } catch (Exception e) {
                log(log, "Game still running after 5 minutes; giving up, next launch retries");
                return;
            }
        }
        // The process being gone doesn't mean every handle on the jar is: antivirus and the
        // launcher can hold it for a moment longer. Keep trying for a minute.
        for (int attempt = 0; attempt < 120; attempt++) {
            // Say it's locked once, not twice a second for a minute.
            if (trySwap(old, staged, target, log, attempt == 0)) {
                return;
            }
            Thread.sleep(500);
        }
        log(log, "Couldn't replace " + old.getFileName() + " within a minute; next launch retries");
    }

    /**
     * One attempt, ordered so that {@code mods/} always holds exactly one copy of the mod:
     *
     * <ol>
     *   <li>copy the new jar in under a name Fabric ignores ({@code .jar.pending});</li>
     *   <li>delete the old jar; if it's locked, remove the copy and report failure;</li>
     *   <li>rename the copy to its real name, a same-folder rename.</li>
     * </ol>
     *
     * The staged file is only deleted once the new jar is in place.
     */
    static boolean trySwap(Path old, Path staged, Path target, Path log, boolean reportLocked) {
        if (!Files.isRegularFile(staged)) {
            log(log, "Nothing staged at " + staged + "; nothing to do");
            return true;
        }
        Path incoming = target.resolveSibling(target.getFileName() + ".pending");
        try {
            Files.copy(staged, incoming, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log(log, "Couldn't copy the new jar into place: " + e);
            return false;
        }
        try {
            Files.deleteIfExists(old);
        } catch (IOException e) {
            quietDelete(incoming);
            if (reportLocked) {
                log(log, "Old jar is locked (" + e.getClass().getSimpleName() + "), waiting for it to be released");
            }
            return false;
        }
        try {
            Files.move(incoming, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // The old jar is already gone, so the new one has to land somehow.
            try {
                Files.copy(staged, target, StandardCopyOption.REPLACE_EXISTING);
                quietDelete(incoming);
            } catch (IOException again) {
                log(log, "Old jar removed but the new one couldn't be placed: " + again + " (staged copy kept at " + staged + ")");
                return false;
            }
        }
        quietDelete(staged);
        log(log, "Installed " + target.getFileName() + " (replaced " + old.getFileName() + ")");
        return true;
    }

    static void log(Path log, String message) {
        String line = LocalDateTime.now().format(STAMP) + "  " + message + System.lineSeparator();
        try {
            Files.createDirectories(log.getParent());
            Files.writeString(log, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.print(line);
        }
    }

    private static void quietDelete(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            // A leftover .pending file is ignored by Fabric and overwritten next time.
        }
    }
}
