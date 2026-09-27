package qupath.ext.timestamp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Consistency checks for a Python-published transcript generation. */
final class TranscriptArtifacts {
    private TranscriptArtifacts() { }

    static Path companion(Path transcript, String suffix) {
        String name = transcript.getFileName().toString();
        return transcript.resolveSibling(name.substring(0, name.length() - 4) + suffix);
    }

    static boolean pending(Path transcript) {
        return Files.exists(companion(transcript, "_generation_pending.json"));
    }

    static boolean consistent(Path transcript) {
        if (pending(transcript)) return false;
        Path record = companion(transcript, "_generation.json");
        if (!Files.exists(record)) return true; // Legacy generations predate this marker.
        try {
            JsonObject document = JsonParser.parseString(Files.readString(record)).getAsJsonObject();
            JsonObject files = document.getAsJsonObject("files");
            if (document.get("version").getAsInt() != 1 ||
                    !files.keySet().equals(Set.of(".txt", "_segments.csv", "_words.csv", "_review.json"))) return false;
            for (String suffix : files.keySet()) {
                JsonObject descriptor = new JsonObject();
                descriptor.addProperty("sha256", files.get(suffix).getAsString());
                SessionIntegrity.verifyCopy(companion(transcript, suffix), descriptor);
            }
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    static String qualityWarning(Path transcript) {
        Path quality = companion(transcript, "_capture_quality.json");
        if (!Files.exists(quality)) return "";
        try {
            var document = JsonParser.parseString(Files.readString(quality)).getAsJsonObject();
            int count = document.get("count").getAsInt();
            return count > 0 ? "Recording needs review: " + count +
                    " audio or clock incident(s). Missing speech cannot be recovered; check the audio." : "";
        } catch (IOException | RuntimeException exception) {
            return "Recording quality history could not be read. Check the audio before using this transcript.";
        }
    }
}
