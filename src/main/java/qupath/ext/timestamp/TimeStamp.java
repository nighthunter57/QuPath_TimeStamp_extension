package qupath.ext.timestamp;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.util.Duration;
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
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * QuPath extension for recording timestamped events (clicks, zooms, pans).
 * Shows the current time and last event on screen.
 */
public class TimeStamp implements QuPathExtension {
    
    private static final Logger logger = LoggerFactory.getLogger(TimeStamp.class);
    
    private static final String EXTENSION_NAME = "TimeStamp Extension";
    private static final String EXTENSION_DESCRIPTION = "Record timestamps for image events in QuPath";
    private static final Version EXTENSION_QUPATH_VERSION = Version.parse("v0.5.0");
    private static final double GESTURE_END_DELAY_MS = 250.0;
    private static final double TRANSCRIPT_REFRESH_INTERVAL_SECONDS = 0.25;
    private static final int MAX_LIVE_MONITOR_EVENTS = 200;
    private static final List<String> AVAILABLE_TRANSCRIPT_MODELS = Arrays.asList(
            "tiny", "tiny.en",
            "base", "base.en",
            "small", "small.en",
            "medium", "medium.en",
            "large-v1", "large-v2", "large-v3",
            "distil-small.en", "distil-medium.en", "distil-large-v2");
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
    
    private boolean isInstalled = false;
    private static QuPathGUI qupathGui;
    private static final String TIMESTAMP_CATEGORY = "TimeStamp";
    
    // Event logs to store timestamped events
    private static final List<EventRecord> eventLog = new ArrayList<>();
    private static final List<EventRecord> mouseMoveLog = new ArrayList<>();
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static Tab liveEventTab;
    private static TextArea liveEventTextArea;
    private static TextArea liveTranscriptTextArea;
    private static Button startRecordingButton;
    private static Button pauseRecordingButton;
    private static Label recordingStatusLabel;
    private static Label transcriptStatusLabel;
    private static Label transcriptSettingsLabel;
    private static File transcriptSessionDir;
    private static File transcriptFile;
    private static long transcriptLastModified = -1L;
    private static String transcriptLastContents = "";
    private static Timeline transcriptRefreshTimeline;
    private static Process transcriptProcess;
    
    // Persistent preferences
    private static final BooleanProperty enableTimestamp = PathPrefs.createPersistentPreference(
            "timestamp.enable", true);

    private static final BooleanProperty recordEvents = PathPrefs.createPersistentPreference(
            "timestamp.recordEvents", false);
            
    private static final BooleanProperty enableMouseTracking = PathPrefs.createPersistentPreference(
            "timestamp.trackMouse", false);
    
    private static final DoubleProperty timestampFontSize = PathPrefs.createPersistentPreference(
            "timestamp.fontSize", 24.0);

    private static final StringProperty transcriptModel = PathPrefs.createPersistentPreference(
            "timestamp.transcriptModel", "small");

    private static final StringProperty transcriptLanguage = PathPrefs.createPersistentPreference(
            "timestamp.transcriptLanguage", "en");

    private static final StringProperty transcriptChunkSeconds = PathPrefs.createPersistentPreference(
            "timestamp.transcriptChunkSeconds", "3.5");

    private static final StringProperty transcriptComputeType = PathPrefs.createPersistentPreference(
            "timestamp.transcriptComputeType", "int8");

    private static final StringProperty transcriptBeamSize = PathPrefs.createPersistentPreference(
            "timestamp.transcriptBeamSize", "5");

    private static final StringProperty transcriptBestOf = PathPrefs.createPersistentPreference(
            "timestamp.transcriptBestOf", "5");

    private static final BooleanProperty transcriptPreviousText = PathPrefs.createPersistentPreference(
            "timestamp.transcriptPreviousText", false);
    
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
        
        addPreferences(qupath);
        addMenuItem(qupath);
        installLiveEventTab(qupath);
        installToolChangeListener(qupath);
        installTimestampOverlay(qupath);
        
        logger.info("{} installed successfully", getName());
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

        var recordEventsProperty = new PropertyItemBuilder<>(recordEvents, Boolean.class)
                .name("Record events")
                .category(TIMESTAMP_CATEGORY)
                .description("Start or pause event capture without hiding the overlay")
                .build();
        
        var fontSizeProperty = new PropertyItemBuilder<>(timestampFontSize, Double.class)
                .name("Font size")
                .category(TIMESTAMP_CATEGORY)
                .description("Font size for timestamp display")
                .build();
        
