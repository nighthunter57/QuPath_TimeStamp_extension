package qupath.ext.timestamp;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.WindowEvent;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.fx.dialogs.FileChoosers;
import qupath.fx.prefs.controlsfx.PropertyItemBuilder;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.overlays.PathOverlay;
import qupath.lib.gui.viewer.tools.PathTool;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyEvent;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyEvent.HierarchyEventType;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener;
import qupath.lib.regions.ImageRegion;
import qupath.lib.roi.interfaces.ROI;

import java.awt.*;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * QuPath extension for recording timestamped events (clicks, zooms, pans).
 * Shows the current time and last event on screen.
 */
public class TimeStamp implements QuPathExtension {
    
    private static final Logger logger = LoggerFactory.getLogger(TimeStamp.class);
    
    private static final String EXTENSION_NAME = "TimeStamp Extension";
    private static final String DEFAULT_EXTENSION_VERSION = "0.1.0";
    private static final String EXTENSION_DESCRIPTION =
            "Record clinical dictation, transcripts, and timestamped QuPath interactions";
    private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.6.0");
    private static final double GESTURE_END_DELAY_MS = 250.0;
    private static final double TRANSCRIPT_REFRESH_INTERVAL_SECONDS = 1.0;
    private static final double OVERLAY_REFRESH_INTERVAL_SECONDS = 0.50;
    private static final double RECOVERY_CHECKPOINT_INTERVAL_SECONDS = 2.0;
    private static final String DEVELOPMENT_RELOAD_GUARD_ENV = "TIMESTAMP_DEV_GUARD_FILE";
    private static final double DEFAULT_OVERLAY_FONT_SIZE = 14.0;
    private static final double LEGACY_OVERLAY_FONT_SIZE = 24.0;
    private static final long OVERLAY_EVENT_VISIBLE_MILLIS = 4_000L;
    private static final int MAX_LIVE_MONITOR_EVENTS = 200;
    private static final int MAX_TRANSCRIPT_RENDER_WORDS_PER_LINE = 80;
    private static final double WIDE_PANEL_MINIMUM_WIDTH = 720.0;
    private static final double DEFAULT_PANEL_DIVIDER_POSITION = 0.5;
    private static final double RECORDING_PANEL_DIVIDER_POSITION = 0.75;
    private static final String TRANSCRIPT_RUNAWAY_MARKER = "[decode error suppressed]";
    private static final String DEFAULT_TRANSCRIPT_MODEL = "large-v3";
    private static final String DEFAULT_TRANSCRIPT_LANGUAGE = "en";
    private static final String DEFAULT_TRANSCRIPT_CHUNK_SECONDS = "10.0";
    private static final String DEFAULT_TRANSCRIPT_COMPUTE_TYPE = "int8_float32";
    private static final String DEFAULT_TRANSCRIPT_BEAM_SIZE = "8";
    private static final String DEFAULT_TRANSCRIPT_BEST_OF = "8";
    private static final String DEFAULT_TRANSCRIPT_DEVICE = "";
    private static final String DEFAULT_TRANSCRIPT_HOTWORDS =
            "Gleason, mitotic figures, pleomorphism, Ki-67, HER2, immunohistochemistry, " +
                    "lymphovascular invasion, perineural invasion, adenocarcinoma, " +
                    "squamous cell carcinoma, margin, malignancy";
    private static final String TRANSCRIPT_FINALIZATION_PENDING = "pending";
    private static final String TRANSCRIPT_FINALIZATION_FAILED = "failed";
    private static final List<String> AVAILABLE_TRANSCRIPT_LANGUAGES = Arrays.asList(
            "auto - Auto detect",
            "en - English",
            "es - Spanish",
            "fr - French",
            "de - German",
            "it - Italian",
            "pt - Portuguese",
            "nl - Dutch",
            "pl - Polish",
            "tr - Turkish",
            "ru - Russian",
            "uk - Ukrainian",
            "ar - Arabic",
            "hi - Hindi",
            "zh - Chinese",
            "ja - Japanese",
            "ko - Korean",
            "vi - Vietnamese",
            "th - Thai",
            "id - Indonesian",
            "ms - Malay",
            "tl - Tagalog");

    private record TranscriptInputDeviceOption(String value, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    record ClinicalTranscriptSettings(String finalModel, String liveContextSeconds,
                                      String computeType, String beamSize,
                                      String bestOf, boolean previousText) {
    }

    private record EventEntry(EventRecord source, String elapsed, String type, String details) {
    }

    record TranscriptWindow(int start, int end, Instant startTime, Instant endTime) {
    }

    static ClinicalTranscriptSettings clinicalTranscriptSettings() {
        return new ClinicalTranscriptSettings(
                DEFAULT_TRANSCRIPT_MODEL,
                DEFAULT_TRANSCRIPT_CHUNK_SECONDS,
                DEFAULT_TRANSCRIPT_COMPUTE_TYPE,
                DEFAULT_TRANSCRIPT_BEAM_SIZE,
                DEFAULT_TRANSCRIPT_BEST_OF,
                true);
    }

    static String extensionVersion() {
        String implementationVersion = TimeStamp.class.getPackage().getImplementationVersion();
        return defaultIfBlank(implementationVersion, DEFAULT_EXTENSION_VERSION);
    }

    enum TranscriptMessageType {
        DEVICE(2),
        AUDIO_CHECK_READY(0),
        AUDIO_CHECK_RESULT(2),
        AUDIO_LEVEL(2),
        AUDIO_CLIPPING(1),
        AUDIO_SILENT(1),
        AUDIO_RECOVERED(0),
        TRANSCRIPT_READY(0),
        RECORDING_ORIGIN(1),
        LIVE_MODEL_READY(1),
        TRANSCRIPT_UPDATED(0),
        TRANSCRIPT_PARTIAL(1),
        FINALIZE_PROGRESS(2),
        FINALIZATION_RESULT(1),
        LOG(-1),
        MALFORMED(-1);

        private final int fieldCount;

        TranscriptMessageType(int fieldCount) {
            this.fieldCount = fieldCount;
        }
    }

    record TranscriptMessage(TranscriptMessageType type, List<String> fields, String raw) {
    }

    static TranscriptMessage parseTranscriptMessage(String line) {
        String raw = line == null ? "" : line;
        String[] parts = raw.split("\t", -1);
        TranscriptMessageType type;
        try {
            type = TranscriptMessageType.valueOf(parts[0]);
        } catch (IllegalArgumentException e) {
            return new TranscriptMessage(TranscriptMessageType.LOG, List.of(), raw);
        }
        List<String> fields = Arrays.asList(parts).subList(1, parts.length);
        if (fields.size() != type.fieldCount) {
            return new TranscriptMessage(TranscriptMessageType.MALFORMED, fields, raw);
        }
        try {
            switch (type) {
                case AUDIO_LEVEL, AUDIO_CHECK_RESULT, AUDIO_CLIPPING ->
                        Double.parseDouble(fields.get(0));
                case AUDIO_SILENT -> Double.parseDouble(fields.get(0));
                case RECORDING_ORIGIN -> Instant.parse(fields.get(0));
                case FINALIZE_PROGRESS -> {
                    Double.parseDouble(fields.get(0));
                    Double.parseDouble(fields.get(1));
                }
                default -> { }
            }
        } catch (RuntimeException e) {
            return new TranscriptMessage(TranscriptMessageType.MALFORMED, fields, raw);
        }
        return new TranscriptMessage(type, List.copyOf(fields), raw);
    }

    private enum TranscriptStartMode {
        NEW_TAKE,
        RESUME,
        CANCEL
    }

    enum RecordingWorkflowState {
        READY,
        STARTING,
        RECORDING,
        PAUSED,
        FINALIZING,
        UNSAVED_REVIEW,
        SAVING,
        SAVED,
        ERROR
    }

    enum RecordingPrimaryAction {
        START,
        PAUSE,
        RESUME,
        SAVE,
        WAIT
    }

    private enum TranscriptProcessPurpose {
        NONE,
        CAPTURE,
        FINALIZE
    }

    private enum TranscriptStopIntent {
        NONE,
        PAUSE,
        DONE
    }
    
    private boolean isInstalled = false;
    private static QuPathGUI qupathGui;
    private static final String TIMESTAMP_CATEGORY = "TimeStamp";
    
    // Event logs to store timestamped events
    private static final List<EventRecord> eventLog = new ArrayList<>();
    private static final List<EventRecord> mouseMoveLog = new ArrayList<>();
    private static final DateTimeFormatter formatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
    private static final DateTimeFormatter overlayTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss");
    private static Tab liveEventTab;
    private static TableView<EventEntry> liveEventTable;
    private static Label liveEventCountLabel;
    private static TextArea liveTranscriptTextArea;
    private static Button recordingPrimaryButton;
    private static Button recordingDoneButton;
    private static Button recordMoreButton;
    private static Button transcriptSettingsButton;
    private static MenuButton transcriptMoreButton;
    private static MenuItem panelClearEventsMenuItem;
    private static MenuItem panelExportTranscriptMenuItem;
    private static FlowPane transcriptSecondaryControls;
    private static HBox transcriptMicrophoneRow;
    private static SplitPane transcriptEventSplitPane;
    private static MenuItem clearLogMenuItem;
    private static MenuItem transcriptSettingsMenuItem;
    private static Label recordingStateDotLabel;
    private static Label recordingStatusLabel;
    private static Label transcriptAudioLevelLabel;
    private static Label transcriptStatusLabel;
    private static Label transcriptHelpLabel;
    private static Label transcriptPartialLabel;
    private static ProgressBar transcriptAudioLevelBar;
    private static ProgressBar transcriptFinalizationProgressBar;
    private static Label transcriptFinalizationProgressLabel;
    private static File transcriptSessionDir;
    private static File lastSavedSessionDir;
    private static File transcriptFile;
    private static boolean transcriptCaptureStarted = false;
    private static boolean recordingSessionSaved = false;
    private static boolean clearLogsWhenRecordingStarts = false;
    private static volatile boolean transcriptStartPending = false;
    private static volatile boolean transcriptStopInProgress = false;
    private static volatile boolean transcriptResumePending = false;
    private static volatile TranscriptProcessPurpose transcriptProcessPurpose = TranscriptProcessPurpose.NONE;
    private static volatile TranscriptStopIntent transcriptStopIntent = TranscriptStopIntent.NONE;
    private static volatile String transcriptFinalizationResult = TRANSCRIPT_FINALIZATION_PENDING;
    private static volatile int transcriptLastExitCode = -1;
    private static long transcriptLastModified = -1L;
    private static long transcriptLastSize = -1L;
    private static String transcriptLastContents = "";
    private static Timeline transcriptRefreshTimeline;
    private static Timeline overlayRefreshTimeline;
    private static Timeline recoveryCheckpointTimeline;
    private static Process transcriptProcess;
    private static volatile Thread transcriptOutputThread;
    private static volatile RecordingWorkflowState recordingWorkflowState = RecordingWorkflowState.READY;
    private static volatile boolean recordingSessionDirty = false;
    private static volatile boolean recoveryCheckpointInProgress = false;
    private static boolean closeProtectionInstalled = false;
    private static Instant recordingStartedInstant;
    private static long finalizationProgressStartedNano;
    private static volatile boolean transcriptLiveModelReady = false;
    private static volatile boolean transcriptSilenceWarningActive = false;
    private static volatile boolean transcriptClippingWarningActive = false;
    private static long nextEventSequence = 1L;
    private static volatile String lastDevelopmentGuardState = "";
    private static RecordingWorkflowState lastPanelWorkflowState;
    private static boolean updatingPanelDivider;
    private static boolean synchronizingTranscriptAndEvents;
    
    // Persistent preferences
    private static final BooleanProperty enableTimestamp = PathPrefs.createPersistentPreference(
            "timestamp.enable", true);

    private static final BooleanProperty recordEvents = new SimpleBooleanProperty(false);
            
    private static final BooleanProperty enableMouseTracking = PathPrefs.createPersistentPreference(
            "timestamp.trackMouse", false);
    
    private static final DoubleProperty timestampFontSize = PathPrefs.createPersistentPreference(
            "timestamp.fontSize", DEFAULT_OVERLAY_FONT_SIZE);

    private static final DoubleProperty panelDividerPosition = PathPrefs.createPersistentPreference(
            "timestamp.panelDividerPosition", DEFAULT_PANEL_DIVIDER_POSITION);

    private static final BooleanProperty compactOverlayMigrationComplete =
            PathPrefs.createPersistentPreference("timestamp.compactOverlayV1", false);

    private static final StringProperty transcriptLanguage = PathPrefs.createPersistentPreference(
            "timestamp.transcriptLanguage", DEFAULT_TRANSCRIPT_LANGUAGE);

    private static final StringProperty transcriptDevice = PathPrefs.createPersistentPreference(
            "timestamp.transcriptDevice", DEFAULT_TRANSCRIPT_DEVICE);

    private static final StringProperty transcriptHotwords = PathPrefs.createPersistentPreference(
            "timestamp.transcriptHotwords", DEFAULT_TRANSCRIPT_HOTWORDS);

    private static final StringProperty transcriptPythonExecutable = PathPrefs.createPersistentPreference(
            "timestamp.transcriptPythonExecutable", "");

    public static BooleanProperty enableTimestampProperty() {
        return enableTimestamp;
    }
    
    public static BooleanProperty enableMouseTrackingProperty() {
        return enableMouseTracking;
    }
    
    public static DoubleProperty timestampFontSizeProperty() {
        return timestampFontSize;
    }
    
    @Override
    public void installExtension(QuPathGUI qupath) {
        if (isInstalled) {
            logger.debug("{} is already installed", getName());
            return;
        }
        isInstalled = true;
        qupathGui = qupath;
        migrateOverlayPreferences();
        
        addPreferences(qupath);
        addMenuItem(qupath);
        installLiveEventTab(qupath);
        installToolChangeListener(qupath);
        installTimestampOverlay(qupath);
        ensureOverlayRefreshStarted();
        ensureRecoveryCheckpointStarted();
        installUnsavedRecordingCloseProtection(qupath);
        Platform.runLater(TimeStamp::offerUnsavedRecordingRecovery);

        enableTimestamp.addListener((obs, oldValue, newValue) -> repaintAllViewers());
        timestampFontSize.addListener((obs, oldValue, newValue) -> repaintAllViewers());
        
        logger.info("{} installed successfully", getName());
    }

    private static void migrateOverlayPreferences() {
        if (compactOverlayMigrationComplete.get()) {
            return;
        }
        if (Math.abs(timestampFontSize.get() - LEGACY_OVERLAY_FONT_SIZE) < 0.01) {
            timestampFontSize.set(DEFAULT_OVERLAY_FONT_SIZE);
        }
        compactOverlayMigrationComplete.set(true);
    }

    private void addPreferences(QuPathGUI qupath) {
        var enableProperty = new PropertyItemBuilder<>(enableTimestamp, Boolean.class)
                .name("Enable timestamp overlay")
                .category(TIMESTAMP_CATEGORY)
                .description("Show/hide timestamp and event log on screen")
                .build();
                
        var enableMouseProperty = new PropertyItemBuilder<>(enableMouseTracking, Boolean.class)
                .name("Enable mouse tracking")
                .category(TIMESTAMP_CATEGORY)
                .description("Record raw mouse movements (exported separately)")
                .build();

        var fontSizeProperty = new PropertyItemBuilder<>(timestampFontSize, Double.class)
                .name("Font size")
                .category(TIMESTAMP_CATEGORY)
                .description("Font size for timestamp display")
                .build();

        var pythonExecutableProperty = new PropertyItemBuilder<>(transcriptPythonExecutable, String.class)
                .name("Transcript Python executable")
                .category(TIMESTAMP_CATEGORY)
                .description("Full path to Python in the environment containing faster-whisper, numpy, and sounddevice")
                .build();
        
        qupath.getPreferencePane()
                .getPropertySheet()
                .getItems()
                .addAll(enableProperty, enableMouseProperty, fontSizeProperty, pythonExecutableProperty);
    }
    
    private void addMenuItem(QuPathGUI qupath) {
        var menu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);
        
        MenuItem toggleItem = new MenuItem("Toggle timestamp display");
        toggleItem.setOnAction(e -> {
            enableTimestamp.set(!enableTimestamp.get());
            qupath.getAllViewers().forEach(QuPathViewer::repaint);
        });
        menu.getItems().add(toggleItem);
        
        MenuItem showLogItem = new MenuItem("Open Clinical Session Recorder");
        showLogItem.setOnAction(e -> showEventLog());
        menu.getItems().add(showLogItem);

        MenuItem exportTranscriptItem = new MenuItem("Export transcript");
        exportTranscriptItem.setOnAction(e -> exportTranscript());
        menu.getItems().add(exportTranscriptItem);

        transcriptSettingsMenuItem = new MenuItem("Transcript settings");
        transcriptSettingsMenuItem.setOnAction(e -> showTranscriptSettingsDialog());
        menu.getItems().add(transcriptSettingsMenuItem);
        
        clearLogMenuItem = new MenuItem("Clear event log");
        clearLogMenuItem.setOnAction(e -> clearLogs());
        menu.getItems().add(clearLogMenuItem);
        
        MenuItem exportLogItem = new MenuItem("Export event log to CSV");
        exportLogItem.setOnAction(e -> exportEventLog());
        menu.getItems().add(exportLogItem);

        MenuItem exportJsonItem = new MenuItem("Export event log to JSON");
        exportJsonItem.setOnAction(e -> exportEventLogToJson());
        menu.getItems().add(exportJsonItem);
        
        MenuItem exportMouseItem = new MenuItem("Export mouse movement log to JSON");
        exportMouseItem.setOnAction(e -> exportMouseLogToJson());
        menu.getItems().add(exportMouseItem);
    }
    
    private void installTimestampOverlay(QuPathGUI qupath) {
        var overlay = new TimestampOverlay();
        
        // Install on all viewers with event listeners
        qupath.getAllViewers().forEach(viewer -> {
            viewer.getCustomOverlayLayers().add(overlay);
            installEventListeners(viewer);
        });
        
        // Listen for new viewers
        qupath.viewerProperty().addListener((obs, oldViewer, newViewer) -> {
            if (newViewer != null && !newViewer.getCustomOverlayLayers().contains(overlay)) {
                newViewer.getCustomOverlayLayers().add(overlay);
                installEventListeners(newViewer);
            }
        });
    }

    private static void repaintAllViewers() {
        if (qupathGui != null) {
            qupathGui.getAllViewers().forEach(QuPathViewer::repaint);
        }
    }

    private static void ensureOverlayRefreshStarted() {
        if (overlayRefreshTimeline != null) {
            if (overlayRefreshTimeline.getStatus() != Animation.Status.RUNNING) {
                overlayRefreshTimeline.play();
            }
            return;
        }
        overlayRefreshTimeline = new Timeline(
                new KeyFrame(Duration.seconds(OVERLAY_REFRESH_INTERVAL_SECONDS), e -> {
                    if (enableTimestamp.get()) {
                        repaintAllViewers();
                    }
                }));
        overlayRefreshTimeline.setCycleCount(Animation.INDEFINITE);
        overlayRefreshTimeline.play();
    }

    private static void ensureRecoveryCheckpointStarted() {
        if (recoveryCheckpointTimeline != null) {
            if (recoveryCheckpointTimeline.getStatus() != Animation.Status.RUNNING) {
                recoveryCheckpointTimeline.play();
            }
            return;
        }
        recoveryCheckpointTimeline = new Timeline(new KeyFrame(
                Duration.seconds(RECOVERY_CHECKPOINT_INTERVAL_SECONDS),
                e -> checkpointWorkingSession()));
        recoveryCheckpointTimeline.setCycleCount(Animation.INDEFINITE);
        recoveryCheckpointTimeline.play();
    }

    private static Path getWorkingRecordingRoot() {
        return Paths.get(System.getProperty("java.io.tmpdir"), "qupath-timestamp-recordings");
    }

    private static Path getRecoverySnapshotPath(File workingDirectory) {
        return workingDirectory.toPath().resolve(".timestamp-recovery.bin");
    }

