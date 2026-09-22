package qupath.ext.timestamp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Keep exports separate from their working source, including filesystem aliases. */
final class ExportSafety {
    private ExportSafety() { }

    static Path resolved(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (Files.exists(absolute)) return absolute.toRealPath();
        if (Files.isSymbolicLink(absolute)) throw new IOException("Destination contains a broken symbolic link");
        Path parent = absolute.getParent();
        return parent == null ? absolute : resolved(parent).resolve(absolute.getFileName());
    }

    static void requireSeparateDestination(Path sourceDirectory, Path destination) throws IOException {
        Path source = resolved(sourceDirectory);
        Path target = resolved(destination);
        if (target.startsWith(source) || source.startsWith(target)) {
            throw new IOException("Choose a save location outside the working recording folder. " +
                    "The original recording must remain separate from its exported copy.");
        }
    }

    static void requireDifferentFiles(Path source, Path destination) throws IOException {
        if (resolved(source).equals(resolved(destination)) ||
                (Files.exists(source) && Files.exists(destination) && Files.isSameFile(source, destination))) {
            throw new IOException("The export destination refers to an original recording file. Choose another location.");
        }
    }
}