        qupath.getPreferencePane()
                .getPropertySheet()
                .getItems()
                .addAll(enableProperty, recordEventsProperty, enableMouseProperty, fontSizeProperty);
    }
    
    private void addMenuItem(QuPathGUI qupath) {
        var menu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);
        
        MenuItem toggleItem = new MenuItem("Toggle timestamp display");
        toggleItem.setOnAction(e -> {
            enableTimestamp.set(!enableTimestamp.get());
            qupath.getAllViewers().forEach(QuPathViewer::repaint);
        });
        menu.getItems().add(toggleItem);
        
        MenuItem showLogItem = new MenuItem("Open live event monitor");
        showLogItem.setOnAction(e -> showEventLog());
        menu.getItems().add(showLogItem);

        MenuItem selectTranscriptItem = new MenuItem("Select transcript session folder");
        selectTranscriptItem.setOnAction(e -> selectTranscriptSessionDirectory());
        menu.getItems().add(selectTranscriptItem);

        MenuItem exportTranscriptItem = new MenuItem("Export transcript");
        exportTranscriptItem.setOnAction(e -> exportTranscript());
        menu.getItems().add(exportTranscriptItem);

        MenuItem transcriptSettingsItem = new MenuItem("Transcript settings");
        transcriptSettingsItem.setOnAction(e -> showTranscriptSettingsDialog());
        menu.getItems().add(transcriptSettingsItem);
        
        MenuItem clearLogItem = new MenuItem("Clear event log");
        clearLogItem.setOnAction(e -> clearLogs());
        menu.getItems().add(clearLogItem);
        
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
            if (interactionState.zoomInProgress && recordEvents.get()) {
                logEvent(interactionState.lastZoomEventType + " End",
                        String.format("downsample=%.2f", viewer.getDownsampleFactor()), viewer);
                viewer.repaint();
            }
            interactionState.zoomInProgress = false;
            interactionState.lastZoomEventType = null;
        });
        
        // Track mouse clicks
        view.addEventFilter(MouseEvent.MOUSE_CLICKED, event -> {
            if (recordEvents.get()) {
                logEvent("Click", String.format("x=%.1f, y=%.1f, button=%s", 
                    event.getX(), event.getY(), event.getButton()), viewer);
                viewer.repaint();
            }
        });
        
        // Track zoom events
        view.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (recordEvents.get() && event.getDeltaY() != 0) {
                String direction = event.getDeltaY() > 0 ? "Zoom In" : "Zoom Out";

                if (interactionState.zoomInProgress &&
                        !direction.equals(interactionState.lastZoomEventType)) {
                    logEvent(interactionState.lastZoomEventType + " End",
                            String.format("downsample=%.2f", viewer.getDownsampleFactor()), viewer);
                    interactionState.zoomInProgress = false;
                    interactionState.lastZoomEventType = null;
                }

                if (!interactionState.zoomInProgress) {
                    interactionState.zoomInProgress = true;
                    interactionState.lastZoomEventType = direction;
                    logEvent(direction + " Start",
                            String.format("downsample=%.2f", viewer.getDownsampleFactor()), viewer);
                    viewer.repaint();
                }

                interactionState.zoomEndDelay.playFromStart();
            } else if (interactionState.zoomInProgress) {
                interactionState.zoomEndDelay.playFromStart();
            }
        });

        view.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (!event.isPrimaryButtonDown()) {
                interactionState.panInProgress = false;
            }
        });

        // Track panning as a single start/end gesture instead of every drag event.
        view.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (recordEvents.get() && event.isPrimaryButtonDown() && !interactionState.panInProgress) {
                interactionState.panInProgress = true;
                logEvent("Pan Start", String.format("x=%.1f, y=%.1f", event.getX(), event.getY()), viewer);
                viewer.repaint();
            }
        });
        
        view.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (interactionState.panInProgress) {
                if (recordEvents.get()) {
                    logEvent("Pan End", String.format("x=%.1f, y=%.1f", event.getX(), event.getY()), viewer);
                    viewer.repaint();
                }
                interactionState.panInProgress = false;
            }
        });

        // Track mouse movement (throttled to avoid log spam, e.g., max 10 times per second)
        long[] lastMouseMoveTime = {0};
        view.addEventFilter(MouseEvent.MOUSE_MOVED, event -> {
            if (recordEvents.get() && enableMouseTracking.get()) {
                long now = System.currentTimeMillis();
                if (now - lastMouseMoveTime[0] > 100) { // 100ms throttle
                    lastMouseMoveTime[0] = now;
                    logEvent("MouseMove", String.format("x=%.1f, y=%.1f", event.getX(), event.getY()), viewer);
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
            if (event.getEventType() != HierarchyEventType.ADDED &&
                    event.getEventType() != HierarchyEventType.REMOVED) return;

            for (PathObject pathObject : event.getChangedObjects()) {
                if (pathObject.isAnnotation() && pathObject.getROI() != null) {
                    ROI roi = pathObject.getROI();
                    AnnotationGeometry geom = createAnnotationGeometry(roi);
                    String details = createAnnotationDetails(roi);
                    String eventType = event.getEventType() == HierarchyEventType.ADDED
                            ? "Annotate"
                            : "Annotation Deleted";

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
        return String.format("type=%s, points=%d, bounds=[%.1f, %.1f, %.1f, %.1f]",
                roi.getRoiName(), roi.getNumPoints(),
                roi.getBoundsX(), roi.getBoundsY(),
                roi.getBoundsWidth(), roi.getBoundsHeight());
    }

    /**
     * Log an event with timestamp, viewer bounding box, and optional annotation geometry
     */
    private static void logEvent(String eventType, String details,
                                 QuPathViewer viewer, AnnotationGeometry annotation) {
        LocalDateTime now = LocalDateTime.now();
        
        // Capture the current WSI view bounding box
        double downsample = viewer.getDownsampleFactor();
        double centerX = viewer.getCenterPixelX();
        double centerY = viewer.getCenterPixelY();
        double vw = viewer.getView().getWidth() * downsample;
        double vh = viewer.getView().getHeight() * downsample;
        double vx = centerX - vw / 2.0;
        double vy = centerY - vh / 2.0;
        int vz = viewer.getZPosition();
        int vt = viewer.getTPosition();
        
        EventRecord entry = new EventRecord(now, eventType, details,
                new ViewBounds(vx, vy, vw, vh, vz, vt, downsample), annotation);
                
        if ("MouseMove".equals(eventType)) {
            mouseMoveLog.add(entry);
        } else {
            eventLog.add(entry);
            refreshLiveEventMonitor();
        }
        
        if (logger.isInfoEnabled()) {
            logger.info("Event: {} - {} - {} - view:[x={}, y={}, w={}, h={}, z={}, t={}]",
                formatter.format(now), eventType, details, vx, vy, vw, vh, vz, vt);
        }
    }
    
    private static BorderPane createLiveEventMonitorPane() {
        liveEventTextArea = new TextArea();
        liveEventTextArea.setEditable(false);
        liveEventTextArea.setWrapText(false);
        liveEventTextArea.setPrefColumnCount(60);
        liveEventTextArea.setStyle("-fx-font-family: 'Monospaced';");

        liveTranscriptTextArea = new TextArea();
        liveTranscriptTextArea.setEditable(false);
        liveTranscriptTextArea.setWrapText(true);
        liveTranscriptTextArea.setStyle("-fx-font-family: 'Monospaced';");

        startRecordingButton = new Button("Start Recording");
        startRecordingButton.setOnAction(e -> startRecordingSession());
        configureMonitorButton(startRecordingButton);

        pauseRecordingButton = new Button("Pause Recording");
        pauseRecordingButton.setOnAction(e -> pauseRecordingSession());
        configureMonitorButton(pauseRecordingButton);

        var clearEventsButton = new Button("Clear Events");
        clearEventsButton.setOnAction(e -> clearLogsStatic());
        configureMonitorButton(clearEventsButton);

        var selectTranscriptButton = new Button("Select Session Folder");
        selectTranscriptButton.setOnAction(e -> selectTranscriptSessionDirectory());
        configureMonitorButton(selectTranscriptButton);

        var exportTranscriptButton = new Button("Export Transcript");
        exportTranscriptButton.setOnAction(e -> exportTranscript());
        configureMonitorButton(exportTranscriptButton);

        var transcriptSettingsButton = new Button("Transcript Settings");
        transcriptSettingsButton.setOnAction(e -> showTranscriptSettingsDialog());
        configureMonitorButton(transcriptSettingsButton);

        recordingStatusLabel = new Label();
        transcriptStatusLabel = new Label();
        transcriptSettingsLabel = new Label();
        recordingStatusLabel.setWrapText(true);
        transcriptStatusLabel.setWrapText(true);
        transcriptSettingsLabel.setWrapText(true);

        GridPane controls = new GridPane();
        controls.setHgap(8);
        controls.setVgap(8);
        controls.setPadding(new Insets(0, 0, 8, 0));
        controls.add(startRecordingButton, 0, 0);
        controls.add(pauseRecordingButton, 1, 0);
        controls.add(clearEventsButton, 2, 0);
        controls.add(recordingStatusLabel, 0, 1, 3, 1);
        GridPane.setHgrow(startRecordingButton, Priority.ALWAYS);
        GridPane.setHgrow(pauseRecordingButton, Priority.ALWAYS);
        GridPane.setHgrow(clearEventsButton, Priority.ALWAYS);
        GridPane.setHgrow(recordingStatusLabel, Priority.ALWAYS);

        var eventLabel = new Label("TimeStamp Events");
        var eventPane = new VBox(6, eventLabel, liveEventTextArea);
        VBox.setVgrow(liveEventTextArea, Priority.ALWAYS);

        var transcriptLabel = new Label("Live Transcript");
        GridPane transcriptControls = new GridPane();
        transcriptControls.setHgap(8);
        transcriptControls.setVgap(8);
        transcriptControls.add(selectTranscriptButton, 0, 0);
        transcriptControls.add(exportTranscriptButton, 1, 0);
        transcriptControls.add(transcriptSettingsButton, 0, 1, 2, 1);
        transcriptControls.add(transcriptSettingsLabel, 0, 2, 2, 1);
        transcriptControls.add(transcriptStatusLabel, 0, 3, 2, 1);
        GridPane.setHgrow(selectTranscriptButton, Priority.ALWAYS);
        GridPane.setHgrow(exportTranscriptButton, Priority.ALWAYS);
        GridPane.setHgrow(transcriptSettingsButton, Priority.ALWAYS);
        GridPane.setHgrow(transcriptSettingsLabel, Priority.ALWAYS);
        GridPane.setHgrow(transcriptStatusLabel, Priority.ALWAYS);
        var transcriptPane = new VBox(6, transcriptLabel, transcriptControls, liveTranscriptTextArea);
        VBox.setVgrow(liveTranscriptTextArea, Priority.ALWAYS);

        var splitPane = new SplitPane(eventPane, transcriptPane);
        splitPane.setOrientation(Orientation.VERTICAL);
        splitPane.setDividerPositions(0.55);

        var root = new BorderPane(splitPane);
        root.setTop(controls);
        root.setPadding(new Insets(8));

        updateLiveEventMonitorControls();
        updateTranscriptSettingsSummary();
        refreshLiveEventMonitorContents();
        ensureTranscriptRefreshStarted();
        return root;
    }

    /**
     * Focus the live event monitor tab in QuPath's analysis pane.
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
        if (liveEventTextArea == null) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Event Log (").append(eventLog.size()).append(" events):\n\n");

        int maxEvents = Math.min(MAX_LIVE_MONITOR_EVENTS, eventLog.size());
        int startIndex = Math.max(0, eventLog.size() - maxEvents);

        for (int i = startIndex; i < eventLog.size(); i++) {
            EventRecord entry = eventLog.get(i);
            sb.append(String.format("[%s] %s: %s%n",
                formatter.format(entry.timestamp),
                entry.eventType,
                entry.details));
        }

        liveEventTextArea.setText(sb.toString());
        liveEventTextArea.positionCaret(liveEventTextArea.getLength());
    }

    private static void updateLiveEventMonitorControls() {
        if (startRecordingButton != null) {
            startRecordingButton.setDisable(recordEvents.get());
        }
        if (pauseRecordingButton != null) {
            pauseRecordingButton.setDisable(!recordEvents.get());
        }
        if (recordingStatusLabel != null) {
            recordingStatusLabel.setText(recordEvents.get() ? "Status: Recording" : "Status: Paused");
        }
    }

    private static void startRecordingSession() {
        recordEvents.set(true);
        updateLiveEventMonitorControls();

        if (transcriptProcess != null && transcriptProcess.isAlive()) {
            if (transcriptStatusLabel != null) {
                transcriptStatusLabel.setText("Transcript: live transcription running");
            }
            return;
        }

        startTranscriptProcess(true);
    }

    private static void startTranscriptProcess(boolean resetWorkingFile) {
        if (transcriptSessionDir == null) {
            if (transcriptStatusLabel != null) {
                transcriptStatusLabel.setText("Transcript: select session folder to start live transcription");
            }
            Dialogs.showWarningNotification(TIMESTAMP_CATEGORY,
                    "Event recording started. Select Session Folder to launch live transcription with Start Recording.");
            return;
        }

        File sessionDir = transcriptSessionDir;
        File launcher = findTranscriptLauncherScript();
        if (launcher == null) {
            if (transcriptStatusLabel != null) {
                transcriptStatusLabel.setText("Transcript: launcher unavailable");
            }
            Dialogs.showWarningNotification(TIMESTAMP_CATEGORY,
                    "Event recording started, but live transcription could not be launched. Check transcript file selection and local scripts/start_live_transcript.sh.");
            return;
        }

        try {
            String model = defaultIfBlank(transcriptModel.get(), "small");
            String language = defaultIfBlank(transcriptLanguage.get(), "en");
            String chunkSeconds = defaultIfBlank(transcriptChunkSeconds.get(), "3.5");
            String computeType = defaultIfBlank(transcriptComputeType.get(), "int8");
            String beamSize = defaultIfBlank(transcriptBeamSize.get(), "5");
            String bestOf = defaultIfBlank(transcriptBestOf.get(), "5");
            String previousText = Boolean.toString(transcriptPreviousText.get());
            transcriptFile = buildTranscriptFile(sessionDir);
            if (resetWorkingFile) {
                resetTranscriptWorkingFile(transcriptFile);
            }
            transcriptLastModified = -1L;
            if (resetWorkingFile) {
                transcriptLastContents = "";
            }
            refreshTranscriptContents();
            ProcessBuilder processBuilder = new ProcessBuilder(
                    launcher.getAbsolutePath(),
                    sessionDir.getAbsolutePath(),
                    model,
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
                    previousText);
            processBuilder.redirectErrorStream(true);
            transcriptProcess = processBuilder.start();
            consumeTranscriptProcessOutput(transcriptProcess);

            if (transcriptStatusLabel != null) {
                transcriptStatusLabel.setText(String.format("Transcript: running (%s, %ss)", model, chunkSeconds));
            }
            logger.info("Started live transcript process for session {} with model={} language={} chunk={} compute={} beam={} bestOf={} previousText={}",
                    sessionDir.getAbsolutePath(), model, language, chunkSeconds, computeType, beamSize, bestOf, previousText);
            refreshLiveEventMonitor();
        } catch (IOException e) {
            logger.error("Failed to start live transcript process", e);
            transcriptProcess = null;
            if (transcriptStatusLabel != null) {
                transcriptStatusLabel.setText("Transcript: failed to start");
            }
            Dialogs.showErrorMessage("Transcript Launch Failed",
                    "Could not start live transcription. " + e.getMessage());
        }
    }

    private static void restartTranscriptProcessForUpdatedSettings() {
        if (!recordEvents.get() || transcriptSessionDir == null) {
            return;
        }

        stopTranscriptProcess();
        startTranscriptProcess(false);
    }

    private static void pauseRecordingSession() {
        recordEvents.set(false);
        updateLiveEventMonitorControls();
        stopTranscriptProcess();
    }

    private static void selectTranscriptSessionDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select Transcript Session Folder");

        if (transcriptSessionDir != null && transcriptSessionDir.isDirectory()) {
            chooser.setInitialDirectory(transcriptSessionDir);
        }

        File selected = chooser.showDialog(qupathGui == null ? null : qupathGui.getStage());
        if (selected == null || !selected.isDirectory()) {
            return;
        }

        transcriptSessionDir = selected;
        transcriptFile = buildTranscriptFile(selected);
        transcriptLastModified = -1L;
        transcriptLastContents = "";
        logger.info("Selected transcript session folder: {}", selected.getAbsolutePath());
        refreshLiveEventMonitor();
        Platform.runLater(() -> {
            if (qupathGui != null && liveEventTab != null && qupathGui.getAnalysisTabPane() != null) {
                qupathGui.getAnalysisTabPane().getSelectionModel().select(liveEventTab);
            }
        });
    }

    private static void ensureTranscriptRefreshStarted() {
        if (transcriptRefreshTimeline != null) {
            if (transcriptRefreshTimeline.getStatus() != Animation.Status.RUNNING) {
                transcriptRefreshTimeline.play();
            }
            return;
        }

        transcriptRefreshTimeline = new Timeline(
                new KeyFrame(Duration.seconds(TRANSCRIPT_REFRESH_INTERVAL_SECONDS), e -> refreshTranscriptContents()));
        transcriptRefreshTimeline.setCycleCount(Animation.INDEFINITE);
        transcriptRefreshTimeline.play();
    }

    private static void refreshTranscriptContents() {
        if (liveTranscriptTextArea == null || transcriptStatusLabel == null) {
            return;
        }

        if (transcriptSessionDir == null) {
            liveTranscriptTextArea.setText("No session folder selected.\nUse 'Select Session Folder' to manage live transcription.");
            transcriptStatusLabel.setText("Transcript: session folder not selected");
            return;
        }

        if (transcriptFile == null) {
            transcriptFile = buildTranscriptFile(transcriptSessionDir);
        }

        if (transcriptFile == null) {
            liveTranscriptTextArea.setText("Transcript file is not configured.");
            transcriptStatusLabel.setText("Transcript: file unavailable");
            return;
        }

        if (!transcriptFile.exists()) {
            liveTranscriptTextArea.setText("");
            transcriptStatusLabel.setText("Transcript: waiting for file " + transcriptFile.getName());
            return;
        }

        long lastModified = transcriptFile.lastModified();
        if (lastModified == transcriptLastModified) {
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
            transcriptLastContents = contents;
            liveTranscriptTextArea.setText(contents.isBlank() ? "Transcript file is empty. Waiting for live text..." : contents);
            liveTranscriptTextArea.positionCaret(liveTranscriptTextArea.getLength());
            if (transcriptProcess != null && transcriptProcess.isAlive()) {
                transcriptStatusLabel.setText("Transcript: live transcription running");
            } else {
                transcriptStatusLabel.setText("Transcript: following " + transcriptFile.getName());
            }
        } catch (IOException e) {
            logger.warn("Failed to read transcript file {}", transcriptFile, e);
            liveTranscriptTextArea.setText(transcriptLastContents);
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
        button.setWrapText(true);
    }

    private static String selectTranscriptModel(String currentModel) {
        if (AVAILABLE_TRANSCRIPT_MODELS.contains(currentModel)) {
            return currentModel;
        }
        return "small";
    }

    private static String selectTranscriptLanguage(String languageCode) {
        String normalized = defaultIfBlank(languageCode, "en");
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
        if (transcriptSettingsLabel == null) {
            return;
        }

        String summary = String.format("Model=%s | Lang=%s | Chunk=%ss | Beam=%s | Best=%s",
                defaultIfBlank(transcriptModel.get(), "small"),
                defaultIfBlank(transcriptLanguage.get(), "en"),
                defaultIfBlank(transcriptChunkSeconds.get(), "3.5"),
                defaultIfBlank(transcriptBeamSize.get(), "5"),
                defaultIfBlank(transcriptBestOf.get(), "5"));
        transcriptSettingsLabel.setText(summary);
    }

    private static void showTranscriptSettingsDialog() {
        Dialog<javafx.scene.control.ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Transcript Settings");
        if (qupathGui != null) {
            dialog.initOwner(qupathGui.getStage());
        }

        var modelCombo = new ComboBox<String>();
        modelCombo.getItems().addAll(AVAILABLE_TRANSCRIPT_MODELS);
        modelCombo.setMaxWidth(Double.MAX_VALUE);
        modelCombo.setValue(selectTranscriptModel(defaultIfBlank(transcriptModel.get(), "small")));

        var languageCombo = new ComboBox<String>();
        languageCombo.getItems().addAll(AVAILABLE_TRANSCRIPT_LANGUAGES);
        languageCombo.setMaxWidth(Double.MAX_VALUE);
        languageCombo.setValue(selectTranscriptLanguage(defaultIfBlank(transcriptLanguage.get(), "en")));

        var chunkField = new TextField(defaultIfBlank(transcriptChunkSeconds.get(), "3.5"));
        var computeField = new TextField(defaultIfBlank(transcriptComputeType.get(), "int8"));
        var beamField = new TextField(defaultIfBlank(transcriptBeamSize.get(), "5"));
        var bestOfField = new TextField(defaultIfBlank(transcriptBestOf.get(), "5"));
        var previousTextBox = new CheckBox("Use previous text context");
        previousTextBox.setSelected(transcriptPreviousText.get());

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
        grid.addRow(0, new Label("Model"), modelCombo);
        grid.addRow(1, new Label("Language"), languageCombo);
        grid.addRow(2, new Label("Chunk seconds"), chunkField);
        grid.addRow(3, new Label("Compute type"), computeField);
        grid.addRow(4, new Label("Beam size"), beamField);
        grid.addRow(5, new Label("Best of"), bestOfField);
        grid.add(previousTextBox, 1, 6);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(javafx.scene.control.ButtonType.OK,
                javafx.scene.control.ButtonType.CANCEL);

        var result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != javafx.scene.control.ButtonType.OK) {
            return;
        }

        String oldModel = defaultIfBlank(transcriptModel.get(), "small");
        String oldLanguage = defaultIfBlank(transcriptLanguage.get(), "en");
        String oldChunkSeconds = defaultIfBlank(transcriptChunkSeconds.get(), "3.5");
        String oldComputeType = defaultIfBlank(transcriptComputeType.get(), "int8");
        String oldBeamSize = defaultIfBlank(transcriptBeamSize.get(), "5");
        String oldBestOf = defaultIfBlank(transcriptBestOf.get(), "5");
        boolean oldPreviousText = transcriptPreviousText.get();

        transcriptModel.set(defaultIfBlank(modelCombo.getValue(), "small"));
        transcriptLanguage.set(languageCodeFromSelection(languageCombo.getValue()));
        transcriptChunkSeconds.set(defaultIfBlank(chunkField.getText(), "3.5"));
        transcriptComputeType.set(defaultIfBlank(computeField.getText(), "int8"));
        transcriptBeamSize.set(defaultIfBlank(beamField.getText(), "5"));
        transcriptBestOf.set(defaultIfBlank(bestOfField.getText(), "5"));
        transcriptPreviousText.set(previousTextBox.isSelected());
        updateTranscriptSettingsSummary();

        boolean settingsChanged =
                !oldModel.equals(transcriptModel.get()) ||
                !oldLanguage.equals(transcriptLanguage.get()) ||
                !oldChunkSeconds.equals(transcriptChunkSeconds.get()) ||
                !oldComputeType.equals(transcriptComputeType.get()) ||
                !oldBeamSize.equals(transcriptBeamSize.get()) ||
                !oldBestOf.equals(transcriptBestOf.get()) ||
                oldPreviousText != transcriptPreviousText.get();

        if (settingsChanged && recordEvents.get()) {
            restartTranscriptProcessForUpdatedSettings();
        }
    }

    private static File buildTranscriptFile(File sessionDir) {
        if (sessionDir == null) {
            return null;
        }

        File videoDir = new File(sessionDir, "video");
        String sessionId = sessionDir.getName();
        return new File(videoDir, sessionId + "_transcript.txt");
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

    private static File findTranscriptLauncherScript() {
        for (Path root : getSearchRoots()) {
            Path current = root;
            for (int i = 0; current != null && i < 8; i++) {
                Path candidate = current.resolve("scripts").resolve("start_live_transcript.sh");
                if (Files.isRegularFile(candidate)) {
                    return candidate.toFile();
                }
                current = current.getParent();
            }
        }
        return null;
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
                    logger.info("Transcript process: {}", line);
                }
            } catch (IOException e) {
                logger.debug("Transcript process output reader stopped", e);
            } finally {
                Platform.runLater(() -> {
                    if (transcriptStatusLabel != null) {
                        if (recordEvents.get()) {
                            transcriptStatusLabel.setText("Transcript: process stopped");
                        } else {
                            transcriptStatusLabel.setText("Transcript: paused");
                        }
                    }
                    refreshTranscriptContents();
                });
            }
        }, "timestamp-transcript-output");
        thread.setDaemon(true);
        thread.start();
    }

    private static void stopTranscriptProcess() {
        if (transcriptProcess == null) {
            if (transcriptStatusLabel != null) {
                transcriptStatusLabel.setText("Transcript: paused");
            }
            return;
        }

        if (!transcriptProcess.isAlive()) {
            transcriptProcess = null;
            if (transcriptStatusLabel != null) {
                transcriptStatusLabel.setText("Transcript: paused");
            }
            return;
        }

        transcriptProcess.destroy();
        try {
            if (!transcriptProcess.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                transcriptProcess.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            transcriptProcess.destroyForcibly();
        } finally {
            transcriptProcess = null;
            if (transcriptStatusLabel != null) {
                transcriptStatusLabel.setText("Transcript: paused");
            }
        }
    }

    private static void clearLogsStatic() {
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
        
        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            // Write CSV header
            writer.println("Timestamp,Event Type,Details,View_X,View_Y,View_Width,View_Height,View_Z,View_T,Downsample,ROI_Type,ROI_BoundsX,ROI_BoundsY,ROI_BoundsWidth,ROI_BoundsHeight,ROI_NumPoints,ROI_Points");
            
            // Write all events
            for (EventRecord entry : eventLog) {
                writer.printf("%s,%s,\"%s\",%.1f,%.1f,%.1f,%.1f,%d,%d,%.4f",
                    formatter.format(entry.timestamp),
                    entry.eventType,
                    entry.details,
                    entry.view.x,
                    entry.view.y,
                    entry.view.width,
                    entry.view.height,
                    entry.view.z,
                    entry.view.t,
                    entry.view.downsample);

                if (entry.annotation != null) {
                    // Build points string: (x1 y1);(x2 y2);...
                    StringBuilder pts = new StringBuilder();
                    for (int j = 0; j < entry.annotation.points.size(); j++) {
                        double[] pt = entry.annotation.points.get(j);
                        if (j > 0) pts.append(';');
                        pts.append(String.format("(%.2f %.2f)", pt[0], pt[1]));
                    }
                    writer.printf(",%s,%.1f,%.1f,%.1f,%.1f,%d,\"%s\"%n",
                        entry.annotation.roiType,
                        entry.annotation.boundsX,
                        entry.annotation.boundsY,
                        entry.annotation.boundsWidth,
                        entry.annotation.boundsHeight,
                        entry.annotation.numPoints,
                        pts.toString());
                } else {
                    writer.printf(",,,,,,%n");
                }
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

        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            writer.println("{");
            writer.println("  \"exportTimestamp\": \"" + escapeJson(LocalDateTime.now().format(formatter)) + "\",");
            writer.println("  \"totalEvents\": " + eventLog.size() + ",");
            writer.println("  \"events\": [");

            for (int i = 0; i < eventLog.size(); i++) {
                EventRecord entry = eventLog.get(i);
                writer.println("    {");
                writer.println("      \"timestamp\": \"" + escapeJson(formatter.format(entry.timestamp)) + "\",");
                writer.println("      \"eventType\": \"" + escapeJson(entry.eventType) + "\",");
                writer.println("      \"details\": \"" + escapeJson(entry.details) + "\",");
                writer.println("      \"zoom_view\": {");
                writer.println("        \"x\": " + String.format("%.1f", entry.view.x) + ",");
                writer.println("        \"y\": " + String.format("%.1f", entry.view.y) + ",");
                writer.println("        \"width\": " + String.format("%.1f", entry.view.width) + ",");
                writer.println("        \"height\": " + String.format("%.1f", entry.view.height) + ",");
                writer.println("        \"z\": " + entry.view.z + ",");
                writer.println("        \"t\": " + entry.view.t + ",");
                writer.println("        \"downsample\": " + String.format("%.4f", entry.view.downsample));
                writer.print("      }");

                // Include annotation geometry if present
                if (entry.annotation != null) {
                    writer.println(",");
                    writer.println("      \"annotation\": {");
                    writer.println("        \"roi_type\": \"" + escapeJson(entry.annotation.roiType) + "\",");
                    writer.println("        \"bounds\": {");
                    writer.println("          \"x\": " + String.format("%.1f", entry.annotation.boundsX) + ",");
                    writer.println("          \"y\": " + String.format("%.1f", entry.annotation.boundsY) + ",");
                    writer.println("          \"width\": " + String.format("%.1f", entry.annotation.boundsWidth) + ",");
                    writer.println("          \"height\": " + String.format("%.1f", entry.annotation.boundsHeight));
                    writer.println("        },");
                    writer.println("        \"num_points\": " + entry.annotation.numPoints + ",");
                    writer.print("        \"points\": [");
                    for (int j = 0; j < entry.annotation.points.size(); j++) {
                        double[] pt = entry.annotation.points.get(j);
                        writer.print("[" + String.format("%.2f", pt[0]) + ", " + String.format("%.2f", pt[1]) + "]");
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

        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            writer.println("{");
            writer.println("  \"exportTimestamp\": \"" + escapeJson(LocalDateTime.now().format(formatter)) + "\",");
            writer.println("  \"totalEvents\": " + mouseMoveLog.size() + ",");
            writer.println("  \"events\": [");

            for (int i = 0; i < mouseMoveLog.size(); i++) {
                EventRecord entry = mouseMoveLog.get(i);
                writer.println("    {");
                writer.println("      \"timestamp\": \"" + escapeJson(formatter.format(entry.timestamp)) + "\",");
                writer.println("      \"eventType\": \"" + escapeJson(entry.eventType) + "\",");
                writer.println("      \"details\": \"" + escapeJson(entry.details) + "\",");
                writer.println("      \"zoom_view\": {");
                writer.println("        \"x\": " + String.format("%.1f", entry.view.x) + ",");
                writer.println("        \"y\": " + String.format("%.1f", entry.view.y) + ",");
                writer.println("        \"width\": " + String.format("%.1f", entry.view.width) + ",");
                writer.println("        \"height\": " + String.format("%.1f", entry.view.height) + ",");
                writer.println("        \"z\": " + entry.view.z + ",");
                writer.println("        \"t\": " + entry.view.t + ",");
                writer.println("        \"downsample\": " + String.format("%.4f", entry.view.downsample));
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
    private static String escapeJson(String value) {
        if (value == null) return "";
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
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
    private static class ViewBounds {
        final double x;
        final double y;
        final double width;
        final double height;
        final int z;
        final int t;
        final double downsample;

        ViewBounds(double x, double y, double width, double height,
                   int z, int t, double downsample) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.z = z;
            this.t = t;
            this.downsample = downsample;
        }
    }

    /**
     * Geometry of an annotation ROI (bounding box + polygon vertices).
     */
    private static class AnnotationGeometry {
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
    private static class EventRecord {
        final LocalDateTime timestamp;
        final String eventType;
        final String details;
        final ViewBounds view;
        final AnnotationGeometry annotation; // null for non-annotation events
        
        EventRecord(LocalDateTime timestamp, String eventType, String details,
                     ViewBounds view, AnnotationGeometry annotation) {
            this.timestamp = timestamp;
            this.eventType = eventType;
            this.details = details;
            this.view = view;
            this.annotation = annotation;
        }
    }

    /**
     * Per-viewer gesture state used to collapse repeated drag/scroll events into
     * a single start/end record.
     */
    private static class ViewerInteractionState {
        boolean panInProgress = false;
        boolean zoomInProgress = false;
        String lastZoomEventType = null;
        final PauseTransition zoomEndDelay = new PauseTransition(Duration.millis(GESTURE_END_DELAY_MS));
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
            
            // Show current time and last event
            String currentTime = formatCurrentTime();
            String lastEvent = "";
            
            if (!eventLog.isEmpty()) {
                EventRecord last = eventLog.get(eventLog.size() - 1);
                lastEvent = String.format("Last: %s (%s)", 
                    last.eventType, 
                    formatter.format(last.timestamp));
            }
            
            // Set up font and color
            int fontSize = (int)(timestampFontSize.get() / downsampleFactor);
            fontSize = Math.max(12, fontSize); // Minimum readable size
            Font font = new Font("SansSerif", Font.BOLD, fontSize);
            g2d.setFont(font);
            
            // Position in top-left corner
            FontMetrics metrics = g2d.getFontMetrics();
            int x = 20;
            int y = 20 + metrics.getAscent();
            
            // Draw current time
            int padding = 10;
            int textWidth = metrics.stringWidth(currentTime);
            int textHeight = metrics.getHeight();
            
            g2d.setColor(new java.awt.Color(0, 0, 0, 180)); // Semi-transparent black
            g2d.fillRect(x - padding, y - metrics.getAscent() - padding, 
                        textWidth + 2 * padding, textHeight + padding);
            
            g2d.setColor(java.awt.Color.WHITE);
            g2d.drawString(currentTime, x, y);
            
            // Draw last event below current time
            if (!lastEvent.isEmpty()) {
                int y2 = y + textHeight + 5;
                int eventWidth = metrics.stringWidth(lastEvent);
                
                g2d.setColor(new java.awt.Color(0, 0, 0, 180));
                g2d.fillRect(x - padding, y2 - metrics.getAscent() - padding, 
                            eventWidth + 2 * padding, textHeight + padding);
                
                g2d.setColor(java.awt.Color.YELLOW);
                g2d.drawString(lastEvent, x, y2);
            }
        }
        
        private String formatCurrentTime() {
            LocalDateTime now = LocalDateTime.now();
            return formatter.format(now);
        }
    }
}
