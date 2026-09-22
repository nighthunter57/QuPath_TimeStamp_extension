package qupath.ext.timestamp;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.embed.swing.SwingFXUtils;
import javax.imageio.ImageIO;

/** Isolated JavaFX regression harness; pass a disposable output directory. Never opens a microphone. */
public class RecorderUiSmoke {
    static Object get(String name) throws Exception {
        var field = TimeStamp.class.getDeclaredField(name); field.setAccessible(true); return field.get(null);
    }
    static void set(String name, Object value) throws Exception {
        var field = TimeStamp.class.getDeclaredField(name); field.setAccessible(true); field.set(null, value);
    }
    static Object call(String name) throws Exception {
        var method = TimeStamp.class.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(null);
    }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static <T> T fx(Callable<T> body) throws Exception {
        var task = new FutureTask<>(body); Platform.runLater(task); return task.get(20, TimeUnit.SECONDS);
    }
    static void snapshot(Parent root, Path directory, String name, int width) throws Exception {
        root.resize(width, 800); root.applyCss(); root.layout();
        ImageIO.write(SwingFXUtils.fromFXImage(root.snapshot(null, null), null), "png", directory.resolve(name + ".png").toFile());
    }
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]).toAbsolutePath(); Files.createDirectories(directory);
        System.setProperty("user.home", directory.toString());
        System.setProperty("java.util.prefs.userRoot", directory.resolve("prefs").toString());
        Platform.startup(() -> {});
        try {
            Parent root = fx(() -> {
                Parent pane = (Parent) call("createLiveEventMonitorPane");
                ((javafx.animation.Timeline) get("transcriptRefreshTimeline")).stop();
                new Scene(pane, 420, 800); return pane;
            });
            Path session = directory.resolve("session"); Files.createDirectories(session.resolve("video"));
            File transcript = fx(() -> {
                var method = TimeStamp.class.getDeclaredMethod("buildTranscriptFile", File.class);
                method.setAccessible(true); return (File) method.invoke(null, session.toFile());
            });
            String text = "[2026-09-14T10:00:00.000] The margin is negative.\n";
            Files.writeString(transcript.toPath(), text);
            int start = text.indexOf("negative");
            String metadata = TimeStamp.reviewedTranscriptJson(text,
                List.of(new TimeStamp.ReviewWord("negative", start, start + 8, 1000, 1500, .35, true)));
            Path metadataPath = transcript.toPath().resolveSibling(transcript.getName().replace(".txt", "_review.json"));
            Files.writeString(metadataPath, metadata);
            fx(() -> {
                snapshot(root, directory, "ready", 420);
                set("transcriptSessionDir", session.toFile()); set("transcriptFile", transcript);
                set("recordingWorkflowState", TimeStamp.RecordingWorkflowState.UNSAVED_REVIEW);
                call("refreshTranscriptContents"); call("updateLiveEventMonitorControls");
                check(((SplitPane) get("transcriptEventSplitPane")).getItems().size() == 2, "Actions must stay visible");
                ((ToggleButton) get("eventsToggleButton")).fire();
                check(((SplitPane) get("transcriptEventSplitPane")).getItems().size() == 2, "View all hid actions");
                call("selectNextUncertainWord"); call("markSelectedWordChecked");
                ((TextField) get("wordCorrectionField")).setText("clear"); call("applySelectedWordCorrection");
                check(((TextArea) get("liveTranscriptTextArea")).getText().contains("clear"), "Correction lost");
                ((Button) get("reviewUndoButton")).fire();
                check(((TextArea) get("liveTranscriptTextArea")).getText().contains("negative"), "Undo failed");
                ((Button) get("reviewRedoButton")).fire();
                check(((TextArea) get("liveTranscriptTextArea")).getText().contains("clear"), "Redo failed");
                snapshot(root, directory, "review-narrow", 320);
                snapshot(root, directory, "review-wide", 900);
                var styles = qupath.lib.gui.prefs.QuPathStyleManager.availableStylesProperty();
                var originalStyle = qupath.lib.gui.prefs.QuPathStyleManager.selectedStyleProperty().get();
                var darkStyle = styles.stream().filter(style -> style.getColorScheme() == javafx.application.ColorScheme.DARK).findFirst();
                if (darkStyle.isPresent()) {
                    qupath.lib.gui.prefs.QuPathStyleManager.selectedStyleProperty().set(darkStyle.get());
                    // This detached snapshot scene has no QuPath window. Attach
                    // the real theme explicitly so snapshots test its CSS too.
                    String darkCss = qupath.lib.gui.prefs.QuPathStyleManager.class.getResource("/css/dark.css").toExternalForm();
                    root.getScene().getStylesheets().add(darkCss);
                    call("styleCaptionConfidence");
                    snapshot(root, directory, "review-dark-narrow", 320);
                    snapshot(root, directory, "review-dark", 420);
                    snapshot(root, directory, "review-dark-wide", 900);
                    root.getScene().getStylesheets().remove(darkCss);
                    qupath.lib.gui.prefs.QuPathStyleManager.selectedStyleProperty().set(originalStyle);
                }
                call("checkpointWorkingSession"); return null;
            });
            for (int i = 0; i < 200 && fx(() -> (boolean) get("recoveryCheckpointInProgress")); i++) Thread.sleep(20);
            check(Files.isRegularFile(session.resolve(".timestamp-review.json")), "Review checkpoint missing");
            fx(() -> {
                ((TextArea) get("liveTranscriptTextArea")).clear();
                var recover = TimeStamp.class.getDeclaredMethod("recoverWorkingSession", File.class);
                recover.setAccessible(true); recover.invoke(null, session.toFile());
                check(((TextArea) get("liveTranscriptTextArea")).getText().contains("clear"), "Crash recovery lost correction");
                return null;
            });
            fx(() -> {
                check(((TextArea) get("liveTranscriptTextArea")).getText().contains("clear"), "Refresh overwrote recovery");
                check((boolean) call("preserveReviewBeforeRecording"), "Review revision was not preserved before resume");
                set("transcriptLastModified", -1L); set("transcriptLastSize", -1L);
                call("refreshTranscriptContents");
                check(((TextArea) get("liveTranscriptTextArea")).getText().contains("clear"), "Resume refresh lost correction");
                String expanded = text + "[2026-09-14T10:00:04.000] More recorded speech.\n";
                Files.writeString(transcript.toPath(), expanded);
                set("transcriptLastModified", -1L); call("refreshTranscriptContents");
                String projected = ((TextArea) get("liveTranscriptTextArea")).getText();
                check(projected.contains("clear") && projected.contains("More recorded speech"), "Appending speech lost reviewed text");
                String revised = "[2026-09-14T10:00:02.000] The margin is negative. More recorded speech.\n";
                Files.writeString(transcript.toPath(), revised);
                set("transcriptLastModified", -1L); call("refreshTranscriptContents");
                check(((Button) get("comparePreviousReviewButton")).isVisible(), "Changed final draft hid preserved review");
                Path previous = transcript.toPath().resolveSibling(transcript.getName().replace(".txt", "_previous_review.json"));
                check(ReviewRevisionStore.read(previous).text().contains("clear"), "Earlier correction was not durable");
                call("loadPreviousReviewRevision");
                check((boolean) call("hasPreviousReview"), "Pending review was lost on reload");
                var complete = TimeStamp.class.getDeclaredMethod("completeReviewComparison", String.class);
                complete.setAccessible(true);
                check((boolean) complete.invoke(null, revised.replace("negative", "clear")), "Compared review did not persist");
                check(ReviewRevisionStore.read(previous).resolved(), "Comparison was not resolved");
                check(Files.readString(session.resolve(".timestamp-review.json")).contains("clear"), "Compared review recovery was not saved");
                set("transcriptLastModified", -1L); call("refreshTranscriptContents");
                check(((TextArea) get("liveTranscriptTextArea")).getText().contains("clear"), "Forced refresh lost compared review");
                snapshot(root, directory, "review-after-resume", 420);
                var commands = new ByteArrayOutputStream();
                Process process = new Process() {
                    public OutputStream getOutputStream() { return commands; }
                    public InputStream getInputStream() { return InputStream.nullInputStream(); }
                    public InputStream getErrorStream() { return InputStream.nullInputStream(); }
                    public int waitFor() { return 0; }
                    public int exitValue() { throw new IllegalThreadStateException(); }
                    public void destroy() { throw new AssertionError("Pause destroyed process"); }
                    public boolean isAlive() { return true; }
                };
                set("transcriptProcess", process);
                set("recordingWorkflowState", TimeStamp.RecordingWorkflowState.RECORDING);
                ((javafx.beans.property.BooleanProperty) get("recordEvents")).set(true);
                call("updateLiveEventMonitorControls"); snapshot(root, directory, "recording", 420);
                root.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,
                        "", "", javafx.scene.input.KeyCode.R, false, true, true, false));
                check(commands.toString().equals("PAUSE\n"), "Pause was not a control command");
                set("captureControlPending", false);
                set("recordingWorkflowState", TimeStamp.RecordingWorkflowState.PAUSED);
                ((javafx.beans.property.BooleanProperty) get("recordEvents")).set(false);
                call("updateLiveEventMonitorControls"); snapshot(root, directory, "paused", 420);
                check(!((Button) get("recordingPrimaryButton")).isDisabled(), "Resident helper disabled Resume");
                ((Button) get("recordingPrimaryButton")).fire();
                check(commands.toString().equals("PAUSE\nRESUME\n"), "Resume restarted helper");
                check(((Button) get("recordingDoneButton")).isVisible(), "Done disappeared");
                set("transcriptProcess", null); set("captureControlPending", false);
                return null;
            });
            fx(() -> {
                set("recordingWorkflowState", TimeStamp.RecordingWorkflowState.RECORDING);
                ((javafx.beans.property.BooleanProperty) get("recordEvents")).set(true);
                call("updateLiveEventMonitorControls");
                ((TextArea) get("liveTranscriptTextArea")).setText(text.repeat(100));
                root.applyCss(); root.layout(); return null;
            });
            fx(() -> {
                var scroll = (ScrollPane) get("liveCaptionScrollPane");
                scroll.setVvalue(.35);
                root.applyCss(); root.layout();
                ((TextArea) get("liveTranscriptTextArea")).appendText(text);
                return null;
            });
            fx(() -> {
                var scroll = (ScrollPane) get("liveCaptionScrollPane");
                root.applyCss(); root.layout();
                check(scroll.getVvalue() == scroll.getVmax(), "New committed words did not automatically follow live");
                scroll.setVvalue(.2);
                var partial = TimeStamp.class.getDeclaredMethod("updateTranscriptPartial", String.class);
                partial.setAccessible(true);
                partial.invoke(null, "New provisional words ".repeat(50));
                return null;
            });
            fx(() -> {
                root.applyCss(); root.layout();
                var scroll = (ScrollPane) get("liveCaptionScrollPane");
                check(scroll.getVvalue() == scroll.getVmax(), "New provisional words did not automatically follow live");
                ((javafx.beans.property.BooleanProperty) get("recordEvents")).set(false);
                scroll.setVvalue(.3);
                call("followCaptionTail");
                return null;
            });
            fx(() -> {
                check(Math.abs(((ScrollPane) get("liveCaptionScrollPane")).getVvalue() - .3) < .001,
                        "Review position moved while recording was stopped");
                return null;
            });
            System.out.println("UI layouts, visible actions, corrections, Undo/Redo, recovery, resident Pause/Resume and automatic live scrolling passed.");
        } finally {
            Platform.exit();
        }
    }
}
