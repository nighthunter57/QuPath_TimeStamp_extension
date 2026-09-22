package qupath.ext.timestamp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Versioned review checkpoints, bound to their exact machine-text source. */
final class ReviewRecovery {
    private ReviewRecovery() { }

    static String encode(String source, String review) {
        var document = new JsonObject();
        document.addProperty("version", 1);
        document.addProperty("source", source);
        document.addProperty("review", review);
        return document.toString();
    }

    static String matchingReview(String checkpoint, String source) {
        try {
            var document = JsonParser.parseString(checkpoint).getAsJsonObject();
            if (document.get("version").getAsInt() != 1 ||
                    !source.equals(document.get("source").getAsString())) return null;
            var review = document.get("review").getAsString();
            var parsed = JsonParser.parseString(review).getAsJsonObject();
            if (parsed.get("version").getAsInt() != 1 || !parsed.has("transcript") || !parsed.has("words")) return null;
            return review;
        } catch (RuntimeException exception) {
            return null;
        }
    }
}
