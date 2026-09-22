package qupath.ext.timestamp;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Collectors;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;

/** Identifies the image at event capture time, not whichever image is open at save time. */
record RecordedImage(String id, String name, String source) implements Serializable {
    private static final long serialVersionUID = 1L;

    static RecordedImage from(QuPathGUI qupath, QuPathViewer viewer) {
        if (viewer == null || viewer.getImageData() == null) return null;
        var data = viewer.getImageData();
        var server = data.getServer();
        if (server == null) return null;
        var project = qupath == null ? null : qupath.getProject();
        var entry = project == null ? null : project.getEntry(data);
        if (entry != null) {
            return identify("project:" + project.getURI() + "#" + entry.getID(), entry.getImageName());
        }
        String uris = server.getURIs().stream().map(Object::toString).sorted().collect(Collectors.joining("\n"));
        return identify("image:" + uris + "\nserver:" + server.getPath(), server.getMetadata().getName());
    }

    static RecordedImage identify(String source, String name) {
        try {
            String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
            return new RecordedImage(id, name == null || name.isBlank() ? "Unnamed slide" : name, source);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