    private static void checkpointWorkingSession() {
        if (!recordingSessionDirty || transcriptSessionDir == null || recoveryCheckpointInProgress) {
            return;
        }
        RecoverySnapshot snapshot = new RecoverySnapshot(
                new ArrayList<>(eventLog), new ArrayList<>(mouseMoveLog),
                recordingStartedInstant, nextEventSequence);
        File workingDirectory = transcriptSessionDir;
        recoveryCheckpointInProgress = true;
        Thread checkpointThread = new Thread(() -> {
            Path target = getRecoverySnapshotPath(workingDirectory);
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            try {
                Files.createDirectories(target.getParent());
                try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(
                        temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
                    output.writeObject(snapshot);
                }
                try {
                    Files.move(temporary, target,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                logger.warn("Could not checkpoint temporary recording session", e);
            } finally {
                recoveryCheckpointInProgress = false;
            }
        }, "timestamp-recovery-checkpoint");
        checkpointThread.setDaemon(true);
        checkpointThread.start();
    }

    private static void installUnsavedRecordingCloseProtection(QuPathGUI qupath) {
        if (closeProtectionInstalled || qupath == null || qupath.getStage() == null) {
            return;
        }
        closeProtectionInstalled = true;
        qupath.getStage().addEventFilter(WindowEvent.WINDOW_CLOSE_REQUEST, event -> {
            if (recordingWorkflowState == RecordingWorkflowState.PAUSED) {
                event.consume();
                Dialogs.showWarningNotification(TIMESTAMP_CATEGORY,
                        "Choose Done and wait for finalization before closing QuPath.");
                return;
            }
            if (recordEvents.get() || transcriptStartPending || isTranscriptProcessBusy()) {
                event.consume();
                Dialogs.showWarningNotification(TIMESTAMP_CATEGORY,
                        "Stop the recording and wait for finalization before closing QuPath.");
                return;
            }
            if (!recordingSessionDirty) {
                return;
            }
            javafx.scene.control.ButtonType choice = Dialogs.showYesNoCancelDialog(
                    "Unsaved TimeStamp Recording",
                    "Save the transcript and timestamps before closing QuPath?");
            if (choice == null || choice == javafx.scene.control.ButtonType.CANCEL) {
                event.consume();
            } else if (choice == javafx.scene.control.ButtonType.YES && !saveTranscriptAndTimestamps()) {
                event.consume();
            } else if (choice == javafx.scene.control.ButtonType.NO) {
                recordingSessionDirty = false;
                recordingWorkflowState = RecordingWorkflowState.READY;
                if (transcriptSessionDir != null) {
                    try {
                        atomicWriteString(transcriptSessionDir.toPath().resolve(".discarded"),
                                LocalDateTime.now().toString());
                    } catch (IOException e) {
                        logger.warn("Could not mark temporary recording as discarded", e);
                    }
                }
            }
        });
    }

    private static void offerUnsavedRecordingRecovery() {
        if (transcriptSessionDir != null || recordingSessionDirty) {
            return;
        }
        File candidate = findLatestRecoverableWorkingSession();
        if (candidate == null) {
            return;
        }
        if (!Dialogs.showYesNoDialog("Recover Unsaved Recording",
                "An unsaved TimeStamp recording was found from " + candidate.getName() +
                        ". Recover it for review and saving?")) {
            return;
        }
        recoverWorkingSession(candidate);
    }

    private static File findLatestRecoverableWorkingSession() {
        Path root = getWorkingRecordingRoot();
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (var directories = Files.list(root)) {
            return directories
                    .filter(Files::isDirectory)
                    .filter(path -> !Files.exists(path.resolve(".saved")))
                    .filter(path -> !Files.exists(path.resolve(".discarded")))
                    .filter(path -> hasExistingTranscriptCapture(path.toFile()))
                    .max(Comparator.comparingLong(path -> path.toFile().lastModified()))
                    .map(Path::toFile)
                    .orElse(null);
        } catch (IOException e) {
            logger.warn("Could not scan for recoverable TimeStamp recordings", e);
            return null;
        }
    }

    private static boolean hasExistingTranscriptCapture(File sessionDirectory) {
        File candidateTranscript = buildTranscriptFile(sessionDirectory);
        if (candidateTranscript == null) {
            return false;
        }
        String stem = candidateTranscript.getName().endsWith(".txt")
                ? candidateTranscript.getName().substring(0, candidateTranscript.getName().length() - 4)
                : candidateTranscript.getName();
        File videoDirectory = candidateTranscript.getParentFile();
        return candidateTranscript.isFile() ||
                new File(videoDirectory, stem + "_audio.raw").isFile() ||
                new File(videoDirectory, stem + "_audio.wav").isFile();
    }

    private static void recoverWorkingSession(File workingDirectory) {
        transcriptSessionDir = workingDirectory;
        transcriptFile = buildTranscriptFile(workingDirectory);
        transcriptCaptureStarted = true;
        recordingSessionSaved = false;
        recordingSessionDirty = true;
        transcriptFinalizationResult = "recovered";
        transcriptLastExitCode = -1;
        recordingWorkflowState = RecordingWorkflowState.UNSAVED_REVIEW;
        Path snapshotPath = getRecoverySnapshotPath(workingDirectory);
        if (Files.isRegularFile(snapshotPath)) {
            try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(snapshotPath))) {
                Object value = input.readObject();
                if (value instanceof RecoverySnapshot snapshot) {
                    eventLog.clear();
                    eventLog.addAll(snapshot.events());
                    mouseMoveLog.clear();
                    mouseMoveLog.addAll(snapshot.mouseEvents());
                    recordingStartedInstant = snapshot.recordingStartedInstant();
                    nextEventSequence = snapshot.nextSequence();
                }
            } catch (IOException | ClassNotFoundException e) {
                logger.warn("Recovered transcript media but could not restore its event checkpoint", e);
            }
        }
        transcriptLastModified = -1L;
        transcriptLastSize = -1L;
        transcriptLastContents = "";
        updateLiveEventMonitorControls();
        refreshLiveEventMonitor();
        if (transcriptStatusLabel != null) {
            transcriptStatusLabel.setText(
                    "Recovered an unsaved recording — review it, then choose Save Session");
        }
    }

    private void installToolChangeListener(QuPathGUI qupath) {
        qupath.getToolManager().selectedToolProperty().addListener((obs, oldTool, newTool) -> {
            if (!recordEvents.get() || newTool == null || newTool == oldTool) {
                return;
            }

            QuPathViewer viewer = getViewerForLogging(qupath);
            if (viewer == null) {
                return;
            }

            String oldToolName = getToolName(oldTool);
            String newToolName = getToolName(newTool);
            logEvent("Tool Changed",
                    String.format("from=%s, to=%s", oldToolName, newToolName),
                    viewer);
            viewer.repaint();
        });
    }

    private void installLiveEventTab(QuPathGUI qupath) {
        Platform.runLater(() -> {
            if (liveEventTab == null) {
                liveEventTab = new Tab("TimeStamp Monitor");
                liveEventTab.setClosable(false);
                liveEventTab.setContent(createLiveEventMonitorPane());
            }

            TabPane analysisTabPane = qupath.getAnalysisTabPane();
            if (analysisTabPane != null && !analysisTabPane.getTabs().contains(liveEventTab)) {
                analysisTabPane.getTabs().add(liveEventTab);
            }
        });
    }
    
