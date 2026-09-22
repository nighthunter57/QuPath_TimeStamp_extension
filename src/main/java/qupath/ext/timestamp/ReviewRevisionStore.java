package qupath.ext.timestamp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Durable review revisions retained when more audio will replace the machine draft. */
final class ReviewRevisionStore {
    private ReviewRevisionStore() { }

    record Revision(String source, String review, boolean resolved) {
        String text() {
            return JsonParser.parseString(review).getAsJsonObject().get("transcript").getAsString();
        }

        String project(String machine) {
            return !resolved && machine.startsWith(source) ? text() + machine.substring(source.length()) : machine;
        }
    }

    static Revision read(Path path) throws IOException {
        if (!Files.exists(path)) return null;
        try {
            var document = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (document.get("version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported revision");
            var result = new Revision(document.get("source").getAsString(),
                    document.get("review").getAsString(), document.get("resolved").getAsBoolean());
            var review = JsonParser.parseString(result.review()).getAsJsonObject();
            if (review.get("version").getAsInt() != 1 || !review.get("words").isJsonArray()) {
                throw new IllegalArgumentException("Invalid review metadata");
            }
            result.text();
            return result;
        } catch (RuntimeException exception) {
            throw new IOException("The earlier review revision could not be read. Its file has been preserved.", exception);
        }
    }

    static Revision preserve(Path path, String source, String review) throws IOException {
        Revision current = read(path);
        if (current != null && !current.resolved()) {
            throw new IOException("Compare the earlier review before recording more audio.");
        }
        Revision revision = new Revision(source, review, false);
        // Immutable history also preserves revisions across several Record more cycles.
        Path history = path.getParent().resolve("review-history").resolve(UUID.randomUUID() + ".json");
        TimeStamp.atomicWriteString(history, encode(revision));
        TimeStamp.atomicWriteString(path, encode(revision));
        return revision;
    }

    static Revision resolve(Path path, Revision revision) throws IOException {
        Revision resolved = new Revision(revision.source(), revision.review(), true);
        TimeStamp.atomicWriteString(path, encode(resolved));
        return resolved;
    }

    private static String encode(Revision revision) {
        JsonObject document = new JsonObject();
        document.addProperty("version", 1);
        document.addProperty("source", revision.source());
        document.addProperty("review", revision.review());
        document.addProperty("resolved", revision.resolved());
        return document.toString();
    }
}
