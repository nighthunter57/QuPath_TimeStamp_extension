package qupath.ext.timestamp;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Checks only manifest-declared artifacts; unrelated files are never included. */
public final class SessionIntegrity {
    private SessionIntegrity() { }

    static String addChecksums(String manifest, Path root) throws IOException {
        JsonObject document = JsonParser.parseString(manifest).getAsJsonObject();
        visit(document, root, true);
        return new GsonBuilder().setPrettyPrinting().create().toJson(document) + "\n";
    }

    static void verify(String manifest, Path root) throws IOException {
        JsonObject document = JsonParser.parseString(manifest).getAsJsonObject();
        if (document.get("schemaVersion").getAsInt() != 2) throw new IOException("Unsupported session schema");
        visit(document, root, false);
    }

    private static void visit(JsonObject object, Path root, boolean creating) throws IOException {
        if (!object.has("exists") || object.get("exists").getAsBoolean()) {
            for (String key : List.of("path", "csvPath")) {
                if (!object.has(key)) continue;
                Path file = root.resolve(object.get(key).getAsString()).normalize();
                if (!file.startsWith(root.normalize()) || !Files.isRegularFile(file) ||
                        !file.toRealPath().startsWith(root.toRealPath())) {
                    throw new IOException("Missing artifact or artifact outside session folder: " + key);
                }
                if (key.equals("path") && object.has("bytes") &&
                        Files.size(file) != object.get("bytes").getAsLong()) {
                    throw new IOException("Saved artifact size does not match manifest");
                }
                String hashKey = key.equals("path") ? "sha256" : "csvSha256";
                String hash = sha256(file);
                if (creating) object.addProperty(hashKey, hash);
                else if (!object.has(hashKey) || !hash.equals(object.get(hashKey).getAsString())) {
                    throw new IOException("Saved artifact checksum missing or mismatched");
                }
            }
        }
        for (JsonElement value : object.asMap().values()) {
            if (value.isJsonObject()) visit(value.getAsJsonObject(), root, creating);
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Read-only integrity check of an exported session; never opens a microphone. */
    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("Pass a saved recording manifest path");
        Path manifest = Path.of(args[0]).toAbsolutePath();
        verify(Files.readString(manifest), manifest.getParent());
        System.out.println("All manifest-declared artifacts match their saved checksums.");
    }
}