    /**
     * Install event listeners to track user interactions
     */
    private void installEventListeners(QuPathViewer viewer) {
        var view = viewer.getView();
        var interactionState = new ViewerInteractionState();

        interactionState.zoomEndDelay.setOnFinished(e -> {
            if (interactionState.zoomInProgress && recordEvents.get() &&
                    interactionState.zoomStartView != null) {
                double finalDownsample = viewer.getDownsampleFactor();
                double initialDownsample = interactionState.zoomStartView.downsample;
                if (Math.abs(finalDownsample - initialDownsample) > 1e-6) {
                    String direction = finalDownsample < initialDownsample ? "Zoom In" : "Zoom Out";
                    logEventAt(interactionState.zoomStartTime, direction + " Start",
                            String.format(Locale.ROOT, "downsample=%.4f", initialDownsample),
                            interactionState.zoomStartView, null);
                    logEvent(direction + " End",
                            String.format(Locale.ROOT, "downsample=%.4f", finalDownsample), viewer);
                    viewer.repaint();
                }
            }
            interactionState.zoomInProgress = false;
            interactionState.zoomStartTime = null;
            interactionState.zoomStartView = null;
        });
        
        // Track mouse clicks
        view.addEventFilter(MouseEvent.MOUSE_CLICKED, event -> {
            if (recordEvents.get()) {
                String details = formatPointerDetails(viewer, event.getX(), event.getY()) +
                        ", button=" + event.getButton();
                logEvent("Click", details, viewer);
                viewer.repaint();
            }
        });
        
        // Track zoom events
        view.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (recordEvents.get() && event.getDeltaY() != 0) {
                if (!interactionState.zoomInProgress) {
                    interactionState.zoomInProgress = true;
                    interactionState.zoomStartTime = LocalDateTime.now();
                    interactionState.zoomStartView = captureViewBounds(viewer);
                }
                interactionState.zoomEndDelay.playFromStart();
            } else if (interactionState.zoomInProgress) {
                interactionState.zoomEndDelay.playFromStart();
            }
        });

        view.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (recordEvents.get() && event.isPrimaryButtonDown()) {
                interactionState.panCandidate = true;
                interactionState.panDragged = false;
                interactionState.panStartTime = LocalDateTime.now();
                interactionState.panStartView = captureViewBounds(viewer);
                interactionState.panStartComponentX = event.getX();
                interactionState.panStartComponentY = event.getY();
                interactionState.panStartImagePoint = viewer.componentPointToImagePoint(
                        event.getX(), event.getY(), null, false);
            } else {
                interactionState.resetPan();
            }
        });

        view.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (recordEvents.get() && event.isPrimaryButtonDown() && interactionState.panCandidate) {
                interactionState.panDragged = true;
            }
        });
        
        view.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (interactionState.panCandidate && interactionState.panDragged &&
                    recordEvents.get() && interactionState.panStartView != null) {
                double centerShift = Math.hypot(
                        viewer.getCenterPixelX() - interactionState.panStartView.centerX,
                        viewer.getCenterPixelY() - interactionState.panStartView.centerY);
                if (centerShift > 0.01) {
                    logEventAt(interactionState.panStartTime, "Pan Start",
                            formatPointerDetails(
                                    interactionState.panStartComponentX,
                                    interactionState.panStartComponentY,
                                    interactionState.panStartImagePoint),
                            interactionState.panStartView, null);
                    logEvent("Pan End", formatPointerDetails(viewer, event.getX(), event.getY()), viewer);
                    viewer.repaint();
                }
            }
            interactionState.resetPan();
        });

        // Track mouse movement (throttled to avoid log spam, e.g., max 10 times per second)
        long[] lastMouseMoveTime = {0};
        view.addEventFilter(MouseEvent.MOUSE_MOVED, event -> {
            if (recordEvents.get() && enableMouseTracking.get()) {
                long now = System.currentTimeMillis();
                if (now - lastMouseMoveTime[0] > 100) { // 100ms throttle
                    lastMouseMoveTime[0] = now;
                    logEvent("MouseMove", formatPointerDetails(viewer, event.getX(), event.getY()), viewer);
                }
            }
        });

        // Track annotation creation via hierarchy listener
        installAnnotationListener(viewer);
    }

    /**
     * Install a hierarchy listener on the viewer to detect annotation creation.
     * Handles image data changes (re-attaches listener to new hierarchy).
     */
    private void installAnnotationListener(QuPathViewer viewer) {
        PathObjectHierarchyListener hierarchyListener = event -> {
            if (!recordEvents.get()) return;
            if (event.isChanging()) return;
            HierarchyEventType hierarchyEventType = event.getEventType();
            if (hierarchyEventType != HierarchyEventType.ADDED &&
                    hierarchyEventType != HierarchyEventType.REMOVED &&
                    hierarchyEventType != HierarchyEventType.CHANGE_CLASSIFICATION &&
                    hierarchyEventType != HierarchyEventType.CHANGE_OTHER) return;

            for (PathObject pathObject : event.getChangedObjects()) {
                if (pathObject.isAnnotation() && pathObject.getROI() != null) {
                    ROI roi = pathObject.getROI();
                    AnnotationGeometry geom = createAnnotationGeometry(roi);
                    String details = createAnnotationDetails(roi) +
                            ", class=" + (pathObject.getPathClass() == null
                            ? "Unclassified"
                            : pathObject.getPathClass());
                    String eventType = switch (hierarchyEventType) {
                        case ADDED -> "Annotate";
                        case REMOVED -> "Annotation Deleted";
                        case CHANGE_CLASSIFICATION -> "Annotation Reclassified";
                        case CHANGE_OTHER -> "Annotation Modified";
                        default -> "Annotation Changed";
                    };

                    logEvent(eventType, details, viewer, geom);
                    viewer.repaint();
                }
            }
        };

        // Attach to current image data
        var imageData = viewer.getImageData();
        if (imageData != null) {
            imageData.getHierarchy().addListener(hierarchyListener);
        }

        // Re-attach when image data changes
        viewer.imageDataProperty().addListener((obs, oldData, newData) -> {
            if (oldData != null) {
                oldData.getHierarchy().removeListener(hierarchyListener);
            }
            if (newData != null) {
                newData.getHierarchy().addListener(hierarchyListener);
            }
        });
    }
    
    /**
     * Log an event with timestamp and current viewer bounding box
     */
    private static void logEvent(String eventType, String details, QuPathViewer viewer) {
        logEvent(eventType, details, viewer, null);
    }

    private static String getToolName(PathTool tool) {
        return tool == null ? "None" : tool.getName();
    }

    private static QuPathViewer getViewerForLogging(QuPathGUI qupath) {
        QuPathViewer viewer = qupath.getViewer();
        if (viewer != null) {
            return viewer;
        }
        return qupath.getAllViewers().isEmpty() ? null : qupath.getAllViewers().get(0);
    }

    private static String formatPointerDetails(QuPathViewer viewer, double componentX, double componentY) {
        Point2D imagePoint = viewer.componentPointToImagePoint(componentX, componentY, null, false);
        return formatPointerDetails(componentX, componentY, imagePoint);
    }

    private static String formatPointerDetails(double componentX, double componentY, Point2D imagePoint) {
        if (imagePoint == null) {
            return String.format(Locale.ROOT, "component_x=%.1f, component_y=%.1f",
                    componentX, componentY);
        }
        return String.format(Locale.ROOT,
                "component_x=%.1f, component_y=%.1f, image_x=%.1f, image_y=%.1f",
                componentX, componentY, imagePoint.getX(), imagePoint.getY());
    }

    private static AnnotationGeometry createAnnotationGeometry(ROI roi) {
        List<double[]> vertexList = new ArrayList<>();
        for (var p : roi.getAllPoints()) {
            vertexList.add(new double[]{p.getX(), p.getY()});
        }

        return new AnnotationGeometry(
                roi.getRoiName(),
                roi.getBoundsX(), roi.getBoundsY(),
                roi.getBoundsWidth(), roi.getBoundsHeight(),
                roi.getNumPoints(), vertexList);
    }

    private static String createAnnotationDetails(ROI roi) {
        return String.format(Locale.ROOT, "type=%s, points=%d, bounds=[%.1f, %.1f, %.1f, %.1f]",
                roi.getRoiName(), roi.getNumPoints(),
                roi.getBoundsX(), roi.getBoundsY(),
                roi.getBoundsWidth(), roi.getBoundsHeight());
    }

    private static ViewBounds captureViewBounds(QuPathViewer viewer) {
        double downsample = viewer.getDownsampleFactor();
        double centerX = viewer.getCenterPixelX();
        double centerY = viewer.getCenterPixelY();
        double width = viewer.getView().getWidth() * downsample;
        double height = viewer.getView().getHeight() * downsample;
        return new ViewBounds(
                centerX - width / 2.0,
                centerY - height / 2.0,
                width,
                height,
                centerX,
                centerY,
                viewer.getZPosition(),
                viewer.getTPosition(),
                downsample,
                viewer.getRotation());
    }

    /**
     * Log an event with timestamp, viewer bounding box, and optional annotation geometry
     */
    private static void logEvent(String eventType, String details,
                                 QuPathViewer viewer, AnnotationGeometry annotation) {
        logEventAt(LocalDateTime.now(), eventType, details, captureViewBounds(viewer), annotation);
    }

    private static void logEventAt(LocalDateTime timestamp, String eventType, String details,
                                   ViewBounds view, AnnotationGeometry annotation) {
        Instant recordedAtUtc = timestamp.atZone(ZoneId.systemDefault()).toInstant();
        EventRecord entry = new EventRecord(
                nextEventSequence++,
                timestamp,
                recordedAtUtc,
                eventType,
                details,
                view,
                annotation,
                transcriptSessionDir == null ? "" : transcriptSessionDir.getName());
        if ("MouseMove".equals(eventType)) {
            mouseMoveLog.add(entry);
        } else {
            insertEventRecordChronologically(entry);
            refreshLiveEventMonitor();
        }
        recordingSessionDirty = true;

        if ("MouseMove".equals(eventType)) {
            logger.debug("Mouse event: {} - {}", formatter.format(timestamp), details);
        } else if (logger.isInfoEnabled()) {
            logger.info("Event: {} - {} - {} - view:[x={}, y={}, w={}, h={}, z={}, t={}]",
                    formatter.format(timestamp), eventType, details,
                    view.x, view.y, view.width, view.height, view.z, view.t);
        }
    }

    static Long derivedElapsedMillis(Instant origin, Instant eventTime) {
        if (origin == null || eventTime == null || eventTime.isBefore(origin)) {
            return null;
        }
        return java.time.Duration.between(origin, eventTime).toMillis();
    }

    private static Long derivedElapsedMillis(EventRecord entry) {
        return derivedElapsedMillis(recordingStartedInstant, entry.recordedAtUtc);
    }

    private static void insertEventRecordChronologically(EventRecord entry) {
        int insertionIndex = eventLog.size();
        while (insertionIndex > 0 &&
                eventLog.get(insertionIndex - 1).timestamp.isAfter(entry.timestamp)) {
            insertionIndex--;
        }
        eventLog.add(insertionIndex, entry);
    }

    private static void logSessionBoundary(String eventType) {
        if (qupathGui == null) {
            return;
        }
        QuPathViewer viewer = getViewerForLogging(qupathGui);
        if (viewer != null) {
            logEvent(eventType, "session=" +
                    (transcriptSessionDir == null ? "working" : transcriptSessionDir.getName()), viewer);
        }
    }
    
    private static BorderPane createLiveEventMonitorPane() {
        liveTranscriptTextArea = new TextArea();
        liveTranscriptTextArea.setEditable(true);
        liveTranscriptTextArea.setWrapText(true);
        liveTranscriptTextArea.setStyle("-fx-font-family: 'System'; -fx-font-size: 13px;");
        liveTranscriptTextArea.setPromptText("Click Start Recording to begin live transcription.");
        liveTranscriptTextArea.textProperty().addListener((obs, oldText, newText) -> {
            if (recordingWorkflowState == RecordingWorkflowState.SAVED &&
                    !String.valueOf(oldText).equals(String.valueOf(newText))) {
                recordingSessionDirty = true;
                recordingWorkflowState = RecordingWorkflowState.UNSAVED_REVIEW;
                updateLiveEventMonitorControls();
            }
        });
        liveTranscriptTextArea.setOnMouseClicked(event ->
                Platform.runLater(TimeStamp::selectEventsForTranscriptCaret));

        recordingPrimaryButton = new Button("Start Recording");
        recordingPrimaryButton.setOnAction(e -> handleRecordingPrimaryAction());
        configureMonitorButton(recordingPrimaryButton);
        recordingPrimaryButton.setMaxWidth(Double.MAX_VALUE);

        recordingDoneButton = new Button("⏹ Done");
        recordingDoneButton.setOnAction(e -> finishRecordingSession());
        configureMonitorButton(recordingDoneButton);
        recordingDoneButton.setTooltip(new Tooltip(
                "End this take and create the high-accuracy final transcript"));

        recordMoreButton = new Button("↺ Record more");
        recordMoreButton.setOnAction(e -> startRecordingSession());
        configureMonitorButton(recordMoreButton);
        recordMoreButton.setTooltip(new Tooltip(
                "Resume this take and append more audio before saving"));

        transcriptSettingsButton = new Button("⚙ Settings");
        transcriptSettingsButton.setOnAction(e -> showTranscriptSettingsDialog());
        configureMonitorButton(transcriptSettingsButton);

        panelExportTranscriptMenuItem = new MenuItem("Export Transcript");
        panelExportTranscriptMenuItem.setOnAction(e -> exportTranscript());
        panelClearEventsMenuItem = new MenuItem("Clear Events");
        panelClearEventsMenuItem.setOnAction(e -> clearLogsStatic());
        transcriptMoreButton = new MenuButton("⋯ More", null,
                panelExportTranscriptMenuItem, panelClearEventsMenuItem);

        recordingStateDotLabel = new Label("●");
        recordingStatusLabel = new Label();
        transcriptAudioLevelLabel = new Label("Mic");
        transcriptStatusLabel = new Label();
        transcriptStatusLabel.textProperty().addListener((obs, oldText, newText) ->
                updateRecordingStatusLine());
        transcriptAudioLevelBar = new ProgressBar(0);
        transcriptAudioLevelBar.setMaxWidth(Double.MAX_VALUE);
        transcriptAudioLevelBar.setPrefHeight(12);
        transcriptAudioLevelBar.setMinHeight(12);
        transcriptAudioLevelBar.setStyle("-fx-accent: #2fbf71;");
        transcriptFinalizationProgressLabel = new Label("Final transcript: waiting");
        transcriptFinalizationProgressLabel.setVisible(false);
        transcriptFinalizationProgressLabel.setManaged(false);
        transcriptFinalizationProgressBar = new ProgressBar(0);
        transcriptFinalizationProgressBar.setMaxWidth(Double.MAX_VALUE);
        transcriptFinalizationProgressBar.setPrefHeight(12);
        transcriptFinalizationProgressBar.setMinHeight(12);
        transcriptFinalizationProgressBar.setStyle("-fx-accent: #2f80ed;");
        transcriptFinalizationProgressBar.setVisible(false);
        transcriptFinalizationProgressBar.setManaged(false);
        recordingStatusLabel.setWrapText(true);

        Label titleLabel = new Label("TimeStamp Recorder");
        titleLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        Label versionLabel = new Label("v" + extensionVersion());
        versionLabel.setStyle("-fx-text-fill: #666666; -fx-font-size: 11px;");
        versionLabel.setTooltip(new Tooltip(
                "TimeStamp extension version " + extensionVersion() + " • QuPath " +
                        EXTENSION_QUPATH_VERSION));
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        HBox titleRow = new HBox(8, titleLabel, titleSpacer, versionLabel);
        recordingStatusLabel.setMaxWidth(Double.MAX_VALUE);
        recordingStatusLabel.setStyle("-fx-font-weight: bold;");

        HBox statusRow = new HBox(6, recordingStateDotLabel, recordingStatusLabel);
        HBox.setHgrow(recordingStatusLabel, Priority.ALWAYS);
        transcriptMicrophoneRow = new HBox(8, transcriptAudioLevelBar, transcriptAudioLevelLabel);
        HBox.setHgrow(transcriptAudioLevelBar, Priority.ALWAYS);

        transcriptSecondaryControls = new FlowPane(8, 4,
                recordingDoneButton, recordMoreButton,
                transcriptSettingsButton, transcriptMoreButton);
        transcriptSecondaryControls.setPrefWrapLength(300);

        VBox statusPane = new VBox(4, statusRow, transcriptMicrophoneRow,
                transcriptFinalizationProgressLabel, transcriptFinalizationProgressBar);
        VBox header = new VBox(7, titleRow, statusPane,
                recordingPrimaryButton, transcriptSecondaryControls);
        header.setPadding(new Insets(0, 0, 8, 0));

        transcriptHelpLabel = new Label(
                "Live text is a preview. Choose Done to create the final transcript before saving.");
        transcriptHelpLabel.setWrapText(true);
        transcriptHelpLabel.setStyle("-fx-text-fill: #667085; -fx-font-size: 11px;");
        transcriptPartialLabel = new Label();
        transcriptPartialLabel.setWrapText(true);
        transcriptPartialLabel.setMaxWidth(Double.MAX_VALUE);
        transcriptPartialLabel.setStyle(
                "-fx-text-fill: #8a94a6; -fx-font-size: 13px; -fx-font-style: italic;");
        transcriptPartialLabel.setVisible(false);
        transcriptPartialLabel.setManaged(false);
        Label transcriptLabel = new Label("Transcript");
        transcriptLabel.setStyle("-fx-font-weight: bold;");
        VBox transcriptPane = new VBox(
                5, transcriptLabel, transcriptHelpLabel, liveTranscriptTextArea, transcriptPartialLabel);
        transcriptPane.setPadding(new Insets(4, 0, 4, 0));
        VBox.setVgrow(liveTranscriptTextArea, Priority.ALWAYS);

        liveEventTable = createEventTable();
        Label eventLabel = new Label("Events");
        eventLabel.setStyle("-fx-font-weight: bold;");
        liveEventCountLabel = new Label("0");
        liveEventCountLabel.setStyle("-fx-text-fill: #667085; -fx-font-size: 11px;");
        Region eventHeaderSpacer = new Region();
        HBox.setHgrow(eventHeaderSpacer, Priority.ALWAYS);
        HBox eventHeader = new HBox(6, eventLabel, eventHeaderSpacer, liveEventCountLabel);
        VBox eventPane = new VBox(5, eventHeader, liveEventTable);
        eventPane.setPadding(new Insets(4, 0, 0, 0));
        VBox.setVgrow(liveEventTable, Priority.ALWAYS);

        transcriptEventSplitPane = new SplitPane(transcriptPane, eventPane);
        transcriptEventSplitPane.setOrientation(Orientation.VERTICAL);
        transcriptEventSplitPane.setDividerPositions(clampPanelDivider(panelDividerPosition.get()));
        transcriptEventSplitPane.getDividers().get(0).positionProperty().addListener(
                (obs, oldPosition, newPosition) -> {
                    if (!updatingPanelDivider) {
                        panelDividerPosition.set(clampPanelDivider(newPosition.doubleValue()));
                    }
                });

        var root = new BorderPane(transcriptEventSplitPane);
        root.setTop(header);
        root.setPadding(new Insets(8));
        root.widthProperty().addListener((obs, oldWidth, newWidth) ->
                updateTranscriptEventSplitOrientation(newWidth.doubleValue()));

        lastPanelWorkflowState = null;
        updateLiveEventMonitorControls();
        updateTranscriptSettingsSummary();
        refreshLiveEventMonitorContents();
        ensureTranscriptRefreshStarted();
        return root;
    }

    private static TableView<EventEntry> createEventTable() {
        TableView<EventEntry> table = new TableView<>();
        table.setPlaceholder(new Label("Events will appear here while recording."));
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        TableColumn<EventEntry, String> elapsedColumn = new TableColumn<>("Elapsed");
        elapsedColumn.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(cell.getValue().elapsed()));
        elapsedColumn.setMinWidth(68);
        elapsedColumn.setPrefWidth(76);

        TableColumn<EventEntry, String> typeColumn = new TableColumn<>("Type");
        typeColumn.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(cell.getValue().type()));
        typeColumn.setMinWidth(80);
        typeColumn.setPrefWidth(110);

        TableColumn<EventEntry, String> detailsColumn = new TableColumn<>("Details");
        detailsColumn.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(cell.getValue().details()));
        detailsColumn.setMinWidth(140);

        table.getColumns().addAll(elapsedColumn, typeColumn, detailsColumn);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.getSelectionModel().selectedItemProperty().addListener((obs, oldEntry, newEntry) -> {
            if (!synchronizingTranscriptAndEvents && newEntry != null) {
                selectTranscriptForEvent(newEntry);
            }
        });
        return table;
    }

    private static void handleRecordingPrimaryAction() {
        switch (recordingPrimaryAction(recordingWorkflowState)) {
            case START, RESUME -> startRecordingSession();
            case PAUSE -> pauseRecordingSession();
            case SAVE -> saveTranscriptAndTimestamps();
            case WAIT -> { }
        }
    }

    static RecordingPrimaryAction recordingPrimaryAction(RecordingWorkflowState state) {
        return switch (state) {
            case READY, SAVED -> RecordingPrimaryAction.START;
            case RECORDING -> RecordingPrimaryAction.PAUSE;
            case PAUSED -> RecordingPrimaryAction.RESUME;
            case UNSAVED_REVIEW, ERROR -> RecordingPrimaryAction.SAVE;
            case STARTING, FINALIZING, SAVING -> RecordingPrimaryAction.WAIT;
        };
    }

    static List<String> transcriptLifecycleArguments(boolean finalizeExisting) {
        return List.of(finalizeExisting ? "--finalize-existing" : "--capture-only");
    }

    static boolean usesHorizontalPanelLayout(double width) {
        return Double.isFinite(width) && width >= WIDE_PANEL_MINIMUM_WIDTH;
    }

    private static Orientation panelOrientationForWidth(double width) {
        return usesHorizontalPanelLayout(width)
                ? Orientation.HORIZONTAL
                : Orientation.VERTICAL;
    }

    static double clampPanelDivider(double position) {
        if (!Double.isFinite(position)) {
            return DEFAULT_PANEL_DIVIDER_POSITION;
        }
        return Math.max(0.15, Math.min(0.85, position));
    }

    private static void updateTranscriptEventSplitOrientation(double width) {
        if (transcriptEventSplitPane == null) {
            return;
        }
        Orientation orientation = panelOrientationForWidth(width);
        if (transcriptEventSplitPane.getOrientation() != orientation) {
            transcriptEventSplitPane.setOrientation(orientation);
        }
    }

    private static void setPanelDividerPosition(double position) {
        if (transcriptEventSplitPane == null || transcriptEventSplitPane.getDividers().isEmpty()) {
            return;
        }
        double resolved = clampPanelDivider(position);
        updatingPanelDivider = true;
        try {
            transcriptEventSplitPane.setDividerPositions(resolved);
            panelDividerPosition.set(resolved);
        } finally {
            updatingPanelDivider = false;
        }
    }

    static String formatEventElapsed(Long elapsedMillis) {
        if (elapsedMillis == null || elapsedMillis < 0) {
            return "—";
        }
        long totalSeconds = elapsedMillis / 1_000L;
        long hours = totalSeconds / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;
        return hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    static List<TranscriptWindow> transcriptWindows(String contents) {
        String resolved = contents == null ? "" : contents;
        List<Integer> starts = new ArrayList<>();
        List<Instant> timestamps = new ArrayList<>();
        int lineStart = 0;
        while (lineStart < resolved.length()) {
            int lineEnd = resolved.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = resolved.length();
            }
            int closeBracket = resolved.indexOf(']', lineStart);
            if (resolved.charAt(lineStart) == '[' && closeBracket > lineStart && closeBracket <= lineEnd) {
                try {
                    LocalDateTime timestamp = LocalDateTime.parse(
                            resolved.substring(lineStart + 1, closeBracket), formatter);
                    starts.add(lineStart);
                    timestamps.add(timestamp.atZone(ZoneId.systemDefault()).toInstant());
                } catch (RuntimeException ignored) {
                    // Edited transcript lines without a valid timestamp are not link targets.
                }
            }
            lineStart = lineEnd + 1;
        }

        List<TranscriptWindow> windows = new ArrayList<>(starts.size());
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? Math.max(starts.get(i), starts.get(i + 1) - 1) : resolved.length();
            Instant endTime = i + 1 < timestamps.size() ? timestamps.get(i + 1) : null;
            windows.add(new TranscriptWindow(starts.get(i), end, timestamps.get(i), endTime));
        }
        return List.copyOf(windows);
    }

    static TranscriptWindow transcriptWindowAtCaret(String contents, int caretPosition) {
        List<TranscriptWindow> windows = transcriptWindows(contents);
        if (windows.isEmpty()) {
            return null;
        }
        int caret = Math.max(0, Math.min(caretPosition, contents == null ? 0 : contents.length()));
        TranscriptWindow selected = windows.get(0);
        for (TranscriptWindow window : windows) {
            if (window.start() > caret) {
                break;
            }
            selected = window;
        }
        return selected;
    }

    static TranscriptWindow transcriptWindowForInstant(String contents, Instant target) {
        List<TranscriptWindow> windows = transcriptWindows(contents);
        if (windows.isEmpty() || target == null) {
            return null;
        }
        TranscriptWindow selected = windows.get(0);
        for (TranscriptWindow window : windows) {
            if (window.startTime().isAfter(target)) {
                break;
            }
            selected = window;
        }
        return selected;
    }

    static int nearestInstantIndex(List<Instant> candidates, Instant target) {
        if (candidates == null || candidates.isEmpty() || target == null) {
            return -1;
        }
        int nearest = -1;
        long nearestDistance = Long.MAX_VALUE;
        for (int i = 0; i < candidates.size(); i++) {
            Instant candidate = candidates.get(i);
            if (candidate == null) {
                continue;
            }
            long distance;
            try {
                distance = Math.abs(java.time.Duration.between(target, candidate).toMillis());
            } catch (ArithmeticException e) {
                distance = Long.MAX_VALUE;
            }
            if (distance < nearestDistance) {
                nearest = i;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private static void selectTranscriptForEvent(EventEntry entry) {
        if (liveTranscriptTextArea == null || entry == null || entry.source() == null) {
            return;
        }
        TranscriptWindow window = transcriptWindowForInstant(
                liveTranscriptTextArea.getText(), entry.source().recordedAtUtc);
        if (window == null) {
            return;
        }
        synchronizingTranscriptAndEvents = true;
        try {
            liveTranscriptTextArea.selectRange(window.start(), window.end());
        } finally {
            synchronizingTranscriptAndEvents = false;
        }
    }

    private static void selectEventsForTranscriptCaret() {
        if (liveEventTable == null || liveTranscriptTextArea == null || synchronizingTranscriptAndEvents) {
            return;
        }
        TranscriptWindow window = transcriptWindowAtCaret(
                liveTranscriptTextArea.getText(), liveTranscriptTextArea.getCaretPosition());
        if (window == null) {
            return;
        }

        synchronizingTranscriptAndEvents = true;
        try {
            var selection = liveEventTable.getSelectionModel();
            selection.clearSelection();
            int firstMatch = -1;
            List<Instant> eventTimes = new ArrayList<>(liveEventTable.getItems().size());
            for (int i = 0; i < liveEventTable.getItems().size(); i++) {
                Instant eventTime = liveEventTable.getItems().get(i).source().recordedAtUtc;
                eventTimes.add(eventTime);
                boolean inWindow = eventTime != null && !eventTime.isBefore(window.startTime()) &&
                        (window.endTime() == null || eventTime.isBefore(window.endTime()));
                if (inWindow) {
                    selection.select(i);
                    if (firstMatch < 0) {
                        firstMatch = i;
                    }
                }
            }
            if (firstMatch < 0) {
                firstMatch = nearestInstantIndex(eventTimes, window.startTime());
                if (firstMatch >= 0) {
                    selection.select(firstMatch);
                }
            }
            if (firstMatch >= 0) {
                liveEventTable.scrollTo(firstMatch);
            }
        } finally {
            synchronizingTranscriptAndEvents = false;
        }
    }

    /**
     * Focus the clinical session recorder tab in QuPath's analysis pane.
     */
    private void showEventLog() {
        Platform.runLater(() -> {
            installLiveEventTab(qupathGui);
            updateLiveEventMonitorControls();
            refreshLiveEventMonitorContents();
            if (qupathGui != null && liveEventTab != null && qupathGui.getAnalysisTabPane() != null) {
                qupathGui.getAnalysisTabPane().getSelectionModel().select(liveEventTab);
            }
        });
    }

    private static void refreshLiveEventMonitor() {
        Platform.runLater(TimeStamp::refreshLiveEventMonitorContents);
    }

    private static void refreshLiveEventMonitorContents() {
        refreshEventMonitorContents();
        refreshTranscriptContents();
    }

    private static void refreshEventMonitorContents() {
        if (liveEventTable == null) {
            return;
        }

        int maxEvents = Math.min(MAX_LIVE_MONITOR_EVENTS, eventLog.size());
        int startIndex = Math.max(0, eventLog.size() - maxEvents);
        long selectedSequence = liveEventTable.getSelectionModel().getSelectedItem() == null
                ? -1L
                : liveEventTable.getSelectionModel().getSelectedItem().source().sequence;
        List<EventEntry> entries = new ArrayList<>(maxEvents);
        for (int i = startIndex; i < eventLog.size(); i++) {
            EventRecord entry = eventLog.get(i);
            entries.add(new EventEntry(entry,
                    formatEventElapsed(derivedElapsedMillis(entry)), entry.eventType, entry.details));
        }
        liveEventTable.getItems().setAll(entries);
        if (selectedSequence >= 0) {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).source().sequence == selectedSequence) {
                    liveEventTable.getSelectionModel().select(i);
                    break;
                }
            }
        } else if (recordEvents.get() && !entries.isEmpty()) {
            liveEventTable.scrollTo(entries.size() - 1);
        }
        if (liveEventCountLabel != null) {
            liveEventCountLabel.setText(Integer.toString(eventLog.size()));
        }
        updateLiveEventMonitorControls();
    }

    private static boolean isTranscriptProcessBusy() {
        return transcriptStopInProgress || (transcriptProcess != null && transcriptProcess.isAlive());
    }

    private static void updateLiveEventMonitorControls() {
        boolean recording = recordEvents.get();
        boolean starting = transcriptStartPending;
        boolean transcriptBusy = starting || isTranscriptProcessBusy();
        if (recordingPrimaryButton != null) {
            recordingPrimaryButton.setText(switch (recordingWorkflowState) {
                case READY, SAVED -> "Start Recording";
                case STARTING -> "Starting microphone…";
                case RECORDING -> "⏸ Pause";
                case PAUSED -> "● Resume";
                case FINALIZING -> "Creating final transcript…";
                case UNSAVED_REVIEW, ERROR -> "Save Session";
                case SAVING -> "Saving session…";
            });
            boolean primaryUnavailable = switch (recordingWorkflowState) {
                case FINALIZING, SAVING -> true;
                case UNSAVED_REVIEW, ERROR -> transcriptSessionDir == null;
                case READY, SAVED -> transcriptBusy;
                case STARTING -> transcriptBusy;
                case RECORDING -> transcriptStopInProgress;
                case PAUSED -> transcriptBusy;
            };
            recordingPrimaryButton.setDisable(primaryUnavailable);
            recordingPrimaryButton.setTooltip(new Tooltip(switch (recordingWorkflowState) {
                case READY, SAVED -> "Begin recording audio, transcript, and QuPath interactions";
                case STARTING -> "Waiting for the microphone to become ready";
                case RECORDING -> "Pause this take without creating the final transcript";
                case PAUSED -> "Resume audio, transcript, and event capture in this same take";
                case FINALIZING -> "The complete saved audio is being transcribed";
                case UNSAVED_REVIEW, ERROR ->
                        "Choose a folder and save transcript, timestamps, and session manifest";
                case SAVING -> "The recording session is being saved";
            }));
        }
        updateRecordingStatusLine();
        if (liveTranscriptTextArea != null) {
            liveTranscriptTextArea.setEditable(!recording && !transcriptStopInProgress);
            if (liveTranscriptTextArea.getText() == null || liveTranscriptTextArea.getText().isBlank()) {
                liveTranscriptTextArea.setPromptText(emptyTranscriptPrompt());
            }
        }
        if (transcriptHelpLabel != null) {
            transcriptHelpLabel.setText(switch (recordingWorkflowState) {
                case READY, STARTING ->
                        "The transcript will appear here as soon as recording begins.";
                case RECORDING ->
                        "Live text is a preview. Pause is reversible; Done creates the final transcript.";
                case PAUSED ->
                        "This take is paused. Resume it, or choose Done to create the final transcript.";
                case FINALIZING, SAVING ->
                        "Please wait while the complete saved audio is processed. Your live text remains protected.";
                case UNSAVED_REVIEW ->
                        "Review and correct the final transcript if needed, then choose Save Session.";
                case SAVED ->
                        "This transcript has been saved with its timestamp events and session manifest.";
                case ERROR ->
                        "Review the available transcript, then choose Save Session to preserve recoverable data.";
            });
        }
        boolean showMicrophoneLevel = recording;
        if (transcriptMicrophoneRow != null) {
            transcriptMicrophoneRow.setVisible(showMicrophoneLevel);
            transcriptMicrophoneRow.setManaged(showMicrophoneLevel);
        }
        if (transcriptAudioLevelLabel != null) {
            transcriptAudioLevelLabel.setVisible(showMicrophoneLevel);
            transcriptAudioLevelLabel.setManaged(showMicrophoneLevel);
        }
        if (transcriptAudioLevelBar != null) {
            transcriptAudioLevelBar.setVisible(showMicrophoneLevel);
            transcriptAudioLevelBar.setManaged(showMicrophoneLevel);
        }
        boolean showFinalization = recordingWorkflowState == RecordingWorkflowState.FINALIZING;
        if (transcriptFinalizationProgressLabel != null) {
            transcriptFinalizationProgressLabel.setVisible(showFinalization);
            transcriptFinalizationProgressLabel.setManaged(showFinalization);
        }
        if (transcriptFinalizationProgressBar != null) {
            transcriptFinalizationProgressBar.setVisible(showFinalization);
            transcriptFinalizationProgressBar.setManaged(showFinalization);
        }
        boolean showSecondaryControls = switch (recordingWorkflowState) {
            case READY, RECORDING, PAUSED, UNSAVED_REVIEW, SAVED, ERROR -> true;
            case STARTING, FINALIZING, SAVING -> false;
        };
        if (transcriptSecondaryControls != null) {
            transcriptSecondaryControls.setVisible(showSecondaryControls);
            transcriptSecondaryControls.setManaged(showSecondaryControls);
        }
        if (transcriptSettingsButton != null) {
            boolean showSettings = recordingWorkflowState != RecordingWorkflowState.RECORDING;
            transcriptSettingsButton.setVisible(showSettings);
            transcriptSettingsButton.setManaged(showSettings);
            transcriptSettingsButton.setDisable(recording || transcriptBusy);
        }
        boolean showDone = recordingWorkflowState == RecordingWorkflowState.RECORDING ||
                recordingWorkflowState == RecordingWorkflowState.PAUSED;
        if (recordingDoneButton != null) {
            recordingDoneButton.setVisible(showDone);
            recordingDoneButton.setManaged(showDone);
            recordingDoneButton.setDisable(transcriptStopInProgress);
        }
        boolean showRecordMore = recordingWorkflowState == RecordingWorkflowState.UNSAVED_REVIEW;
        if (recordMoreButton != null) {
            recordMoreButton.setVisible(showRecordMore);
            recordMoreButton.setManaged(showRecordMore);
            recordMoreButton.setDisable(transcriptBusy);
        }
        if (panelExportTranscriptMenuItem != null) {
            panelExportTranscriptMenuItem.setDisable(recording || transcriptBusy ||
                    recordingWorkflowState == RecordingWorkflowState.PAUSED ||
                    transcriptFile == null || !transcriptFile.isFile());
        }
        if (panelClearEventsMenuItem != null) {
            panelClearEventsMenuItem.setDisable(recording || starting || transcriptStopInProgress ||
                    (eventLog.isEmpty() && mouseMoveLog.isEmpty()));
        }
        if (transcriptMoreButton != null) {
            boolean showMore = recordingWorkflowState != RecordingWorkflowState.RECORDING;
            transcriptMoreButton.setVisible(showMore);
            transcriptMoreButton.setManaged(showMore);
            transcriptMoreButton.setDisable(
                    (panelExportTranscriptMenuItem == null || panelExportTranscriptMenuItem.isDisable()) &&
                    (panelClearEventsMenuItem == null || panelClearEventsMenuItem.isDisable()));
        }
        if (transcriptSettingsMenuItem != null) {
            transcriptSettingsMenuItem.setDisable(recording || transcriptBusy);
        }
        if (clearLogMenuItem != null) {
            clearLogMenuItem.setDisable(recording || starting || transcriptStopInProgress ||
                    (eventLog.isEmpty() && mouseMoveLog.isEmpty()));
        }
        if (recordingWorkflowState != lastPanelWorkflowState) {
            if (recordingWorkflowState == RecordingWorkflowState.RECORDING) {
                setPanelDividerPosition(RECORDING_PANEL_DIVIDER_POSITION);
            } else if (recordingWorkflowState == RecordingWorkflowState.PAUSED ||
                    recordingWorkflowState == RecordingWorkflowState.UNSAVED_REVIEW) {
                setPanelDividerPosition(DEFAULT_PANEL_DIVIDER_POSITION);
            }
            lastPanelWorkflowState = recordingWorkflowState;
        }
        updateDevelopmentReloadGuard();
    }

    private static void updateRecordingStatusLine() {
        if (recordingStatusLabel == null || recordingStateDotLabel == null) {
            return;
        }
        String stateText = switch (recordingWorkflowState) {
            case READY -> "Ready";
            case STARTING -> transcriptResumePending ? "Resuming microphone…" : "Starting microphone…";
            case RECORDING -> "Recording";
            case PAUSED -> "Paused · " + formatDurationSeconds(
                    Math.round(recordingAudioDurationSeconds())) + " recorded";
            case FINALIZING -> "Creating final transcript…";
            case UNSAVED_REVIEW -> "Review transcript, then save";
            case SAVING -> "Saving session…";
            case SAVED -> "Session saved";
            case ERROR -> "Attention needed — session data is still available";
        };
        if (recordingWorkflowState == RecordingWorkflowState.RECORDING) {
            stateText += " · " + formatRecordingElapsed(recordingStartedInstant, Instant.now());
        }
        String detail = transcriptStatusLabel == null ? "" : transcriptStatusLabel.getText();
        if (detail != null) {
            detail = detail.replaceFirst("^Transcript:\\s*", "").trim();
            String lowerDetail = detail.toLowerCase(Locale.ROOT);
            if (!detail.isBlank() && (lowerDetail.contains("warning") || lowerDetail.contains("error") ||
                    lowerDetail.contains("clipping") || lowerDetail.contains("quiet") ||
                    lowerDetail.contains("unavailable"))) {
                stateText += " · " + detail;
            }
        }
        recordingStatusLabel.setText(stateText);
        recordingStateDotLabel.setStyle("-fx-text-fill: " + switch (recordingWorkflowState) {
            case READY -> "#98a2b3";
            case RECORDING -> "#d92d20";
            case PAUSED -> "#d97706";
            case SAVED -> "#2f9e44";
            case STARTING, FINALIZING, SAVING -> "#d97706";
            case UNSAVED_REVIEW -> "#2f80ed";
            case ERROR -> "#d92d20";
        } + ";");
    }

    static String formatRecordingElapsed(Instant origin, Instant now) {
        if (origin == null || now == null || now.isBefore(origin)) {
            return "00:00";
        }
        long totalSeconds = java.time.Duration.between(origin, now).toSeconds();
        long hours = totalSeconds / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;
        return hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    private static void updateDevelopmentReloadGuard() {
        String guardFile = System.getenv(DEVELOPMENT_RELOAD_GUARD_ENV);
        if (guardFile == null || guardFile.isBlank()) {
            return;
        }
        Path guardPath = Paths.get(guardFile).toAbsolutePath().normalize();
        boolean restartUnsafe = recordingSessionDirty || recordEvents.get() ||
                transcriptStartPending || isTranscriptProcessBusy();
        try {
            if (!restartUnsafe) {
                Files.deleteIfExists(guardPath);
                lastDevelopmentGuardState = "";
                return;
            }
            String state = recordingWorkflowState + "\n";
            if (!state.equals(lastDevelopmentGuardState) || !Files.isRegularFile(guardPath)) {
                atomicWriteString(guardPath, state);
                lastDevelopmentGuardState = state;
            }
        } catch (IOException e) {
            logger.warn("Could not update the development reload safety guard at {}", guardPath, e);
        }
    }

    private static void startRecordingSession() {
        if (isTranscriptProcessBusy()) {
            if (transcriptStatusLabel != null) {
                transcriptStatusLabel.setText(transcriptStopInProgress
                        ? "Transcript: finalizing previous recording"
                        : "Transcript: process already running");
            }
            updateLiveEventMonitorControls();
            return;
        }

        if (recordingSessionSaved && recordingSessionDirty) {
            javafx.scene.control.ButtonType choice = Dialogs.showYesNoCancelDialog(
                    "Unsaved Transcript Changes",
                    "Save the transcript changes before starting a new recording?");
            if (choice == null || choice == javafx.scene.control.ButtonType.CANCEL) {
                return;
            }
            if (choice == javafx.scene.control.ButtonType.YES && !saveTranscriptAndTimestamps()) {
                return;
            }
        }
        if (recordingSessionSaved) {
            resetWorkingSessionForNewRecording();
        }
        if (!ensureWorkingSessionDirectory()) {
            return;
        }

        TranscriptStartMode startMode = resolveTranscriptStartMode();
        if (startMode == TranscriptStartMode.CANCEL) {
            return;
        }
        if (startMode == TranscriptStartMode.NEW_TAKE && !archiveExistingTranscriptCapture()) {
            return;
        }

        clearLogsWhenRecordingStarts = startMode == TranscriptStartMode.NEW_TAKE;
        transcriptResumePending = startMode == TranscriptStartMode.RESUME;
        transcriptStartPending = true;
        recordingWorkflowState = RecordingWorkflowState.STARTING;
        recordingSessionDirty = true;
        recordEvents.set(false);
        boolean transcriptStarted = startTranscriptProcess(startMode == TranscriptStartMode.NEW_TAKE);
        if (!transcriptStarted) {
            transcriptStartPending = false;
            transcriptResumePending = false;
            clearLogsWhenRecordingStarts = false;
            recordEvents.set(false);
            recordingWorkflowState = RecordingWorkflowState.ERROR;
            updateLiveEventMonitorControls();
            return;
        }

        updateLiveEventMonitorControls();
    }

    private static boolean ensureWorkingSessionDirectory() {
        if (transcriptSessionDir != null && transcriptSessionDir.isDirectory()) {
            return true;
        }
        try {
            Path workingRoot = Paths.get(System.getProperty("java.io.tmpdir"),
                    "qupath-timestamp-recordings");
            Path workingDirectory = createUniqueArchiveDirectory(workingRoot, LocalDateTime.now());
            transcriptSessionDir = workingDirectory.toFile();
            transcriptFile = buildTranscriptFile(transcriptSessionDir);
            transcriptCaptureStarted = false;
            recordingSessionSaved = false;
            transcriptLastModified = -1L;
            transcriptLastSize = -1L;
            transcriptLastContents = "";
            logger.info("Created temporary transcript working session at {}", workingDirectory);
            return true;
        } catch (IOException e) {
            logger.error("Could not create temporary transcript working session", e);
            Dialogs.showErrorMessage("Recording Start Failed",
                    "Could not create a temporary working folder for this recording: " + e.getMessage());
            return false;
        }
    }

    private static void resetWorkingSessionForNewRecording() {
        transcriptSessionDir = null;
        transcriptFile = null;
        transcriptCaptureStarted = false;
        recordingSessionSaved = false;
        transcriptFinalizationResult = TRANSCRIPT_FINALIZATION_PENDING;
        transcriptLastExitCode = -1;
        transcriptLastModified = -1L;
        transcriptLastSize = -1L;
        transcriptLastContents = "";
        recordingStartedInstant = null;
        transcriptLiveModelReady = false;
        transcriptSilenceWarningActive = false;
        transcriptClippingWarningActive = false;
        transcriptResumePending = false;
        transcriptProcessPurpose = TranscriptProcessPurpose.NONE;
        transcriptStopIntent = TranscriptStopIntent.NONE;
        nextEventSequence = 1L;
        recordingSessionDirty = false;
        recordingWorkflowState = RecordingWorkflowState.READY;
    }

    private static TranscriptStartMode resolveTranscriptStartMode() {
        if (transcriptCaptureStarted) {
            return TranscriptStartMode.RESUME;
        }
        if (!hasExistingTranscriptCapture()) {
            return TranscriptStartMode.NEW_TAKE;
        }

        String resume = "Resume existing take";
        String newTake = "Start new take (archive existing files)";
        String cancel = "Cancel";
        String selection = Dialogs.showChoiceDialog(
                "Existing Transcript Session",
                "This session already contains transcript or audio data. Resume it, or archive it before starting a new take.",
                List.of(resume, newTake, cancel),
                resume);
        if (resume.equals(selection)) {
            return TranscriptStartMode.RESUME;
        }
        if (newTake.equals(selection)) {
            return TranscriptStartMode.NEW_TAKE;
        }
        return TranscriptStartMode.CANCEL;
    }

    private static boolean hasExistingTranscriptCapture() {
        for (File file : getTranscriptCaptureFiles()) {
            if (file.isFile() && file.length() > 0L) {
                return true;
            }
        }
        return false;
    }

    private static List<File> getTranscriptCaptureFiles() {
        if (transcriptSessionDir == null) {
            return List.of();
        }
        File workingTranscript = buildTranscriptFile(transcriptSessionDir);
        if (workingTranscript == null) {
            return List.of();
        }
        File videoDir = workingTranscript.getParentFile();
        String baseName = workingTranscript.getName();
        String stem = baseName.endsWith(".txt")
                ? baseName.substring(0, baseName.length() - 4)
                : baseName;
        List<File> captureFiles = new ArrayList<>(List.of(
                workingTranscript,
                new File(videoDir, stem + "_live.txt"),
                new File(videoDir, stem + "_audio.raw"),
                new File(videoDir, stem + "_audio.wav"),
                new File(videoDir, stem + "_audio.start.txt"),
                new File(videoDir, stem + "_segments.csv"),
                new File(videoDir, stem + "_words.csv"),
                buildEditedTranscriptFile(transcriptSessionDir)));
        SessionArtifactPaths artifactPaths = buildSessionArtifactPaths(transcriptSessionDir);
        if (artifactPaths != null) {
            captureFiles.add(artifactPaths.eventCsv().toFile());
            captureFiles.add(artifactPaths.eventJson().toFile());
            captureFiles.add(artifactPaths.cursorJson().toFile());
            captureFiles.add(artifactPaths.manifest().toFile());
        }
        return captureFiles;
    }

    private static boolean archiveExistingTranscriptCapture() {
        if (!hasExistingTranscriptCapture()) {
            return true;
        }
        File workingTranscript = buildTranscriptFile(transcriptSessionDir);
        if (workingTranscript == null || workingTranscript.getParentFile() == null) {
            return false;
        }
        List<Path[]> archivedFiles = new ArrayList<>();
        Path archiveDir = null;
        try {
            archiveDir = createUniqueArchiveDirectory(
                    workingTranscript.getParentFile().toPath().resolve("archive"),
                    LocalDateTime.now());
            for (File file : getTranscriptCaptureFiles()) {
                if (file.isFile()) {
                    Path source = file.toPath();
                    Path destination = archiveDir.resolve(file.getName());
                    Files.move(source, destination);
                    archivedFiles.add(new Path[] {source, destination});
                }
            }
            logger.info("Archived existing transcript capture to {}", archiveDir);
            return true;
        } catch (IOException e) {
            for (int i = archivedFiles.size() - 1; i >= 0; i--) {
                Path[] move = archivedFiles.get(i);
                try {
                    Files.move(move[1], move[0]);
                } catch (IOException rollbackError) {
                    e.addSuppressed(rollbackError);
                    logger.error("Failed to restore transcript file {} after archive failure",
                            move[0], rollbackError);
                }
            }
            if (archiveDir != null) {
                try {
                    Files.deleteIfExists(archiveDir);
                } catch (IOException cleanupError) {
                    e.addSuppressed(cleanupError);
                    logger.debug("Unable to remove incomplete transcript archive {}", archiveDir, cleanupError);
                }
            }
            logger.error("Failed to archive existing transcript capture", e);
            Dialogs.showErrorMessage("Could Not Start New Take",
                    "Existing transcript files could not be archived. Any files already moved were restored when possible.\n" +
                            e.getMessage());
            return false;
        }
    }

    static Path createUniqueArchiveDirectory(Path archiveRoot, LocalDateTime timestamp) throws IOException {
        Files.createDirectories(archiveRoot);
        String archiveId = timestamp.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        for (int suffix = 0; suffix < 10_000; suffix++) {
            String directoryName = suffix == 0 ? archiveId : archiveId + "_" + suffix;
            Path candidate = archiveRoot.resolve(directoryName);
            try {
                return Files.createDirectory(candidate);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // Try the next suffix without replacing an earlier take.
            }
        }
        throw new IOException("Could not create a unique transcript archive directory in " + archiveRoot);
    }

    private static boolean startTranscriptProcess(boolean resetWorkingFile) {
        if (transcriptSessionDir == null) {
            if (transcriptStatusLabel != null) {
                transcriptStatusLabel.setText("Transcript: temporary working folder unavailable");
            }
            Dialogs.showErrorMessage("Recording Start Failed",
                    "A temporary working folder could not be prepared for live transcription.");
            return false;
        }

        File sessionDir = transcriptSessionDir;
        File pythonScript = findTranscriptPythonScript();
        String pythonExecutable = findTranscriptPythonExecutable();
        if (pythonScript == null) {
            if (transcriptStatusLabel != null) {
                transcriptStatusLabel.setText("Transcript: bundled Python helper unavailable");
            }
            Dialogs.showWarningNotification(TIMESTAMP_CATEGORY,
                    "Live transcription could not be launched because the bundled Python helper could not be prepared.");
            return false;
        }

        try {
            ClinicalTranscriptSettings clinicalSettings = clinicalTranscriptSettings();
            String model = clinicalSettings.finalModel();
            String language = defaultIfBlank(transcriptLanguage.get(), DEFAULT_TRANSCRIPT_LANGUAGE);
            String device = defaultIfBlank(transcriptDevice.get(), DEFAULT_TRANSCRIPT_DEVICE);
            String chunkSeconds = clinicalSettings.liveContextSeconds();
            String computeType = clinicalSettings.computeType();
            String beamSize = clinicalSettings.beamSize();
            String bestOf = clinicalSettings.bestOf();
            String hotwords = defaultIfBlank(transcriptHotwords.get(), DEFAULT_TRANSCRIPT_HOTWORDS);
            String previousText = Boolean.toString(clinicalSettings.previousText());
            transcriptFile = buildTranscriptFile(sessionDir);
            if (resetWorkingFile) {
                resetTranscriptWorkingFile(transcriptFile);
            }
            transcriptLastModified = -1L;
            transcriptLastSize = -1L;
            transcriptFinalizationResult = TRANSCRIPT_FINALIZATION_PENDING;
            transcriptLastExitCode = -1;
            transcriptLiveModelReady = false;
            transcriptSilenceWarningActive = false;
            transcriptClippingWarningActive = false;
            updateTranscriptPartial("");
            resetTranscriptFinalizationProgress();
            if (resetWorkingFile) {
                transcriptLastContents = "";
            }
            refreshTranscriptContents();
            List<String> command = new ArrayList<>(Arrays.asList(
                    pythonExecutable,
                    "-u",
                    pythonScript.getAbsolutePath(),
                    "--output",
                    transcriptFile.getAbsolutePath(),
                    "--model",
                    model,
                    "--language",
                    language,
                    "--chunk-seconds",
                    chunkSeconds,
                    "--compute-type",
                    computeType,
                    "--beam-size",
                    beamSize,
                    "--best-of",
                    bestOf,
                    "--previous-text",
                    previousText,
                    "--hotwords",
                    hotwords));
            command.addAll(transcriptLifecycleArguments(false));
            if (!device.isBlank()) {
                command.add("--device");
                command.add(device);
            }
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            configureTranscriptProcessEnvironment(processBuilder, pythonExecutable);
            processBuilder.redirectErrorStream(true);
            transcriptProcess = processBuilder.start();
            transcriptStopInProgress = false;
            transcriptProcessPurpose = TranscriptProcessPurpose.CAPTURE;
            transcriptStopIntent = TranscriptStopIntent.NONE;
            consumeTranscriptProcessOutput(transcriptProcess);

            if (transcriptStatusLabel != null) {
                transcriptStatusLabel.setText(String.format(
                        "Transcript: starting microphone (%s, %ss)", model, chunkSeconds));
            }
            logger.info("Started live transcript process for session {} with python={} model={} language={} device={} chunk={} compute={} beam={} bestOf={} previousText={}",
                    sessionDir.getAbsolutePath(), pythonExecutable, model, language,
                    displayTranscriptDevice(device), chunkSeconds, computeType, beamSize, bestOf, previousText);
            refreshLiveEventMonitor();
            return true;
        } catch (IOException e) {
            logger.error("Failed to start live transcript process", e);
            transcriptProcess = null;
            if (transcriptStatusLabel != null) {
                transcriptStatusLabel.setText("Transcript: failed to start");
            }
            Dialogs.showErrorMessage("Transcript Launch Failed",
                    "Could not start live transcription. " + e.getMessage());
            return false;
        }
    }

    private static void startTranscriptFinalizationProcess() {
        if (transcriptSessionDir == null || transcriptFile == null) {
            recordingWorkflowState = RecordingWorkflowState.ERROR;
            if (transcriptStatusLabel != null) {
                transcriptStatusLabel.setText(
                        "Transcript: recording files are unavailable for finalization");
            }
            updateLiveEventMonitorControls();
            return;
        }

        File pythonScript = findTranscriptPythonScript();
        String pythonExecutable = findTranscriptPythonExecutable();
        if (pythonScript == null) {
            recordingWorkflowState = RecordingWorkflowState.ERROR;
            if (transcriptStatusLabel != null) {
                transcriptStatusLabel.setText("Transcript: bundled Python helper unavailable");
            }
            updateLiveEventMonitorControls();
            return;
        }

        try {
            ClinicalTranscriptSettings settings = clinicalTranscriptSettings();
            List<String> command = new ArrayList<>(Arrays.asList(
                    pythonExecutable,
                    "-u",
                    pythonScript.getAbsolutePath(),
                    "--output",
                    transcriptFile.getAbsolutePath(),
                    "--model",
                    settings.finalModel(),
                    "--language",
                    defaultIfBlank(transcriptLanguage.get(), DEFAULT_TRANSCRIPT_LANGUAGE),
                    "--compute-type",
                    settings.computeType(),
                    "--beam-size",
                    settings.beamSize(),
                    "--best-of",
                    settings.bestOf(),
                    "--previous-text",
                    Boolean.toString(settings.previousText()),
                    "--hotwords",
                    defaultIfBlank(transcriptHotwords.get(), DEFAULT_TRANSCRIPT_HOTWORDS)));
            command.addAll(transcriptLifecycleArguments(true));
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            configureTranscriptProcessEnvironment(processBuilder, pythonExecutable);
            processBuilder.redirectErrorStream(true);

            transcriptFinalizationResult = TRANSCRIPT_FINALIZATION_PENDING;
            transcriptLastExitCode = -1;
            transcriptStartPending = false;
            transcriptResumePending = false;
            transcriptStopInProgress = true;
            transcriptProcessPurpose = TranscriptProcessPurpose.FINALIZE;
            transcriptStopIntent = TranscriptStopIntent.DONE;
            recordingWorkflowState = RecordingWorkflowState.FINALIZING;
            resetTranscriptFinalizationProgress();
            showTranscriptFinalizationProgress();
            transcriptProcess = processBuilder.start();
            consumeTranscriptProcessOutput(transcriptProcess);
            if (transcriptStatusLabel != null) {
                transcriptStatusLabel.setText("Transcript: creating final transcript from saved audio");
            }
            updateLiveEventMonitorControls();
            waitForTranscriptFinalization(transcriptProcess);
        } catch (IOException e) {
            logger.error("Failed to start final transcript process", e);
            transcriptProcess = null;
            transcriptStopInProgress = false;
            transcriptProcessPurpose = TranscriptProcessPurpose.NONE;
            transcriptStopIntent = TranscriptStopIntent.NONE;
            transcriptFinalizationResult = TRANSCRIPT_FINALIZATION_FAILED;
            recordingWorkflowState = RecordingWorkflowState.ERROR;
            if (transcriptStatusLabel != null) {
                transcriptStatusLabel.setText("Transcript: failed to start finalization");
            }
            updateLiveEventMonitorControls();
            Dialogs.showErrorMessage("Transcript Finalization Failed",
                    "Could not start final transcription. " + e.getMessage());
        }
    }

    private static void pauseRecordingSession() {
        boolean wasRecording = recordEvents.get();
        recordEvents.set(false);
        transcriptStartPending = false;
        transcriptResumePending = false;
        clearLogsWhenRecordingStarts = false;
        if (wasRecording) {
            logSessionBoundary("Recording Paused");
        }
        stopTranscriptProcess(TranscriptStopIntent.PAUSE);
    }

    private static void finishRecordingSession() {
        boolean wasRecording = recordEvents.get();
        recordEvents.set(false);
        transcriptStartPending = false;
        transcriptResumePending = false;
        clearLogsWhenRecordingStarts = false;
        if (wasRecording) {
            logSessionBoundary("Recording Finished");
        }
        if (transcriptProcess != null && transcriptProcess.isAlive()) {
            stopTranscriptProcess(TranscriptStopIntent.DONE);
        } else {
            startTranscriptFinalizationProcess();
        }
    }

    private static void ensureTranscriptRefreshStarted() {
        if (transcriptRefreshTimeline != null) {
            if (transcriptRefreshTimeline.getStatus() != Animation.Status.RUNNING) {
                transcriptRefreshTimeline.play();
            }
            return;
        }

        transcriptRefreshTimeline = new Timeline(
                new KeyFrame(Duration.seconds(TRANSCRIPT_REFRESH_INTERVAL_SECONDS), e -> {
                    refreshTranscriptContents();
                    updateRecordingStatusLine();
                }));
        transcriptRefreshTimeline.setCycleCount(Animation.INDEFINITE);
        transcriptRefreshTimeline.play();
    }

    private static void resetTranscriptAudioLevelIndicator() {
        if (transcriptAudioLevelBar != null) {
            transcriptAudioLevelBar.setProgress(0);
        }
        if (transcriptAudioLevelLabel != null) {
            transcriptAudioLevelLabel.setText("Mic: waiting");
        }
    }

    private static void resetTranscriptFinalizationProgress() {
        finalizationProgressStartedNano = 0L;
        if (transcriptFinalizationProgressBar != null) {
            transcriptFinalizationProgressBar.setProgress(0);
            transcriptFinalizationProgressBar.setVisible(false);
            transcriptFinalizationProgressBar.setManaged(false);
        }
        if (transcriptFinalizationProgressLabel != null) {
            transcriptFinalizationProgressLabel.setText("Final transcript: waiting");
            transcriptFinalizationProgressLabel.setVisible(false);
            transcriptFinalizationProgressLabel.setManaged(false);
        }
    }

    private static void showTranscriptFinalizationProgress() {
        if (finalizationProgressStartedNano == 0L) {
            finalizationProgressStartedNano = System.nanoTime();
        }
        if (transcriptFinalizationProgressBar != null) {
            transcriptFinalizationProgressBar.setVisible(true);
            transcriptFinalizationProgressBar.setManaged(true);
        }
        if (transcriptFinalizationProgressLabel != null) {
            transcriptFinalizationProgressLabel.setVisible(true);
            transcriptFinalizationProgressLabel.setManaged(true);
        }
    }

    private static void updateTranscriptFinalizationProgress(double completedSeconds,
                                                              double totalSeconds) {
        showTranscriptFinalizationProgress();
        double progress = totalSeconds <= 0 ? ProgressBar.INDETERMINATE_PROGRESS
                : Math.max(0.0, Math.min(1.0, completedSeconds / totalSeconds));
        if (transcriptFinalizationProgressBar != null) {
            transcriptFinalizationProgressBar.setProgress(progress);
        }
        String etaText = "estimating remaining time";
        if (completedSeconds > 0 && totalSeconds > completedSeconds && finalizationProgressStartedNano > 0) {
            double elapsedSeconds = (System.nanoTime() - finalizationProgressStartedNano) / 1_000_000_000.0;
            long etaSeconds = Math.max(0L, Math.round(
                    elapsedSeconds * (totalSeconds - completedSeconds) / completedSeconds));
            etaText = formatDurationSeconds(etaSeconds) + " remaining";
        } else if (totalSeconds > 0 && completedSeconds >= totalSeconds) {
            etaText = "finishing files";
        }
        if (transcriptFinalizationProgressLabel != null) {
            transcriptFinalizationProgressLabel.setText(String.format(Locale.ROOT,
                    "Final transcript: %.0f%% — %s", progress < 0 ? 0 : progress * 100.0, etaText));
        }
    }

    private static String formatDurationSeconds(long seconds) {
        long safeSeconds = Math.max(0L, seconds);
        return safeSeconds >= 60
                ? String.format(Locale.ROOT, "%dm %02ds", safeSeconds / 60, safeSeconds % 60)
                : safeSeconds + "s";
    }

    static long computeFinalizeTimeoutSeconds(double recordingDurationSeconds) {
        double safeDuration = Double.isFinite(recordingDurationSeconds)
                ? Math.max(0.0, recordingDurationSeconds)
                : 0.0;
        return Math.max(600L, Math.min(14_400L, (long) Math.ceil(safeDuration * 4.0 + 300.0)));
    }

    private static double recordingAudioDurationSeconds() {
        File waveAudio = transcriptCompanionFile(transcriptFile, "_audio.wav");
        if (waveAudio == null || !waveAudio.isFile() || waveAudio.length() <= 44L) {
            return 0.0;
        }
        return (waveAudio.length() - 44L) / (16_000.0 * 2.0);
    }

    private static void updateTranscriptAudioLevelIndicator(TranscriptMessage message) {
        if (message == null || message.type() != TranscriptMessageType.AUDIO_LEVEL) {
            return;
        }
        double rms;
        try {
            rms = Double.parseDouble(message.fields().get(0));
        } catch (NumberFormatException e) {
            logger.debug("Invalid transcript audio level line: {}", message.raw());
            return;
        }
        String state = message.fields().get(1).trim();
        double scaled = Math.min(1.0, rms / 0.05);
        if (transcriptAudioLevelBar != null) {
            transcriptAudioLevelBar.setProgress(scaled);
        }
        if (transcriptAudioLevelLabel != null) {
            transcriptAudioLevelLabel.setText(String.format("Mic: %s (level %.3f)", state, rms));
        }
    }

    private static void updateTranscriptTextArea(String contents) {
        if (liveTranscriptTextArea == null) {
            return;
        }
        String resolved = suppressRunawayTranscriptLines(contents);
        liveTranscriptTextArea.setPromptText(resolved.isBlank() ? emptyTranscriptPrompt() : "");
        if (resolved.equals(liveTranscriptTextArea.getText())) {
            return;
        }

        boolean followTail = isTranscriptViewportAtTail(liveTranscriptTextArea);
        double previousScrollTop = liveTranscriptTextArea.getScrollTop();
        int previousAnchor = liveTranscriptTextArea.getAnchor();
        int previousCaret = liveTranscriptTextArea.getCaretPosition();
        TextArea targetTextArea = liveTranscriptTextArea;
        liveTranscriptTextArea.setText(resolved);
        if (followTail) {
            liveTranscriptTextArea.positionCaret(liveTranscriptTextArea.getLength());
        } else {
            int resolvedAnchor = Math.min(previousAnchor, liveTranscriptTextArea.getLength());
            int resolvedCaret = Math.min(previousCaret, liveTranscriptTextArea.getLength());
            liveTranscriptTextArea.selectRange(resolvedAnchor, resolvedCaret);
            liveTranscriptTextArea.setScrollTop(previousScrollTop);
            Platform.runLater(() -> {
                if (liveTranscriptTextArea == targetTextArea &&
                        resolved.equals(targetTextArea.getText())) {
                    targetTextArea.setScrollTop(previousScrollTop);
                }
            });
        }
    }

    private static boolean isTranscriptViewportAtTail(TextArea textArea) {
        var verticalScrollBar = textArea.lookup(".scroll-bar:vertical");
        if (verticalScrollBar instanceof ScrollBar scrollBar && scrollBar.isVisible()) {
            return shouldFollowTranscriptTail(
                    scrollBar.getValue(),
                    scrollBar.getMax(),
                    textArea.getCaretPosition(),
                    textArea.getLength());
        }
        return textArea.getCaretPosition() >= textArea.getLength();
    }

    static boolean shouldFollowTranscriptTail(
            double scrollValue,
            double scrollMaximum,
            int caretPosition,
            int textLength) {
        if (Double.isFinite(scrollValue) && Double.isFinite(scrollMaximum) && scrollMaximum > 0) {
            return scrollValue >= scrollMaximum - 0.001;
        }
        return caretPosition >= textLength;
    }

    static String suppressRunawayTranscriptLines(String contents) {
        if (contents == null || contents.isEmpty()) {
            return "";
        }
        String[] lines = contents.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            int timestampEnd = line.startsWith("[") ? line.indexOf("] ") : -1;
            String prefix = timestampEnd >= 0 ? line.substring(0, timestampEnd + 2) : "";
            String transcriptText = timestampEnd >= 0 ? line.substring(timestampEnd + 2) : line;
            String trimmed = transcriptText.trim();
            int wordCount = trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;
            if (wordCount > MAX_TRANSCRIPT_RENDER_WORDS_PER_LINE) {
                lines[index] = prefix + TRANSCRIPT_RUNAWAY_MARKER;
            }
        }
        return String.join("\n", lines);
    }

    private static void updateTranscriptPartial(String text) {
        if (transcriptPartialLabel == null) {
            return;
        }
        String resolved = suppressRunawayTranscriptLines(text).trim();
        transcriptPartialLabel.setText(resolved);
        transcriptPartialLabel.setVisible(!resolved.isBlank());
        transcriptPartialLabel.setManaged(!resolved.isBlank());
    }

    private static String emptyTranscriptPrompt() {
        return switch (recordingWorkflowState) {
            case READY, STARTING -> "Transcript text will appear when recording begins.";
            case RECORDING -> "Listening for speech…";
            case PAUSED -> "Recording is paused; Resume or choose Done.";
            case FINALIZING, SAVING -> "Processing the complete audio…";
            case UNSAVED_REVIEW, SAVED ->
                    "No speech was transcribed. You may enter a note here before saving.";
            case ERROR -> "No transcript text is available yet; recoverable session data can still be saved.";
        };
    }

    private static boolean hasStickyTranscriptStatus() {
        if (transcriptStatusLabel == null) {
            return false;
        }
        String text = transcriptStatusLabel.getText();
        return text != null && (text.startsWith("Transcript: Error:") ||
                text.startsWith("Transcript: Warning:") ||
                text.contains("Save Session") ||
                text.contains("timestamp events saved"));
    }

    private static void refreshTranscriptContents() {
        refreshTranscriptContents(false);
    }

    private static void refreshTranscriptContents(boolean force) {
        if (liveTranscriptTextArea == null || transcriptStatusLabel == null) {
            return;
        }

        if (transcriptSessionDir == null) {
            liveTranscriptTextArea.setText("");
            liveTranscriptTextArea.setPromptText(
                    "Click Start Recording to begin live transcription.");
            transcriptStatusLabel.setText("Transcript: ready to record");
            return;
        }

        if (transcriptFile == null) {
            transcriptFile = buildTranscriptFile(transcriptSessionDir);
        }

        if (transcriptFile == null) {
            liveTranscriptTextArea.setText("");
            liveTranscriptTextArea.setPromptText("Transcript file is not configured.");
            transcriptStatusLabel.setText("Transcript: file unavailable");
            return;
        }

        if (!transcriptFile.exists()) {
            liveTranscriptTextArea.setText("");
            liveTranscriptTextArea.setPromptText("Waiting for transcript file " + transcriptFile.getName());
            transcriptLastModified = -1L;
            transcriptLastSize = -1L;
            transcriptStatusLabel.setText("Transcript: waiting for file " + transcriptFile.getName());
            return;
        }

        long lastModified = transcriptFile.lastModified();
        long lastSize = transcriptFile.length();
        if (!force && lastModified == transcriptLastModified && lastSize == transcriptLastSize) {
            if (transcriptStopInProgress) {
                transcriptStatusLabel.setText("Transcript: finalizing transcript");
                return;
            }
            if (hasStickyTranscriptStatus()) {
                return;
            }
            if (transcriptProcess != null && transcriptProcess.isAlive()) {
                transcriptStatusLabel.setText("Transcript: live transcription running");
            } else {
                transcriptStatusLabel.setText("Transcript: following " + transcriptFile.getName());
            }
            return;
        }

        try {
            String contents = Files.readString(transcriptFile.toPath());
            transcriptLastModified = lastModified;
            transcriptLastSize = lastSize;
            transcriptLastContents = suppressRunawayTranscriptLines(contents);
            updateTranscriptTextArea(transcriptLastContents);
            if (transcriptStopInProgress) {
                transcriptStatusLabel.setText("Transcript: finalizing transcript");
                return;
            }
            if (hasStickyTranscriptStatus()) {
                return;
            }
            if (transcriptProcess != null && transcriptProcess.isAlive()) {
                transcriptStatusLabel.setText("Transcript: live transcription running");
            } else {
                transcriptStatusLabel.setText("Transcript: following " + transcriptFile.getName());
            }
        } catch (IOException e) {
            logger.warn("Failed to read transcript file {}", transcriptFile, e);
            updateTranscriptTextArea(transcriptLastContents);
            transcriptStatusLabel.setText("Transcript: read failed for " + transcriptFile.getName());
        }
    }

    private static String defaultIfBlank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static void configureMonitorButton(Button button) {
        button.setMaxWidth(Double.MAX_VALUE);
        button.setMinHeight(30);
        button.setWrapText(true);
    }

    private static String displayTranscriptDevice(String device) {
        String normalized = defaultIfBlank(device, DEFAULT_TRANSCRIPT_DEVICE);
        return normalized.isBlank() ? "default" : normalized;
    }

    private static TranscriptInputDeviceOption defaultTranscriptInputDeviceOption() {
        return new TranscriptInputDeviceOption(DEFAULT_TRANSCRIPT_DEVICE, "default - System Default");
    }

    private static TranscriptInputDeviceOption parseTranscriptInputDeviceOption(String line) {
        TranscriptMessage message = parseTranscriptMessage(line);
        if (message.type() != TranscriptMessageType.DEVICE) {
            return null;
        }
        String value = message.fields().get(0).trim();
        String label = message.fields().get(1).trim();
        if (label.isBlank()) {
            label = value.isBlank() ? defaultTranscriptInputDeviceOption().label() : value;
        }
        return new TranscriptInputDeviceOption(value, label);
    }

    private static File findTranscriptPythonScript() {
        for (Path root : getSearchRoots()) {
            Path current = root;
            for (int i = 0; current != null && i < 8; i++) {
                Path candidate = current.resolve("scripts").resolve("live_whisper_demo.py");
                if (Files.isRegularFile(candidate)) {
                    return candidate.toFile();
                }
                current = current.getParent();
            }
        }
        return extractBundledTranscriptPythonScript();
    }

    private static File extractBundledTranscriptPythonScript() {
        String resourcePath = "/qupath/ext/timestamp/scripts/live_whisper_demo.py";
        try (InputStream input = TimeStamp.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                logger.error("Bundled transcript helper resource is missing: {}", resourcePath);
                return null;
            }
            String configuredUserPath = PathPrefs.userPathProperty().get();
            Path userDirectory = configuredUserPath == null || configuredUserPath.isBlank()
                    ? PathPrefs.getDefaultQuPathUserDirectory()
                    : Paths.get(configuredUserPath);
            Path target = userDirectory
                    .resolve("timestamp")
                    .resolve("live_whisper_demo.py");
            Files.createDirectories(target.getParent());
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            return target.toFile();
        } catch (IOException e) {
            logger.error("Unable to extract bundled transcript helper", e);
            return null;
        }
    }

    private static String findTranscriptPythonExecutable() {
        String configured = defaultIfBlank(transcriptPythonExecutable.get(), "");
        if (!configured.isBlank()) {
            return configured;
        }
        Path doctorRuntimePython = doctorRuntimePython(getQuPathUserDirectory(), isWindows());
        if (Files.isRegularFile(doctorRuntimePython)) {
            return doctorRuntimePython.toAbsolutePath().toString();
        }
        for (Path root : getSearchRoots()) {
            Path current = root;
            for (int i = 0; current != null && i < 8; i++) {
                Path unixPython = current.resolve(".venv-whisper").resolve("bin").resolve("python");
                if (Files.isRegularFile(unixPython)) {
                    return unixPython.toAbsolutePath().toString();
                }
                Path windowsPython = current.resolve(".venv-whisper").resolve("Scripts").resolve("python.exe");
                if (Files.isRegularFile(windowsPython)) {
                    return windowsPython.toAbsolutePath().toString();
                }
                current = current.getParent();
            }
        }
        return "python3";
    }

    private static Path getQuPathUserDirectory() {
        String configuredUserPath = PathPrefs.userPathProperty().get();
        return configuredUserPath == null || configuredUserPath.isBlank()
                ? PathPrefs.getDefaultQuPathUserDirectory()
                : Paths.get(configuredUserPath);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    static Path doctorRuntimePython(Path qupathUserDirectory, boolean windows) {
        Path environment = qupathUserDirectory.resolve("timestamp").resolve("runtime").resolve(".venv");
        return windows
                ? environment.resolve("Scripts").resolve("python.exe")
                : environment.resolve("bin").resolve("python");
    }

    static Path doctorModelCache(Path qupathUserDirectory) {
        return qupathUserDirectory.resolve("timestamp").resolve("model-cache");
    }

    private static void configureTranscriptProcessEnvironment(
            ProcessBuilder processBuilder, String pythonExecutable) {
        Path doctorPython = doctorRuntimePython(getQuPathUserDirectory(), isWindows())
                .toAbsolutePath().normalize();
        try {
            Path selectedPython = Paths.get(pythonExecutable).toAbsolutePath().normalize();
            if (selectedPython.equals(doctorPython)) {
                processBuilder.environment().put(
                        "HF_HOME", doctorModelCache(getQuPathUserDirectory()).toAbsolutePath().toString());
            }
        } catch (RuntimeException e) {
            logger.debug("Transcript Python path is not a local filesystem path: {}", pythonExecutable);
        }
    }

    private static List<TranscriptInputDeviceOption> loadTranscriptInputDevices() {
        List<TranscriptInputDeviceOption> options = new ArrayList<>();
        options.add(defaultTranscriptInputDeviceOption());

        File pythonScript = findTranscriptPythonScript();
        String pythonExecutable = findTranscriptPythonExecutable();
        if (pythonScript == null) {
            logger.info("Transcript input device lookup unavailable: Python helper not found");
            return options;
        }

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    pythonExecutable,
                    pythonScript.getAbsolutePath(),
                    "--list-devices");
            configureTranscriptProcessEnvironment(processBuilder, pythonExecutable);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                logger.warn("Transcript input device lookup timed out");
                return options;
            }

            List<String> outputLines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputLines.add(line);
                }
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                logger.warn("Transcript input device lookup failed with exit code {} and output {}", exitCode, outputLines);
                return options;
            }

            for (String line : outputLines) {
                TranscriptInputDeviceOption option = parseTranscriptInputDeviceOption(line);
                if (option == null) {
                    continue;
                }
                boolean alreadyPresent = options.stream().anyMatch(existing -> existing.value().equals(option.value()));
                if (!alreadyPresent) {
                    options.add(option);
                } else if (option.value().isBlank()) {
                    options.set(0, option);
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to query transcript input devices", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Transcript input device lookup interrupted", e);
        }

        return options;
    }

    private static TranscriptInputDeviceOption selectTranscriptInputDeviceOption(
            List<TranscriptInputDeviceOption> options,
            String currentDevice) {
        String normalized = currentDevice == null ? DEFAULT_TRANSCRIPT_DEVICE : currentDevice.trim();
        for (TranscriptInputDeviceOption option : options) {
            if (option.value().equals(normalized)) {
                return option;
            }
        }
        if (normalized.isBlank()) {
            return defaultTranscriptInputDeviceOption();
        }
        return new TranscriptInputDeviceOption(normalized, normalized + " (saved)");
    }

    private static String normalizeTranscriptInputDeviceText(String text) {
        if (text == null) {
            return DEFAULT_TRANSCRIPT_DEVICE;
        }
        String trimmed = text.trim();
        if (trimmed.isBlank() || trimmed.equalsIgnoreCase("default")) {
            return DEFAULT_TRANSCRIPT_DEVICE;
        }
        return trimmed;
    }

    private static String resolveTranscriptInputDeviceText(
            List<TranscriptInputDeviceOption> options,
            String text) {
        String normalized = normalizeTranscriptInputDeviceText(text);
        for (TranscriptInputDeviceOption option : options) {
            if (option.value().equals(normalized) || option.label().equals(text)) {
                return option.value();
            }
        }
        return normalized;
    }

    private static String selectedTranscriptInputDevice(ComboBox<TranscriptInputDeviceOption> deviceCombo) {
        TranscriptInputDeviceOption selected = deviceCombo.getValue();
        if (selected != null) {
            return normalizeTranscriptInputDeviceText(selected.value());
        }
        return normalizeTranscriptInputDeviceText(deviceCombo.getEditor().getText());
    }

    private static void refreshTranscriptInputDevices(ComboBox<TranscriptInputDeviceOption> deviceCombo) {
        String currentValue = selectedTranscriptInputDevice(deviceCombo);
        deviceCombo.setDisable(true);
        Thread lookupThread = new Thread(() -> {
            List<TranscriptInputDeviceOption> options = loadTranscriptInputDevices();
            Platform.runLater(() -> {
                deviceCombo.getItems().setAll(options);
                deviceCombo.setValue(selectTranscriptInputDeviceOption(options, currentValue));
                deviceCombo.setDisable(false);
            });
        }, "timestamp-audio-device-lookup");
        lookupThread.setDaemon(true);
        lookupThread.start();
    }

    private static void testTranscriptMicrophone(String device) {
        File pythonScript = findTranscriptPythonScript();
        String pythonExecutable = findTranscriptPythonExecutable();
        if (pythonScript == null) {
            Dialogs.showErrorMessage("Microphone Test", "The bundled microphone helper is unavailable.");
            return;
        }
        List<String> command = new ArrayList<>(List.of(
                pythonExecutable, "-u", pythonScript.getAbsolutePath(),
                "--check-audio", "--check-seconds", "8"));
        if (device != null && !device.isBlank()) {
            command.add("--device");
            command.add(device);
        }

        Dialog<javafx.scene.control.ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Test Microphone");
        if (qupathGui != null) {
            dialog.initOwner(qupathGui.getStage());
        }
        Label status = new Label("Opening microphone… Speak normally for a few seconds.");
        ProgressBar meter = new ProgressBar(0);
        meter.setMaxWidth(Double.MAX_VALUE);
        VBox content = new VBox(10, status, meter);
        content.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);

        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            configureTranscriptProcessEnvironment(builder, pythonExecutable);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            dialog.setOnHidden(e -> {
                if (process.isAlive()) {
                    process.destroy();
                }
            });
            Thread outputThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        TranscriptMessage message = parseTranscriptMessage(line);
                        Platform.runLater(() -> {
                            switch (message.type()) {
                                case AUDIO_CHECK_READY -> status.setText("Microphone open — speak now.");
                                case AUDIO_LEVEL -> {
                                    double rms = Double.parseDouble(message.fields().get(0));
                                    meter.setProgress(Math.min(1.0, rms / 0.05));
                                    status.setText("Microphone: " + message.fields().get(1));
                                }
                                case AUDIO_CLIPPING -> status.setText(
                                        "Microphone clipping detected (" + message.fields().get(0) +
                                                "% at full scale). Lower the input gain.");
                                case AUDIO_CHECK_RESULT -> status.setText(
                                        "Test complete: " + message.fields().get(1) +
                                                " (peak " + message.fields().get(0) + ")");
                                case LOG -> {
                                    if (message.raw().startsWith("Error:")) {
                                        status.setText(message.raw());
                                    }
                                }
                                default -> { }
                            }
                        });
                    }
                } catch (IOException e) {
                    logger.debug("Microphone test output closed", e);
                }
            }, "timestamp-microphone-test");
            outputThread.setDaemon(true);
            outputThread.start();
            dialog.show();
        } catch (IOException e) {
            Dialogs.showErrorMessage("Microphone Test", "Could not start microphone test: " + e.getMessage());
        }
    }

    private static String selectTranscriptLanguage(String languageCode) {
        String normalized = defaultIfBlank(languageCode, DEFAULT_TRANSCRIPT_LANGUAGE);
        if ("auto".equalsIgnoreCase(normalized)) {
            return AVAILABLE_TRANSCRIPT_LANGUAGES.get(0);
        }
        for (String option : AVAILABLE_TRANSCRIPT_LANGUAGES) {
            if (option.startsWith(normalized + " ")) {
                return option;
            }
        }
        return "en - English";
    }

    private static String languageCodeFromSelection(String selection) {
        String normalized = defaultIfBlank(selection, "en - English");
        if (normalized.startsWith("auto")) {
            return "auto";
        }
        int dash = normalized.indexOf(" - ");
        return dash > 0 ? normalized.substring(0, dash) : normalized;
    }

    private static void updateTranscriptSettingsSummary() {
        if (transcriptSettingsButton == null) {
            return;
        }

        String device = defaultIfBlank(transcriptDevice.get(), DEFAULT_TRANSCRIPT_DEVICE);
        String languageSelection = selectTranscriptLanguage(
                defaultIfBlank(transcriptLanguage.get(), DEFAULT_TRANSCRIPT_LANGUAGE));
        int separator = languageSelection.indexOf(" - ");
        String languageName = separator >= 0
                ? languageSelection.substring(separator + 3)
                : languageSelection;
        String summary = String.format(
                "Clinical High Accuracy • Microphone: %s • Language: %s",
                device.isBlank() ? "System Default" : device,
                languageName);
        transcriptSettingsButton.setTooltip(new Tooltip(
                "Choose language and microphone, then test the input level.\nCurrent: " + summary));
    }

    private static void showTranscriptSettingsDialog() {
        if (recordEvents.get() || isTranscriptProcessBusy()) {
            if (transcriptStatusLabel != null) {
                transcriptStatusLabel.setText("Transcript: pause recording before changing settings");
            }
            Dialogs.showWarningNotification(TIMESTAMP_CATEGORY,
                    "Pause recording before changing transcript settings.");
            return;
        }

        Dialog<javafx.scene.control.ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Recorder Settings");
        if (qupathGui != null) {
            dialog.initOwner(qupathGui.getStage());
        }

        var languageCombo = new ComboBox<String>();
        languageCombo.getItems().addAll(AVAILABLE_TRANSCRIPT_LANGUAGES);
        languageCombo.setMaxWidth(Double.MAX_VALUE);
        languageCombo.setValue(selectTranscriptLanguage(defaultIfBlank(transcriptLanguage.get(), DEFAULT_TRANSCRIPT_LANGUAGE)));

        List<TranscriptInputDeviceOption> deviceOptions = new ArrayList<>();
        deviceOptions.add(defaultTranscriptInputDeviceOption());
        String savedDevice = defaultIfBlank(transcriptDevice.get(), DEFAULT_TRANSCRIPT_DEVICE);
        if (!savedDevice.isBlank()) {
            deviceOptions.add(new TranscriptInputDeviceOption(savedDevice, savedDevice + " (saved)"));
        }
        var deviceCombo = new ComboBox<TranscriptInputDeviceOption>();
        deviceCombo.setEditable(true);
        deviceCombo.setMaxWidth(Double.MAX_VALUE);
        deviceCombo.getItems().setAll(deviceOptions);
        deviceCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(TranscriptInputDeviceOption option) {
                return option == null ? "" : option.label();
            }

            @Override
            public TranscriptInputDeviceOption fromString(String text) {
                String normalized = resolveTranscriptInputDeviceText(deviceCombo.getItems(), text);
                return selectTranscriptInputDeviceOption(deviceCombo.getItems(), normalized);
            }
        });
        deviceCombo.setValue(selectTranscriptInputDeviceOption(deviceOptions, defaultIfBlank(transcriptDevice.get(), DEFAULT_TRANSCRIPT_DEVICE)));
        var refreshDevicesButton = new Button("Refresh");
        refreshDevicesButton.setOnAction(e -> refreshTranscriptInputDevices(deviceCombo));
        var testMicrophoneButton = new Button("Test microphone");
        testMicrophoneButton.setOnAction(e -> testTranscriptMicrophone(
                selectedTranscriptInputDevice(deviceCombo)));
        var deviceBox = new HBox(8, deviceCombo, refreshDevicesButton, testMicrophoneButton);
        HBox.setHgrow(deviceCombo, Priority.ALWAYS);
        refreshTranscriptInputDevices(deviceCombo);
        var hotwordsArea = new TextArea(defaultIfBlank(transcriptHotwords.get(), DEFAULT_TRANSCRIPT_HOTWORDS));
        hotwordsArea.setPrefRowCount(3);
        hotwordsArea.setWrapText(true);
        var accuracyLabel = new Label(
                "Automatic Clinical High Accuracy — fast live preview, then large-v3 for the final saved transcript");
        accuracyLabel.setWrapText(true);
        var settingsHint = new Label(
                "The recognition model and decoding strength are managed automatically. " +
                        "Choose only the microphone, language, and any case-specific terminology. " +
                        "Live context is a maximum uncommitted-audio buffer, not a repeated decode window.");
        settingsHint.setWrapText(true);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
        grid.addRow(0, new Label("Accuracy"), accuracyLabel);
        grid.addRow(1, new Label("Language"), languageCombo);
        grid.addRow(2, new Label("Input device"), deviceBox);
        grid.addRow(3, new Label("Pathology terms"), hotwordsArea);
        grid.add(settingsHint, 0, 4, 2, 1);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK,
                javafx.scene.control.ButtonType.CANCEL);

        var result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != javafx.scene.control.ButtonType.OK) {
            return;
        }
        transcriptLanguage.set(languageCodeFromSelection(languageCombo.getValue()));
        transcriptDevice.set(selectedTranscriptInputDevice(deviceCombo));
        transcriptHotwords.set(defaultIfBlank(hotwordsArea.getText(), DEFAULT_TRANSCRIPT_HOTWORDS));
        updateTranscriptSettingsSummary();
    }

    private static File buildTranscriptFile(File sessionDir) {
        if (sessionDir == null) {
            return null;
        }

        File videoDir = new File(sessionDir, "video");
        String sessionId = sessionDir.getName();
        return new File(videoDir, sessionId + "_transcript.txt");
    }

    private static File buildEditedTranscriptFile(File sessionDir) {
        if (sessionDir == null) {
            return null;
        }

        File videoDir = new File(sessionDir, "video");
        String sessionId = sessionDir.getName();
        return new File(videoDir, sessionId + "_transcript_edited.txt");
    }

    private static void resetTranscriptWorkingFile(File file) throws IOException {
        if (file == null) {
            return;
        }
        Path path = file.toPath();
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static void exportTranscript() {
        if (recordingWorkflowState == RecordingWorkflowState.PAUSED) {
            Dialogs.showWarningNotification(TIMESTAMP_CATEGORY,
                    "Choose Done and wait for the final transcript before exporting.");
            return;
        }
        String transcriptText = liveTranscriptTextArea == null ? "" : liveTranscriptTextArea.getText();
        if (transcriptText == null || transcriptText.isBlank()) {
            Dialogs.showWarningNotification(TIMESTAMP_CATEGORY, "Transcript is empty. Nothing to export.");
            return;
        }

        File initialFile = transcriptFile;
        File file = FileChoosers.promptToSaveFile("Export Transcript", initialFile,
                FileChoosers.createExtensionFilter("Text files", ".txt"));
        if (file == null) {
            return;
        }

        try {
            Files.writeString(file.toPath(), transcriptText,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Dialogs.showInfoNotification(TIMESTAMP_CATEGORY,
                    String.format("Transcript exported to:%n%s", file.getName()));
            logger.info("Transcript exported to {}", file.getAbsolutePath());
        } catch (IOException e) {
            logger.error("Failed to export transcript", e);
            Dialogs.showErrorMessage("Export Failed",
                    "Failed to export transcript: " + e.getMessage());
        }
    }

    private static boolean saveTranscriptAndTimestamps() {
        if (recordingWorkflowState == RecordingWorkflowState.PAUSED) {
            Dialogs.showWarningNotification(TIMESTAMP_CATEGORY,
                    "Choose Done and wait for the final transcript before saving.");
            return false;
        }
        if (recordEvents.get() || transcriptStartPending || isTranscriptProcessBusy()) {
            Dialogs.showWarningNotification(TIMESTAMP_CATEGORY,
                    "Pause recording and wait for transcript finalization before saving.");
            return false;
        }

        if (transcriptSessionDir == null) {
            Dialogs.showWarningNotification(TIMESTAMP_CATEGORY,
                    "Start a recording before saving.");
            return false;
        }

        if (liveTranscriptTextArea == null) {
            return false;
        }

        if (transcriptFile == null) {
            transcriptFile = buildTranscriptFile(transcriptSessionDir);
        }
        if (transcriptFile == null) {
            Dialogs.showErrorMessage("Recording Save Failed",
                    "Transcript file could not be determined for the current recording.");
            return false;
        }

        SaveOptions saveOptions = chooseRecordingSaveOptions();
        if (saveOptions == null) {
            return false;
        }
        File destinationDirectory = saveOptions.sessionDirectory();
        File destinationTranscript = buildTranscriptFile(destinationDirectory);
        if (destinationTranscript == null) {
            Dialogs.showErrorMessage("Recording Save Failed",
                    "Transcript file could not be determined for the selected folder.");
            return false;
        }
        if (!(recordingSessionSaved && destinationDirectory.equals(lastSavedSessionDir)) &&
                hasSavedRecordingArtifacts(destinationDirectory) &&
                !Dialogs.showYesNoDialog("Replace Saved Recording",
                        "The selected folder already contains TimeStamp recording files. Replace them with this recording?")) {
            return false;
        }

        String transcriptText = liveTranscriptTextArea.getText();
        if (transcriptText == null) {
            transcriptText = "";
        }

        try {
            recordingWorkflowState = RecordingWorkflowState.SAVING;
            updateLiveEventMonitorControls();
            Files.createDirectories(destinationDirectory.toPath());
            markDestinationSavePending(destinationDirectory, destinationTranscript);
            atomicWriteString(destinationTranscript.toPath(), transcriptText);
            validateExactTextFile(destinationTranscript.toPath(), transcriptText,
                    "displayed transcript");
            copyWorkingRecordingFiles(transcriptFile, destinationTranscript, saveOptions.includeRawAudio());
            validateTranscriptTimingFiles(destinationTranscript, transcriptFinalizationResult);
            transcriptLastContents = transcriptText;
            boolean saved = saveSessionArtifacts(destinationDirectory, destinationTranscript,
                    transcriptFinalizationResult, transcriptLastExitCode);
            if (!saved) {
                throw new IOException("One or more session artifacts could not be written.");
            }
            lastSavedSessionDir = destinationDirectory;
            recordingSessionSaved = true;
            recordingSessionDirty = false;
            recordingWorkflowState = RecordingWorkflowState.SAVED;
            atomicWriteString(transcriptSessionDir.toPath().resolve(".saved"),
                    destinationDirectory.getAbsolutePath());
            String status = buildSavedStatusText(transcriptFinalizationResult);
            if (transcriptStatusLabel != null) {
                transcriptStatusLabel.setText(status);
            }
            Dialogs.showInfoNotification(TIMESTAMP_CATEGORY,
                    String.format("Transcript and %d timestamp events saved to:%n%s",
                            eventLog.size(), destinationDirectory.getAbsolutePath()));
            logger.info("User saved transcript and timestamp artifacts to {}",
                    destinationDirectory.getAbsolutePath());
            updateLiveEventMonitorControls();
            return true;
        } catch (IOException e) {
            logger.error("Failed to save transcript and timestamps", e);
            if (transcriptStatusLabel != null) {
                transcriptStatusLabel.setText("Transcript: Error: recording could not be saved");
            }
            recordingSessionDirty = true;
            recordingWorkflowState = RecordingWorkflowState.ERROR;
            updateLiveEventMonitorControls();
            Dialogs.showErrorMessage("Recording Save Failed",
                    "Could not save transcript and timestamps: " + e.getMessage());
            return false;
        }
    }

    private static void markDestinationSavePending(File destinationDirectory,
                                                   File destinationTranscript) throws IOException {
        SessionArtifactPaths paths = buildSessionArtifactPaths(destinationDirectory);
        if (paths == null) {
            throw new IOException("Could not prepare the selected recording folder.");
        }
        atomicWriteString(paths.manifest(), buildRecordingManifest(
                paths, destinationDirectory, destinationTranscript,
                TRANSCRIPT_FINALIZATION_PENDING, -1, eventLog.size(), mouseMoveLog.size()));
    }

    private static SaveOptions chooseRecordingSaveOptions() {
        File initialParent = resolveInitialSaveParent();
        String initialName = recordingSessionSaved && lastSavedSessionDir != null
                ? lastSavedSessionDir.getName()
                : (transcriptSessionDir == null ? LocalDateTime.now().format(
                        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")) : transcriptSessionDir.getName());

        Dialog<javafx.scene.control.ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Save Clinical Session");
        if (qupathGui != null) {
            dialog.initOwner(qupathGui.getStage());
        }
        TextField parentField = new TextField(initialParent == null ? "" : initialParent.getAbsolutePath());
        parentField.setEditable(false);
        TextField nameField = new TextField(initialName);
        CheckBox includeAudioBox = new CheckBox("Include audio recording (may contain sensitive speech)");
        includeAudioBox.setSelected(false);
        Button browseButton = new Button("Browse…");
        browseButton.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Choose Parent Folder for Recording Session");
            File current = new File(parentField.getText());
            if (current.isDirectory()) {
                chooser.setInitialDirectory(current);
            }
            File selected = chooser.showDialog(qupathGui == null ? null : qupathGui.getStage());
            if (selected != null) {
                parentField.setText(selected.getAbsolutePath());
            }
        });
        HBox parentBox = new HBox(8, parentField, browseButton);
        HBox.setHgrow(parentField, Priority.ALWAYS);
        Label privacyHint = new Label(
                "Transcript and timestamp files are always saved locally. Raw audio is excluded by default.");
        privacyHint.setWrapText(true);
        GridPane content = new GridPane();
        content.setHgap(8);
        content.setVgap(8);
        content.setPadding(new Insets(10));
        content.addRow(0, new Label("Parent folder"), parentBox);
        content.addRow(1, new Label("Session name"), nameField);
        content.add(includeAudioBox, 1, 2);
        content.add(privacyHint, 0, 3, 2, 1);
        GridPane.setHgrow(parentBox, Priority.ALWAYS);
        GridPane.setHgrow(nameField, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        javafx.scene.control.ButtonType saveButtonType = new javafx.scene.control.ButtonType(
                "Save", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(
                saveButtonType, javafx.scene.control.ButtonType.CANCEL);
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(saveButtonType);
        Runnable updateSaveAvailability = () -> saveButton.setDisable(
                !new File(parentField.getText()).isDirectory() ||
                        !isValidSessionName(nameField.getText()));
        nameField.textProperty().addListener((observable, oldValue, newValue) ->
                updateSaveAvailability.run());
        parentField.textProperty().addListener((observable, oldValue, newValue) ->
                updateSaveAvailability.run());
        updateSaveAvailability.run();
        var result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != saveButtonType) {
            return null;
        }
        File parent = new File(parentField.getText());
        String sessionName = nameField.getText() == null ? "" : nameField.getText().trim();
        if (!parent.isDirectory() || !isValidSessionName(sessionName)) {
            Dialogs.showErrorMessage("Invalid Save Location",
                    "Choose an existing parent folder and enter a session name without path separators.");
            return null;
        }
        return new SaveOptions(new File(parent, sessionName), includeAudioBox.isSelected());
    }

    private static File resolveInitialSaveParent() {
        if (lastSavedSessionDir != null && lastSavedSessionDir.getParentFile() != null &&
                lastSavedSessionDir.getParentFile().isDirectory()) {
            return lastSavedSessionDir.getParentFile();
        }
        if (qupathGui != null && qupathGui.getProject() != null && qupathGui.getProject().getPath() != null) {
            File projectPath = qupathGui.getProject().getPath().toFile();
            File projectDirectory = projectPath.isDirectory() ? projectPath : projectPath.getParentFile();
            if (projectDirectory != null && projectDirectory.isDirectory()) {
                return projectDirectory;
            }
        }
        return new File(System.getProperty("user.home"));
    }

    static boolean isValidSessionName(String sessionName) {
        return sessionName != null && !sessionName.isBlank() &&
                !sessionName.equals(".") && !sessionName.equals("..") &&
                !sessionName.contains("/") && !sessionName.contains("\\");
    }

    private static boolean hasSavedRecordingArtifacts(File directory) {
        File targetTranscript = buildTranscriptFile(directory);
        SessionArtifactPaths paths = buildSessionArtifactPaths(directory);
        return (targetTranscript != null && targetTranscript.isFile()) ||
                (paths != null && (paths.eventCsv().toFile().isFile() ||
                        paths.eventJson().toFile().isFile() ||
                        paths.cursorJson().toFile().isFile() ||
                        paths.manifest().toFile().isFile()));
    }

    private static void copyWorkingRecordingFiles(File sourceTranscript,
                                                  File destinationTranscript,
                                                  boolean includeRawAudio) throws IOException {
        if (sourceTranscript == null || destinationTranscript == null) {
            return;
        }
        copyOrRemoveManagedFile(sourceTranscript,
                transcriptCompanionFile(destinationTranscript, "_timed.txt"), true);
        for (String suffix : List.of("_live.txt", "_segments.csv", "_words.csv")) {
            copyOrRemoveManagedCompanion(sourceTranscript, destinationTranscript, suffix, true);
        }
        for (String suffix : List.of("_audio.raw", "_audio.wav", "_audio.start.txt")) {
            copyOrRemoveManagedCompanion(sourceTranscript, destinationTranscript, suffix, includeRawAudio);
        }
    }

    private static void copyOrRemoveManagedCompanion(File sourceTranscript,
                                                     File destinationTranscript,
                                                     String suffix,
                                                     boolean include) throws IOException {
        File source = transcriptCompanionFile(sourceTranscript, suffix);
        File destination = transcriptCompanionFile(destinationTranscript, suffix);
        copyOrRemoveManagedFile(source, destination, include);
    }

    private static void copyOrRemoveManagedFile(File source, File destination,
                                                boolean include) throws IOException {
        if (destination == null) {
            return;
        }
        if (include && source != null && source.isFile()) {
            copyFileAtomically(source, destination);
        } else {
            Files.deleteIfExists(destination.toPath());
        }
    }

    private static void validateTranscriptTimingFiles(File destinationTranscript,
                                                      String finalizationResult) throws IOException {
        if (!("final".equals(finalizationResult) ||
                (finalizationResult != null && finalizationResult.startsWith("live-fallback")))) {
            return;
        }
        for (String suffix : List.of("_segments.csv", "_words.csv")) {
            File timingFile = transcriptCompanionFile(destinationTranscript, suffix);
            if (timingFile == null || !timingFile.isFile() || timingFile.length() == 0L) {
                throw new IOException("Required transcript timing file was not saved: " + suffix);
            }
        }
        File timedTranscript = transcriptCompanionFile(destinationTranscript, "_timed.txt");
        if (timedTranscript == null || !timedTranscript.isFile()) {
            throw new IOException("The machine transcript linked to transcript timings was not saved.");
        }
    }

    static void copyFileAtomically(File source, File destination) throws IOException {
        if (source == null || destination == null || !source.isFile() ||
                source.toPath().equals(destination.toPath())) {
            return;
        }
        Path parent = destination.toPath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = destination.toPath().resolveSibling(destination.getName() + ".tmp");
        Files.copy(source.toPath(), temporary, StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(temporary, destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<Path> getSearchRoots() {
        List<Path> roots = new ArrayList<>();
        roots.add(Paths.get("").toAbsolutePath());

        try {
            Path codeSource = Paths.get(TimeStamp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            roots.add(Files.isDirectory(codeSource) ? codeSource : codeSource.getParent());
        } catch (Exception e) {
            logger.debug("Unable to resolve code source path for transcript launcher lookup", e);
        }

        return roots;
    }

    private static void consumeTranscriptProcessOutput(Process process) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    TranscriptMessage message = parseTranscriptMessage(line);
                    String outputLine = message.raw();
                    if (message.type() == TranscriptMessageType.FINALIZATION_RESULT) {
                        // Capture the protocol result before the process waiter commits session files.
                        transcriptFinalizationResult = message.fields().get(0).trim();
                    }
                    if (message.type() == TranscriptMessageType.AUDIO_LEVEL) {
                        logger.debug("Transcript process: {}", outputLine);
                    } else if (message.type() == TranscriptMessageType.MALFORMED) {
                        logger.warn("Ignored malformed transcript protocol message: {}", outputLine);
                    } else {
                        logger.info("Transcript process: {}", outputLine);
                    }
                    Platform.runLater(() -> {
                        if (transcriptProcess != process) {
                            return;
                        }
                        switch (message.type()) {
                            case AUDIO_LEVEL -> updateTranscriptAudioLevelIndicator(message);
                            case TRANSCRIPT_UPDATED -> {
                                refreshTranscriptContents(true);
                                if (transcriptStatusLabel != null && !transcriptClippingWarningActive) {
                                    transcriptStatusLabel.setText(
                                            "Transcript: receiving fast preview; final accuracy after Done");
                                }
                            }
                            case TRANSCRIPT_PARTIAL ->
                                    updateTranscriptPartial(message.fields().get(0));
                            case TRANSCRIPT_READY -> {
                                if (!transcriptStartPending || !process.isAlive()) {
                                    return;
                                }
                                boolean resumedCapture = transcriptResumePending;
                                if (clearLogsWhenRecordingStarts) {
                                    eventLog.clear();
                                    mouseMoveLog.clear();
                                    clearLogsWhenRecordingStarts = false;
                                }
                                transcriptStartPending = false;
                                transcriptResumePending = false;
                                transcriptCaptureStarted = true;
                                recordingWorkflowState = RecordingWorkflowState.RECORDING;
                                recordingSessionDirty = true;
                                recordEvents.set(true);
                                if (transcriptStatusLabel != null && !transcriptClippingWarningActive) {
                                    transcriptStatusLabel.setText(
                                            "Transcript: recording audio; warming speech model");
                                }
                                logSessionBoundary(resumedCapture ? "Recording Resumed" : "Recording Started");
                                updateLiveEventMonitorControls();
                                refreshLiveEventMonitor();
                            }
                            case RECORDING_ORIGIN -> {
                                Instant origin = Instant.parse(message.fields().get(0));
                                boolean firstOrigin = recordingStartedInstant == null;
                                recordingStartedInstant = origin;
                                if (firstOrigin) {
                                    nextEventSequence = Math.max(1L, nextEventSequence);
                                }
                                recordingSessionDirty = true;
                            }
                            case LIVE_MODEL_READY -> {
                                transcriptLiveModelReady = true;
                                if (transcriptStatusLabel != null && !transcriptClippingWarningActive) {
                                    transcriptStatusLabel.setText(
                                            "Transcript: listening; live text is a fast preview");
                                }
                            }
                            case AUDIO_CLIPPING -> {
                                transcriptClippingWarningActive = true;
                                String clippedPercent = message.fields().get(0);
                                if (transcriptStatusLabel != null) {
                                    transcriptStatusLabel.setText(
                                            "Transcript: Warning: microphone clipping detected (" +
                                                    clippedPercent + "% at full scale); lower the input gain");
                                }
                                Dialogs.showWarningNotification(TIMESTAMP_CATEGORY,
                                        "Microphone clipping detected. Lower the input gain before continuing.");
                            }
                            case AUDIO_SILENT -> {
                                if (!transcriptSilenceWarningActive) {
                                    transcriptSilenceWarningActive = true;
                                    if (transcriptStatusLabel != null && !transcriptClippingWarningActive) {
                                        transcriptStatusLabel.setText(
                                                "Transcript: Warning: microphone has been quiet for " +
                                                        message.fields().get(0) + " seconds");
                                    }
                                    Dialogs.showWarningNotification(TIMESTAMP_CATEGORY,
                                            "No microphone signal detected. Check the selected input device.");
                                }
                            }
                            case AUDIO_RECOVERED -> {
                                transcriptSilenceWarningActive = false;
                                if (transcriptStatusLabel != null) {
                                    if (transcriptClippingWarningActive) {
                                        transcriptStatusLabel.setText(
                                                "Transcript: Warning: microphone clipping was detected; lower the input gain");
                                    } else {
                                        transcriptStatusLabel.setText(transcriptLiveModelReady
                                                ? "Transcript: listening; live text is a fast preview"
                                                : "Transcript: recording audio; warming speech model");
                                    }
                                }
                            }
                            case FINALIZE_PROGRESS -> updateTranscriptFinalizationProgress(
                                    Double.parseDouble(message.fields().get(0)),
                                    Double.parseDouble(message.fields().get(1)));
                            case FINALIZATION_RESULT -> {
                                updateTranscriptPartial("");
                                transcriptFinalizationResult = message.fields().get(0).trim();
                            }
                            case MALFORMED, DEVICE, AUDIO_CHECK_READY, AUDIO_CHECK_RESULT -> { }
                            case LOG -> {
                                if (transcriptStatusLabel == null) {
                                    return;
                                }
                                if (outputLine.startsWith("Error:")) {
                                    transcriptStatusLabel.setText("Transcript: " + outputLine);
                                } else if (outputLine.startsWith("Warning:")) {
                                    transcriptStatusLabel.setText("Transcript: " + outputLine);
                                } else if (outputLine.startsWith("Loading final faster-whisper model:")) {
                                    transcriptStatusLabel.setText("Transcript: finalizing transcript");
                                    showTranscriptFinalizationProgress();
                                } else if (outputLine.startsWith("Final transcript regenerated from full audio:")) {
                                    transcriptStatusLabel.setText("Transcript: final transcript ready");
                                    refreshTranscriptContents(true);
                                }
                            }
                        }
                    });
                }
            } catch (IOException e) {
                logger.debug("Transcript process output reader stopped", e);
            } finally {
                Platform.runLater(() -> {
                    if (transcriptProcess != process) {
                        return;
                    }
                    boolean unexpectedStop = transcriptProcessPurpose == TranscriptProcessPurpose.CAPTURE &&
                            transcriptStopIntent == TranscriptStopIntent.NONE &&
                            !transcriptStopInProgress &&
                            (recordEvents.get() || transcriptStartPending);
                    if (unexpectedStop) {
                        recordEvents.set(false);
                        transcriptStartPending = false;
                        transcriptResumePending = false;
                        clearLogsWhenRecordingStarts = false;
                        transcriptProcess = null;
                        transcriptProcessPurpose = TranscriptProcessPurpose.NONE;
                        transcriptStopIntent = TranscriptStopIntent.NONE;
                        logSessionBoundary("Transcription Failed");
                        transcriptFinalizationResult = TRANSCRIPT_FINALIZATION_FAILED;
                        transcriptLastExitCode = safeExitValue(process);
                        recordingWorkflowState = RecordingWorkflowState.ERROR;
                    }
                    updateTranscriptPartial("");
                    resetTranscriptAudioLevelIndicator();
                    if (transcriptStatusLabel != null) {
                        if (unexpectedStop) {
                            transcriptStatusLabel.setText(
                                    "Transcript: Error: microphone process stopped; event recording was paused");
                        }
                    }
                    updateLiveEventMonitorControls();
                    refreshTranscriptContents(true);
                });
            }
        }, "timestamp-transcript-output");
        thread.setDaemon(true);
        transcriptOutputThread = thread;
        thread.start();
    }

    private static void stopTranscriptProcess(TranscriptStopIntent intent) {
        Process process = transcriptProcess;
        if (process == null) {
            transcriptStartPending = false;
            transcriptResumePending = false;
            transcriptStopInProgress = false;
            resetTranscriptAudioLevelIndicator();
            transcriptLastExitCode = -1;
            transcriptProcessPurpose = TranscriptProcessPurpose.NONE;
            transcriptStopIntent = TranscriptStopIntent.NONE;
            if (intent == TranscriptStopIntent.PAUSE && transcriptCaptureStarted) {
                recordingWorkflowState = RecordingWorkflowState.PAUSED;
                if (transcriptStatusLabel != null) {
                    transcriptStatusLabel.setText("Transcript: paused");
                }
            } else if (intent == TranscriptStopIntent.DONE && transcriptCaptureStarted) {
                startTranscriptFinalizationProcess();
                return;
            } else {
                recordingWorkflowState = RecordingWorkflowState.ERROR;
                if (transcriptStatusLabel != null) {
                    transcriptStatusLabel.setText(
                            "Recorder unavailable; available session data remains protected");
                }
            }
            updateLiveEventMonitorControls();
            return;
        }

        if (!process.isAlive()) {
            int exitCode = safeExitValue(process);
            transcriptProcess = null;
            transcriptStartPending = false;
            transcriptResumePending = false;
            transcriptStopInProgress = false;
            resetTranscriptAudioLevelIndicator();
            transcriptLastExitCode = exitCode;
            transcriptProcessPurpose = TranscriptProcessPurpose.NONE;
            transcriptStopIntent = TranscriptStopIntent.NONE;
            if (intent == TranscriptStopIntent.PAUSE) {
                recordingWorkflowState = RecordingWorkflowState.PAUSED;
                if (transcriptStatusLabel != null) {
                    transcriptStatusLabel.setText("Transcript: paused");
                }
            } else {
                startTranscriptFinalizationProcess();
                return;
            }
            updateLiveEventMonitorControls();
            return;
        }

        transcriptStopInProgress = true;
        transcriptStopIntent = intent;
        recordingWorkflowState = intent == TranscriptStopIntent.PAUSE
                ? RecordingWorkflowState.PAUSED
                : RecordingWorkflowState.FINALIZING;
        updateLiveEventMonitorControls();
        if (transcriptStatusLabel != null) {
            transcriptStatusLabel.setText(intent == TranscriptStopIntent.PAUSE
                    ? "Transcript: pausing capture"
                    : "Transcript: stopping capture before finalization");
        }
        process.destroy();

        Thread waitThread = new Thread(() -> {
            final boolean[] timedOut = {false};
            final boolean[] interrupted = {false};
            final int[] exitCode = {-1};
            try {
                long timeoutSeconds = 120L;
                logger.info("Waiting up to {} seconds for transcript capture to stop", timeoutSeconds);
                if (!process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
                    timedOut[0] = true;
                    process.destroyForcibly();
                    process.waitFor();
                }
                exitCode[0] = safeExitValue(process);
                waitForTranscriptOutputDrain();
            } catch (InterruptedException e) {
                interrupted[0] = true;
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            } finally {
                Platform.runLater(() -> {
                    if (transcriptProcess == process) {
                        transcriptProcess = null;
                    }
                    transcriptStopInProgress = false;
                    transcriptLastExitCode = exitCode[0];
                    transcriptProcessPurpose = TranscriptProcessPurpose.NONE;
                    transcriptStopIntent = TranscriptStopIntent.NONE;
                    resetTranscriptAudioLevelIndicator();
                    if (timedOut[0] || interrupted[0] || exitCode[0] != 0) {
                        recordingWorkflowState = RecordingWorkflowState.ERROR;
                        transcriptFinalizationResult = timedOut[0]
                                ? "live-preserved-pause-timeout"
                                : (interrupted[0]
                                ? "live-preserved-pause-interrupted"
                                : "live-preserved-pause-failed");
                        if (transcriptStatusLabel != null) {
                            transcriptStatusLabel.setText(
                                    "Warning: capture did not stop cleanly; available audio and transcript are preserved");
                        }
                    } else if (intent == TranscriptStopIntent.PAUSE) {
                        transcriptFinalizationResult = "paused";
                        recordingWorkflowState = RecordingWorkflowState.PAUSED;
                        if (transcriptStatusLabel != null) {
                            transcriptStatusLabel.setText("Transcript: paused; Resume or choose Done");
                        }
                    } else {
                        startTranscriptFinalizationProcess();
                        return;
                    }
                    updateLiveEventMonitorControls();
                    refreshTranscriptContents(true);
                });
            }
        }, "timestamp-transcript-pause");
        waitThread.setDaemon(true);
        waitThread.start();
    }

    private static void waitForTranscriptFinalization(Process process) {
        Thread waitThread = new Thread(() -> {
            final boolean[] timedOut = {false};
            final boolean[] interrupted = {false};
            final int[] exitCode = {-1};
            try {
                long timeoutSeconds = computeFinalizeTimeoutSeconds(recordingAudioDurationSeconds());
                logger.info("Waiting up to {} seconds for transcript finalization", timeoutSeconds);
                if (!process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
                    timedOut[0] = true;
                    process.destroyForcibly();
                    process.waitFor();
                }
                exitCode[0] = safeExitValue(process);
                waitForTranscriptOutputDrain();
            } catch (InterruptedException e) {
                interrupted[0] = true;
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            } finally {
                String finalizationResult = timedOut[0]
                        ? "live-preserved-timeout"
                        : (interrupted[0] ? "live-preserved-interrupted" : transcriptFinalizationResult);
                Platform.runLater(() -> {
                    if (transcriptProcess == process) {
                        transcriptProcess = null;
                    }
                    transcriptStopInProgress = false;
                    transcriptProcessPurpose = TranscriptProcessPurpose.NONE;
                    transcriptStopIntent = TranscriptStopIntent.NONE;
                    transcriptLastExitCode = exitCode[0];
                    transcriptFinalizationResult = finalizationResult;
                    boolean failed = timedOut[0] || interrupted[0] || exitCode[0] != 0 ||
                            TRANSCRIPT_FINALIZATION_FAILED.equals(finalizationResult);
                    recordingWorkflowState = failed
                            ? RecordingWorkflowState.ERROR
                            : RecordingWorkflowState.UNSAVED_REVIEW;
                    resetTranscriptAudioLevelIndicator();
                    if (transcriptStatusLabel != null) {
                        if (timedOut[0]) {
                            transcriptStatusLabel.setText(
                                    "Warning: final pass timed out — choose Save Session to preserve the live transcript");
                        } else if (interrupted[0]) {
                            transcriptStatusLabel.setText(
                                    "Warning: final pass interrupted — choose Save Session to preserve the live transcript");
                        } else {
                            transcriptStatusLabel.setText(buildReadyToSaveStatusText(finalizationResult));
                        }
                    }
                    updateLiveEventMonitorControls();
                    refreshTranscriptContents(true);
                });
            }
        }, "timestamp-transcript-finalize");
        waitThread.setDaemon(true);
        waitThread.start();
    }

    private static int safeExitValue(Process process) {
        if (process == null || process.isAlive()) {
            return -1;
        }
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException e) {
            return -1;
        }
    }

    private static void waitForTranscriptOutputDrain() throws InterruptedException {
        Thread outputThread = transcriptOutputThread;
        if (outputThread != null && outputThread != Thread.currentThread()) {
            outputThread.join(5_000L);
        }
    }

    private static String buildSavedStatusText(String finalizationResult) {
        if ("final".equals(finalizationResult)) {
            return String.format("Transcript: final transcript and %d timestamp events saved", eventLog.size());
        }
        if (finalizationResult != null && finalizationResult.startsWith("live-fallback")) {
            return String.format("Transcript: Warning: live transcript fallback and %d timestamp events saved",
                    eventLog.size());
        }
        if (finalizationResult != null && finalizationResult.startsWith("live-preserved")) {
            return String.format(
                    "Transcript: Warning: live transcript preserved and %d timestamp events saved (final pass incomplete)",
                    eventLog.size());
        }
        if ("no-audio".equals(finalizationResult)) {
            return String.format("Transcript: Warning: no audio captured; %d timestamp events saved", eventLog.size());
        }
        return String.format("Transcript: Warning: timestamps saved, transcript finalization incomplete (%d events)",
                eventLog.size());
    }

    private static String buildReadyToSaveStatusText(String finalizationResult) {
        if ("final".equals(finalizationResult)) {
            return "Final transcript ready — review it, then choose Save Session";
        }
        if (finalizationResult != null && finalizationResult.startsWith("live-fallback")) {
            return "Warning: live transcript fallback ready — review it, then choose Save Session";
        }
        if (finalizationResult != null && finalizationResult.startsWith("live-preserved")) {
            return "Transcript: Warning: live transcript preserved after final-pass interruption — " +
                    "choose Save Session";
        }
        if ("no-audio".equals(finalizationResult)) {
            return "Warning: no audio captured — choose Save Session to preserve timestamps";
        }
        return "Warning: finalization incomplete — choose Save Session to preserve available data";
    }

    private static SessionArtifactPaths buildSessionArtifactPaths(File sessionDir) {
        if (sessionDir == null) {
            return null;
        }
        String sessionId = sessionDir.getName();
        Path root = sessionDir.toPath();
        return new SessionArtifactPaths(
                root.resolve("events").resolve(sessionId + "_event.csv"),
                root.resolve("events").resolve(sessionId + "_event.json"),
                root.resolve("cursor").resolve(sessionId + "_cursor.json"),
                root.resolve(sessionId + "_recording_manifest.json"));
    }

    private static boolean saveSessionArtifacts(File destinationDirectory, File destinationTranscript,
                                                String finalizationResult, int transcriptExitCode) {
        SessionArtifactPaths paths = buildSessionArtifactPaths(destinationDirectory);
        if (paths == null) {
            logger.warn("Cannot save recording artifacts because no destination folder is selected");
            return false;
        }

        try {
            List<EventRecord> eventSnapshot = new ArrayList<>(eventLog);
            List<EventRecord> mouseSnapshot = new ArrayList<>(mouseMoveLog);
            String savedSessionId = destinationDirectory.getName();
            String eventCsv = serializeEventCsv(eventSnapshot, savedSessionId);
            String eventJson = serializeEventJson(eventSnapshot, "events", savedSessionId);
            String cursorJson = serializeEventJson(mouseSnapshot, "cursorEvents", savedSessionId);
            // Invalidate any older commit marker before replacing this session snapshot.
            atomicWriteString(paths.manifest(), buildRecordingManifest(
                    paths, destinationDirectory, destinationTranscript,
                    TRANSCRIPT_FINALIZATION_PENDING, -1,
                    eventSnapshot.size(), mouseSnapshot.size()));
            atomicWriteString(paths.eventCsv(), eventCsv);
            atomicWriteString(paths.eventJson(), eventJson);
            atomicWriteString(paths.cursorJson(), cursorJson);
            validateExactTextFile(paths.eventCsv(), eventCsv, "event CSV");
            validateExactTextFile(paths.eventJson(), eventJson, "event JSON");
            validateExactTextFile(paths.cursorJson(), cursorJson, "cursor JSON");
            // The manifest is written last and acts as the commit marker for the complete snapshot.
            String completedManifest = buildRecordingManifest(
                    paths, destinationDirectory, destinationTranscript,
                    finalizationResult, transcriptExitCode,
                    eventSnapshot.size(), mouseSnapshot.size());
            atomicWriteString(paths.manifest(), completedManifest);
            validateExactTextFile(paths.manifest(), completedManifest, "recording manifest");
            logger.info("Saved recording session artifacts: transcript={}, events={}, cursor={}, manifest={}",
                    destinationTranscript, paths.eventJson(), paths.cursorJson(), paths.manifest());
            return true;
        } catch (IOException | RuntimeException e) {
            logger.error("Failed to save recording session artifacts", e);
            return false;
        }
    }

    static void atomicWriteString(Path target, String contents) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, contents == null ? "" : contents, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void validateExactTextFile(Path target, String expectedContents,
                                      String artifactName) throws IOException {
        if (!Files.isRegularFile(target)) {
            throw new IOException("Missing " + artifactName + ": " + target);
        }
        String actualContents = Files.readString(target, StandardCharsets.UTF_8);
        if (!Objects.equals(expectedContents == null ? "" : expectedContents, actualContents)) {
            throw new IOException("Saved " + artifactName +
                    " did not pass its integrity check: " + target);
        }
    }

    private static String serializeEventCsv(List<EventRecord> records, String savedSessionId) {
        StringBuilder output = new StringBuilder();
        output.append("Session_ID,Sequence,Timestamp,Recorded_At_UTC,Elapsed_ms,Event_Type,Details,")
                .append("View_X,View_Y,View_Width,View_Height,")
                .append("View_CenterX,View_CenterY,View_Z,View_T,Downsample,Rotation,")
                .append("ROI_Type,ROI_BoundsX,ROI_BoundsY,ROI_BoundsWidth,ROI_BoundsHeight,ROI_NumPoints,ROI_Points\n");
        for (EventRecord entry : records) {
            List<String> columns = eventCsvColumns(entry, savedSessionId);
            output.append(String.join(",", columns)).append('\n');
        }
        return output.toString();
    }

    private static List<String> eventCsvColumns(EventRecord entry, String savedSessionId) {
        List<String> columns = new ArrayList<>(List.of(
                csvEscape(savedSessionId), Long.toString(entry.sequence),
                csvEscape(formatter.format(entry.timestamp)), csvEscape(entry.recordedAtUtc.toString()),
                derivedElapsedMillis(entry) == null ? "" : Long.toString(derivedElapsedMillis(entry)),
                csvEscape(entry.eventType), csvEscape(entry.details),
                formatDecimal("%.1f", entry.view.x), formatDecimal("%.1f", entry.view.y),
                formatDecimal("%.1f", entry.view.width), formatDecimal("%.1f", entry.view.height),
                formatDecimal("%.1f", entry.view.centerX), formatDecimal("%.1f", entry.view.centerY),
                Integer.toString(entry.view.z), Integer.toString(entry.view.t),
                formatDecimal("%.4f", entry.view.downsample), formatDecimal("%.4f", entry.view.rotation)));
        if (entry.annotation == null) {
            columns.addAll(List.of("", "", "", "", "", "", ""));
            return columns;
        }
        StringBuilder points = new StringBuilder();
        for (int index = 0; index < entry.annotation.points.size(); index++) {
            if (index > 0) points.append(';');
            double[] point = entry.annotation.points.get(index);
            points.append(String.format(Locale.ROOT, "(%.2f %.2f)", point[0], point[1]));
        }
        columns.add(csvEscape(entry.annotation.roiType));
        columns.add(formatDecimal("%.1f", entry.annotation.boundsX));
        columns.add(formatDecimal("%.1f", entry.annotation.boundsY));
        columns.add(formatDecimal("%.1f", entry.annotation.boundsWidth));
        columns.add(formatDecimal("%.1f", entry.annotation.boundsHeight));
        columns.add(Integer.toString(entry.annotation.numPoints));
        columns.add(csvEscape(points.toString()));
        return columns;
    }

    private static String serializeEventJson(List<EventRecord> records, String collectionName,
                                             String savedSessionId) {
        StringBuilder output = new StringBuilder();
        output.append("{\n  \"schemaVersion\": 2,\n  \"savedAt\": \"")
                .append(escapeJson(formatter.format(LocalDateTime.now())))
                .append("\",\n  \"totalEvents\": ").append(records.size())
                .append(",\n  \"").append(collectionName).append("\": [\n");
        for (int index = 0; index < records.size(); index++) {
            appendEventJson(output, records.get(index), savedSessionId);
            output.append(index + 1 < records.size() ? ",\n" : "\n");
        }
        output.append("  ]\n}\n");
        return output.toString();
    }

    private static void appendEventJson(StringBuilder output, EventRecord entry, String savedSessionId) {
        output.append("    {\n")
                .append("      \"sessionId\": \"").append(escapeJson(savedSessionId)).append("\",\n")
                .append("      \"sequence\": ").append(entry.sequence).append(",\n")
                .append("      \"timestamp\": \"").append(escapeJson(formatter.format(entry.timestamp))).append("\",\n")
                .append("      \"recordedAtUtc\": \"").append(escapeJson(entry.recordedAtUtc.toString())).append("\",\n")
                .append("      \"elapsedMs\": ")
                .append(derivedElapsedMillis(entry) == null ? "null" : derivedElapsedMillis(entry))
                .append(",\n")
                .append("      \"eventType\": \"").append(escapeJson(entry.eventType)).append("\",\n")
                .append("      \"details\": \"").append(escapeJson(entry.details)).append("\",\n")
                .append("      \"zoom_view\": {\n")
                .append("        \"x\": ").append(formatDecimal("%.1f", entry.view.x)).append(",\n")
                .append("        \"y\": ").append(formatDecimal("%.1f", entry.view.y)).append(",\n")
                .append("        \"width\": ").append(formatDecimal("%.1f", entry.view.width)).append(",\n")
                .append("        \"height\": ").append(formatDecimal("%.1f", entry.view.height)).append(",\n")
                .append("        \"centerX\": ").append(formatDecimal("%.1f", entry.view.centerX)).append(",\n")
                .append("        \"centerY\": ").append(formatDecimal("%.1f", entry.view.centerY)).append(",\n")
                .append("        \"z\": ").append(entry.view.z).append(",\n")
                .append("        \"t\": ").append(entry.view.t).append(",\n")
                .append("        \"downsample\": ").append(formatDecimal("%.4f", entry.view.downsample)).append(",\n")
                .append("        \"rotation\": ").append(formatDecimal("%.4f", entry.view.rotation)).append("\n")
                .append("      }");
        if (entry.annotation != null) {
            output.append(",\n      \"annotation\": {\n")
                    .append("        \"roi_type\": \"").append(escapeJson(entry.annotation.roiType)).append("\",\n")
                    .append("        \"bounds\": {\n")
                    .append("          \"x\": ").append(formatDecimal("%.1f", entry.annotation.boundsX)).append(",\n")
                    .append("          \"y\": ").append(formatDecimal("%.1f", entry.annotation.boundsY)).append(",\n")
                    .append("          \"width\": ").append(formatDecimal("%.1f", entry.annotation.boundsWidth)).append(",\n")
                    .append("          \"height\": ").append(formatDecimal("%.1f", entry.annotation.boundsHeight)).append("\n")
                    .append("        },\n")
                    .append("        \"num_points\": ").append(entry.annotation.numPoints).append(",\n")
                    .append("        \"points\": [");
            for (int index = 0; index < entry.annotation.points.size(); index++) {
                if (index > 0) output.append(", ");
                double[] point = entry.annotation.points.get(index);
                output.append('[').append(formatDecimal("%.2f", point[0])).append(", ")
                        .append(formatDecimal("%.2f", point[1])).append(']');
            }
            output.append("]\n      }");
        }
        output.append("\n    }");
    }

    private static String buildRecordingManifest(SessionArtifactPaths paths, File destinationDirectory,
                                                 File destinationTranscript, String finalizationResult,
                                                 int transcriptExitCode, int eventCount, int cursorCount) {
        String normalizedResult = defaultIfBlank(finalizationResult, TRANSCRIPT_FINALIZATION_FAILED);
        boolean transcriptExists = destinationTranscript != null && destinationTranscript.isFile();
        File liveTranscript = transcriptCompanionFile(destinationTranscript, "_live.txt");
        File timedTranscript = transcriptCompanionFile(destinationTranscript, "_timed.txt");
        File segmentTimings = transcriptCompanionFile(destinationTranscript, "_segments.csv");
        File wordTimings = transcriptCompanionFile(destinationTranscript, "_words.csv");
        File rawAudio = transcriptCompanionFile(destinationTranscript, "_audio.raw");
        File waveAudio = transcriptCompanionFile(destinationTranscript, "_audio.wav");
        File audioStart = transcriptCompanionFile(destinationTranscript, "_audio.start.txt");
        boolean preservedFallback = normalizedResult.startsWith("live-preserved");
        boolean completed = transcriptExists && (preservedFallback || (transcriptExitCode == 0 &&
                ("final".equals(normalizedResult) || normalizedResult.startsWith("live-fallback") ||
                        "no-audio".equals(normalizedResult))));
        String workflowState = completed
                ? ("final".equals(normalizedResult) ? "complete" : "complete_with_warning")
                : "incomplete";
        Long durationMillis = eventLog.stream()
                .map(TimeStamp::derivedElapsedMillis)
                .filter(Objects::nonNull)
                .max(Long::compareTo)
                .orElse(null);
        return "{\n" +
                "  \"schemaVersion\": 2,\n" +
                "  \"sessionId\": \"" + escapeJson(destinationDirectory.getName()) + "\",\n" +
                "  \"savedAt\": \"" + escapeJson(formatter.format(LocalDateTime.now())) + "\",\n" +
                "  \"recordingStartedAtUtc\": \"" +
                escapeJson(recordingStartedInstant == null ? "" : recordingStartedInstant.toString()) + "\",\n" +
                "  \"durationMs\": " + (durationMillis == null ? "null" : durationMillis) + ",\n" +
                "  \"qupathVersion\": \"" + escapeJson(EXTENSION_QUPATH_VERSION.toString()) + "\",\n" +
                "  \"workflowState\": \"" + workflowState + "\",\n" +
                "  \"transcriptFinalizationResult\": \"" + escapeJson(normalizedResult) + "\",\n" +
                "  \"transcriptProcessExitCode\": " + transcriptExitCode + ",\n" +
                "  \"transcript\": {\"path\": \"" + escapeJson(relativeSessionPath(destinationDirectory, destinationTranscript)) +
                "\", \"exists\": " + transcriptExists + ", \"bytes\": " +
                (transcriptExists ? destinationTranscript.length() : 0L) + "},\n" +
                "  \"timedMachineTranscript\": " + fileManifestJson(destinationDirectory, timedTranscript) + ",\n" +
                "  \"liveTranscript\": " + fileManifestJson(destinationDirectory, liveTranscript) + ",\n" +
                "  \"transcriptSegments\": " + fileManifestJson(destinationDirectory, segmentTimings) + ",\n" +
                "  \"transcriptWords\": " + fileManifestJson(destinationDirectory, wordTimings) + ",\n" +
                "  \"audio\": {\n" +
                "    \"raw\": " + fileManifestJson(destinationDirectory, rawAudio) + ",\n" +
                "    \"wav\": " + fileManifestJson(destinationDirectory, waveAudio) + ",\n" +
                "    \"startTime\": " + fileManifestJson(destinationDirectory, audioStart) + "\n" +
                "  },\n" +
                "  \"timestamps\": {\"path\": \"" + escapeJson(relativeSessionPath(destinationDirectory, paths.eventJson().toFile())) +
                "\", \"csvPath\": \"" + escapeJson(relativeSessionPath(destinationDirectory, paths.eventCsv().toFile())) +
                "\", \"count\": " + eventCount + "},\n" +
                "  \"cursor\": {\"path\": \"" + escapeJson(relativeSessionPath(destinationDirectory, paths.cursorJson().toFile())) +
                "\", \"count\": " + cursorCount + "}\n" +
                "}\n";
    }

    private static File transcriptCompanionFile(File transcript, String suffix) {
        if (transcript == null || transcript.getParentFile() == null) {
            return null;
        }
        String name = transcript.getName();
        String stem = name.endsWith(".txt") ? name.substring(0, name.length() - 4) : name;
        return new File(transcript.getParentFile(), stem + suffix);
    }

    private static String fileManifestJson(File destinationDirectory, File file) {
        boolean exists = file != null && file.isFile();
        return "{\"path\": \"" + escapeJson(relativeSessionPath(destinationDirectory, file)) +
                "\", \"exists\": " + exists + ", \"bytes\": " +
                (exists ? file.length() : 0L) + "}";
    }

    private static String relativeSessionPath(File destinationDirectory, File file) {
        if (file == null || destinationDirectory == null) {
            return "";
        }
        try {
            return destinationDirectory.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
        } catch (IllegalArgumentException e) {
            return file.getName();
        }
    }

    private record SessionArtifactPaths(Path eventCsv, Path eventJson, Path cursorJson, Path manifest) {
    }

    private record SaveOptions(File sessionDirectory, boolean includeRawAudio) {
    }

    private record RecoverySnapshot(List<EventRecord> events,
                                    List<EventRecord> mouseEvents,
                                    Instant recordingStartedInstant,
                                    long nextSequence) implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    private static void clearLogsStatic() {
        if (recordEvents.get() || transcriptStartPending || transcriptStopInProgress) {
            Dialogs.showWarningNotification(TIMESTAMP_CATEGORY,
                    "Pause recording and wait for transcript finalization before clearing event logs.");
            return;
        }
        if (eventLog.isEmpty() && mouseMoveLog.isEmpty()) {
            Dialogs.showInfoNotification(TIMESTAMP_CATEGORY, "Event and Mouse logs are already empty");
            return;
        }
        if (!Dialogs.showYesNoDialog("Clear Event Logs",
                "Permanently clear the current in-memory event and cursor logs?")) {
            return;
        }
        eventLog.clear();
        mouseMoveLog.clear();
        refreshLiveEventMonitor();
        logger.info("Event logs cleared");
        Dialogs.showInfoNotification(TIMESTAMP_CATEGORY, "Event and Mouse logs cleared");
    }

    private void clearLogs() {
        clearLogsStatic();
    }
    
    /**
     * Export the event log to a CSV file
     */
    private void exportEventLog() {
        if (eventLog.isEmpty()) {
            Dialogs.showWarningNotification(TIMESTAMP_CATEGORY, "Event log is empty. No data to export.");
            return;
        }
        
        // Let user choose save location
        File file = FileChoosers.promptToSaveFile("Export Event Log", null,
                FileChoosers.createExtensionFilter("CSV files", ".csv"));
        
        if (file == null) {
            return; // User cancelled
        }
        
        try (PrintWriter writer = new PrintWriter(new FileWriter(file, StandardCharsets.UTF_8))) {
            // Write CSV header
            writer.println("Session_ID,Timestamp,Event_Type,Details,View_X,View_Y,View_Width,View_Height,View_CenterX,View_CenterY,View_Z,View_T,Downsample,Rotation,ROI_Type,ROI_BoundsX,ROI_BoundsY,ROI_BoundsWidth,ROI_BoundsHeight,ROI_NumPoints,ROI_Points");
            
            // Write all events
            for (EventRecord entry : eventLog) {
                List<String> columns = new ArrayList<>(List.of(
                        csvEscape(entry.sessionId),
                        csvEscape(formatter.format(entry.timestamp)),
                        csvEscape(entry.eventType),
                        csvEscape(entry.details),
                        formatDecimal("%.1f", entry.view.x),
                        formatDecimal("%.1f", entry.view.y),
                        formatDecimal("%.1f", entry.view.width),
                        formatDecimal("%.1f", entry.view.height),
                        formatDecimal("%.1f", entry.view.centerX),
                        formatDecimal("%.1f", entry.view.centerY),
                        Integer.toString(entry.view.z),
                        Integer.toString(entry.view.t),
                        formatDecimal("%.4f", entry.view.downsample),
                        formatDecimal("%.4f", entry.view.rotation)));
                if (entry.annotation != null) {
                    StringBuilder pts = new StringBuilder();
                    for (int j = 0; j < entry.annotation.points.size(); j++) {
                        double[] pt = entry.annotation.points.get(j);
                        if (j > 0) pts.append(';');
                        pts.append(String.format(Locale.ROOT, "(%.2f %.2f)", pt[0], pt[1]));
                    }
                    columns.add(csvEscape(entry.annotation.roiType));
                    columns.add(formatDecimal("%.1f", entry.annotation.boundsX));
                    columns.add(formatDecimal("%.1f", entry.annotation.boundsY));
                    columns.add(formatDecimal("%.1f", entry.annotation.boundsWidth));
                    columns.add(formatDecimal("%.1f", entry.annotation.boundsHeight));
                    columns.add(Integer.toString(entry.annotation.numPoints));
                    columns.add(csvEscape(pts.toString()));
                } else {
                    columns.addAll(List.of("", "", "", "", "", "", ""));
                }
                writer.println(String.join(",", columns));
            }
            
            logger.info("Event log exported to: {}", file.getAbsolutePath());
            Dialogs.showInfoNotification(TIMESTAMP_CATEGORY, 
                String.format("Event log exported successfully!%n%d events saved to:%n%s", 
                    eventLog.size(), file.getName()));
            
        } catch (IOException e) {
            logger.error("Failed to export event log", e);
            Dialogs.showErrorMessage("Export Failed", 
                "Failed to export event log: " + e.getMessage());
        }
    }

    /**
     * Export the event log to a JSON file
     */
    private void exportEventLogToJson() {
        if (eventLog.isEmpty()) {
            Dialogs.showWarningNotification(TIMESTAMP_CATEGORY, "Event log is empty. No data to export.");
            return;
        }

        File file = FileChoosers.promptToSaveFile("Export Event Log as JSON", null,
                FileChoosers.createExtensionFilter("JSON files", ".json"));
        if (file == null) {
            return; // User cancelled
        }

        try (PrintWriter writer = new PrintWriter(new FileWriter(file, StandardCharsets.UTF_8))) {
            writer.println("{");
            writer.println("  \"exportTimestamp\": \"" + escapeJson(LocalDateTime.now().format(formatter)) + "\",");
            writer.println("  \"totalEvents\": " + eventLog.size() + ",");
            writer.println("  \"events\": [");

            for (int i = 0; i < eventLog.size(); i++) {
                EventRecord entry = eventLog.get(i);
                writer.println("    {");
                writer.println("      \"sessionId\": \"" + escapeJson(entry.sessionId) + "\",");
                writer.println("      \"timestamp\": \"" + escapeJson(formatter.format(entry.timestamp)) + "\",");
                writer.println("      \"eventType\": \"" + escapeJson(entry.eventType) + "\",");
                writer.println("      \"details\": \"" + escapeJson(entry.details) + "\",");
                writer.println("      \"zoom_view\": {");
                writer.println("        \"x\": " + formatDecimal("%.1f", entry.view.x) + ",");
                writer.println("        \"y\": " + formatDecimal("%.1f", entry.view.y) + ",");
                writer.println("        \"width\": " + formatDecimal("%.1f", entry.view.width) + ",");
                writer.println("        \"height\": " + formatDecimal("%.1f", entry.view.height) + ",");
                writer.println("        \"centerX\": " + formatDecimal("%.1f", entry.view.centerX) + ",");
                writer.println("        \"centerY\": " + formatDecimal("%.1f", entry.view.centerY) + ",");
                writer.println("        \"z\": " + entry.view.z + ",");
                writer.println("        \"t\": " + entry.view.t + ",");
                writer.println("        \"downsample\": " + formatDecimal("%.4f", entry.view.downsample) + ",");
                writer.println("        \"rotation\": " + formatDecimal("%.4f", entry.view.rotation));
                writer.print("      }");

                // Include annotation geometry if present
                if (entry.annotation != null) {
                    writer.println(",");
                    writer.println("      \"annotation\": {");
                    writer.println("        \"roi_type\": \"" + escapeJson(entry.annotation.roiType) + "\",");
                    writer.println("        \"bounds\": {");
                    writer.println("          \"x\": " + formatDecimal("%.1f", entry.annotation.boundsX) + ",");
                    writer.println("          \"y\": " + formatDecimal("%.1f", entry.annotation.boundsY) + ",");
                    writer.println("          \"width\": " + formatDecimal("%.1f", entry.annotation.boundsWidth) + ",");
                    writer.println("          \"height\": " + formatDecimal("%.1f", entry.annotation.boundsHeight));
                    writer.println("        },");
                    writer.println("        \"num_points\": " + entry.annotation.numPoints + ",");
                    writer.print("        \"points\": [");
                    for (int j = 0; j < entry.annotation.points.size(); j++) {
                        double[] pt = entry.annotation.points.get(j);
                        writer.print("[" + formatDecimal("%.2f", pt[0]) + ", " +
                                formatDecimal("%.2f", pt[1]) + "]");
                        if (j < entry.annotation.points.size() - 1) {
                            writer.print(", ");
                        }
                    }
                    writer.println("]");
                    writer.print("      }");
                }

                writer.println();
                writer.print("    }");
                if (i < eventLog.size() - 1) {
                    writer.println(",");
                } else {
                    writer.println();
                }
            }

            writer.println("  ]");
            writer.println("}");

            logger.info("Event log exported to JSON: {}", file.getAbsolutePath());
            Dialogs.showInfoNotification(TIMESTAMP_CATEGORY,
                String.format("Event log exported to JSON!%n%d events saved to:%n%s",
                    eventLog.size(), file.getName()));

        } catch (IOException e) {
            logger.error("Failed to export event log to JSON", e);
            Dialogs.showErrorMessage("Export Failed",
                "Failed to export event log to JSON: " + e.getMessage());
        }
    }

    /**
     * Export the mouse movement log to a separate JSON file
     */
    private void exportMouseLogToJson() {
        if (mouseMoveLog.isEmpty()) {
            Dialogs.showWarningNotification(TIMESTAMP_CATEGORY, "Mouse movement log is empty. No data to export.");
            return;
        }

        File file = FileChoosers.promptToSaveFile("Export Mouse Movement Log as JSON", null,
                FileChoosers.createExtensionFilter("JSON files", ".json"));
        if (file == null) {
            return; // User cancelled
        }

        try (PrintWriter writer = new PrintWriter(new FileWriter(file, StandardCharsets.UTF_8))) {
            writer.println("{");
            writer.println("  \"exportTimestamp\": \"" + escapeJson(LocalDateTime.now().format(formatter)) + "\",");
            writer.println("  \"totalEvents\": " + mouseMoveLog.size() + ",");
            writer.println("  \"events\": [");

            for (int i = 0; i < mouseMoveLog.size(); i++) {
                EventRecord entry = mouseMoveLog.get(i);
                writer.println("    {");
                writer.println("      \"sessionId\": \"" + escapeJson(entry.sessionId) + "\",");
                writer.println("      \"timestamp\": \"" + escapeJson(formatter.format(entry.timestamp)) + "\",");
                writer.println("      \"eventType\": \"" + escapeJson(entry.eventType) + "\",");
                writer.println("      \"details\": \"" + escapeJson(entry.details) + "\",");
                writer.println("      \"zoom_view\": {");
                writer.println("        \"x\": " + formatDecimal("%.1f", entry.view.x) + ",");
                writer.println("        \"y\": " + formatDecimal("%.1f", entry.view.y) + ",");
                writer.println("        \"width\": " + formatDecimal("%.1f", entry.view.width) + ",");
                writer.println("        \"height\": " + formatDecimal("%.1f", entry.view.height) + ",");
                writer.println("        \"centerX\": " + formatDecimal("%.1f", entry.view.centerX) + ",");
                writer.println("        \"centerY\": " + formatDecimal("%.1f", entry.view.centerY) + ",");
                writer.println("        \"z\": " + entry.view.z + ",");
                writer.println("        \"t\": " + entry.view.t + ",");
                writer.println("        \"downsample\": " + formatDecimal("%.4f", entry.view.downsample) + ",");
                writer.println("        \"rotation\": " + formatDecimal("%.4f", entry.view.rotation));
                writer.print("      }");
                writer.println();
                writer.print("    }");
                if (i < mouseMoveLog.size() - 1) {
                    writer.println(",");
                } else {
                    writer.println();
                }
            }

            writer.println("  ]");
            writer.println("}");

            logger.info("Mouse movement log exported to JSON: {}", file.getAbsolutePath());
            Dialogs.showInfoNotification(TIMESTAMP_CATEGORY,
                String.format("Mouse movement log exported to JSON!%n%d events saved to:%n%s",
                    mouseMoveLog.size(), file.getName()));

        } catch (IOException e) {
            logger.error("Failed to export mouse log to JSON", e);
            Dialogs.showErrorMessage("Export Failed",
                "Failed to export mouse log to JSON: " + e.getMessage());
        }
    }

    /**
     * Escape special characters for JSON string values
     */
    static String formatDecimal(String pattern, double value) {
        return String.format(Locale.ROOT, pattern, value);
    }

    static String csvEscape(String value) {
        String resolved = value == null ? "" : value;
        if (resolved.contains(",") || resolved.contains("\"") ||
                resolved.contains("\n") || resolved.contains("\r")) {
            return "\"" + resolved.replace("\"", "\"\"") + "\"";
        }
        return resolved;
    }

    static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int)character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }
    
    @Override
    public String getDescription() {
        return EXTENSION_DESCRIPTION;
    }
    
    @Override
    public Version getQuPathVersion() {
        return EXTENSION_QUPATH_VERSION;
    }
    
    /**
     * Bounding box of the current WSI view (in image coordinates).
     */
    private static class ViewBounds implements Serializable {
        private static final long serialVersionUID = 1L;
        final double x;
        final double y;
        final double width;
        final double height;
        final double centerX;
        final double centerY;
        final int z;
        final int t;
        final double downsample;
        final double rotation;

        ViewBounds(double x, double y, double width, double height,
                   double centerX, double centerY,
                   int z, int t, double downsample, double rotation) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.centerX = centerX;
            this.centerY = centerY;
            this.z = z;
            this.t = t;
            this.downsample = downsample;
            this.rotation = rotation;
        }
    }

    /**
     * Geometry of an annotation ROI (bounding box + polygon vertices).
     */
    private static class AnnotationGeometry implements Serializable {
        private static final long serialVersionUID = 1L;
        final String roiType;       // "Rectangle", "Polygon", "Ellipse", etc.
        final double boundsX;
        final double boundsY;
        final double boundsWidth;
        final double boundsHeight;
        final int numPoints;
        final List<double[]> points; // vertex list: [[x1,y1], [x2,y2], ...]

        AnnotationGeometry(String roiType, double boundsX, double boundsY,
                           double boundsWidth, double boundsHeight,
                           int numPoints, List<double[]> points) {
            this.roiType = roiType;
            this.boundsX = boundsX;
            this.boundsY = boundsY;
            this.boundsWidth = boundsWidth;
            this.boundsHeight = boundsHeight;
            this.numPoints = numPoints;
            this.points = points;
        }
    }

    /**
     * Record of a timestamped event, including the viewer's bounding box (zoom_view)
     * and optional annotation geometry.
     */
    private static class EventRecord implements Serializable {
        private static final long serialVersionUID = 1L;
        final long sequence;
        final LocalDateTime timestamp;
        final Instant recordedAtUtc;
        final String eventType;
        final String details;
        final ViewBounds view;
        final AnnotationGeometry annotation; // null for non-annotation events
        final String sessionId;
        
        EventRecord(long sequence, LocalDateTime timestamp, Instant recordedAtUtc,
                    String eventType, String details,
                     ViewBounds view, AnnotationGeometry annotation, String sessionId) {
            this.sequence = sequence;
            this.timestamp = timestamp;
            this.recordedAtUtc = recordedAtUtc;
            this.eventType = eventType;
            this.details = details;
            this.view = view;
            this.annotation = annotation;
            this.sessionId = sessionId;
        }
    }

    /**
     * Per-viewer gesture state used to collapse repeated drag/scroll events into
     * a single start/end record.
     */
    private static class ViewerInteractionState {
        boolean zoomInProgress = false;
        LocalDateTime zoomStartTime;
        ViewBounds zoomStartView;
        boolean panCandidate = false;
        boolean panDragged = false;
        LocalDateTime panStartTime;
        ViewBounds panStartView;
        double panStartComponentX;
        double panStartComponentY;
        Point2D panStartImagePoint;
        final PauseTransition zoomEndDelay = new PauseTransition(Duration.millis(GESTURE_END_DELAY_MS));

        void resetPan() {
            panCandidate = false;
            panDragged = false;
            panStartTime = null;
            panStartView = null;
            panStartImagePoint = null;
        }
    }
    
    /**
     * Custom overlay to display timestamp and last event
     */
    private static class TimestampOverlay implements PathOverlay {
        
        @Override
        public void paintOverlay(Graphics2D g2d, ImageRegion imageRegion,
                                 double downsampleFactor, ImageData<BufferedImage> imageData,
                                 boolean paintCompletely) {

            if (!enableTimestamp.get()) {
                return;
            }
            
            LocalDateTime now = LocalDateTime.now();
            String recordingText = recordEvents.get() ? "REC  " : "";
            String currentTime = formatOverlayTime(now);
            String lastEvent = "";

            if (!eventLog.isEmpty()) {
                EventRecord last = eventLog.get(eventLog.size() - 1);
                if (isOverlayEventRecent(last.timestamp, now)) {
                    lastEvent = "  |  " + last.eventType + " " + formatOverlayTime(last.timestamp);
                }
            }
            
            double scale = Math.max(0.0001, downsampleFactor);
            int fontSize = (int)Math.round(Math.max(10.0, timestampFontSize.get()) * scale);
            Font font = new Font("SansSerif", Font.PLAIN, fontSize);
            g2d.setFont(font);
            
            FontMetrics metrics = g2d.getFontMetrics();
            int margin = (int)Math.round(8 * scale);
            int paddingX = (int)Math.round(6 * scale);
            int paddingY = (int)Math.round(3 * scale);
            int x = imageRegion.getX() + margin;
            int top = imageRegion.getY() + margin;
            int baseline = top + paddingY + metrics.getAscent();
            int textWidth = metrics.stringWidth(recordingText)
                    + metrics.stringWidth(currentTime)
                    + metrics.stringWidth(lastEvent);
            int backgroundWidth = textWidth + 2 * paddingX;
            int backgroundHeight = metrics.getHeight() + 2 * paddingY;
            int arc = Math.max(1, (int)Math.round(4 * scale));

            g2d.setColor(new java.awt.Color(0, 0, 0, 155));
            g2d.fillRoundRect(x, top, backgroundWidth, backgroundHeight, arc, arc);

            int textX = x + paddingX;
            if (!recordingText.isEmpty()) {
                g2d.setColor(new java.awt.Color(255, 100, 92));
                g2d.drawString(recordingText, textX, baseline);
                textX += metrics.stringWidth(recordingText);
            }
            g2d.setColor(java.awt.Color.WHITE);
            g2d.drawString(currentTime, textX, baseline);
            textX += metrics.stringWidth(currentTime);

            if (!lastEvent.isEmpty()) {
                g2d.setColor(new java.awt.Color(255, 224, 120));
                g2d.drawString(lastEvent, textX, baseline);
            }
        }
    }

    static String formatOverlayTime(LocalDateTime time) {
        return overlayTimeFormatter.format(time);
    }

    static boolean isOverlayEventRecent(LocalDateTime eventTime, LocalDateTime now) {
        if (eventTime == null || now == null) {
            return false;
        }
        long ageMillis = java.time.Duration.between(eventTime, now).toMillis();
        return ageMillis >= 0 && ageMillis <= OVERLAY_EVENT_VISIBLE_MILLIS;
    }
}
