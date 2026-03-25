package qupath.ext.timestamp;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.scene.control.MenuItem;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
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
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyEvent;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyEvent.HierarchyEventType;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener;
import qupath.lib.regions.ImageRegion;
import qupath.lib.roi.interfaces.ROI;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
    
    private boolean isInstalled = false;
    private static final String TIMESTAMP_CATEGORY = "TimeStamp";
    
    // Event logs to store timestamped events
    private static final List<EventRecord> eventLog = new ArrayList<>();
    private static final List<EventRecord> mouseMoveLog = new ArrayList<>();
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    
    // Persistent preferences
    private static final BooleanProperty enableTimestamp = PathPrefs.createPersistentPreference(
            "timestamp.enable", true);
            
    private static final BooleanProperty enableMouseTracking = PathPrefs.createPersistentPreference(
            "timestamp.trackMouse", false);
    
    private static final DoubleProperty timestampFontSize = PathPrefs.createPersistentPreference(
            "timestamp.fontSize", 24.0);
    
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
        
        addPreferences(qupath);
        addMenuItem(qupath);
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
        
        var fontSizeProperty = new PropertyItemBuilder<>(timestampFontSize, Double.class)
                .name("Font size")
                .category(TIMESTAMP_CATEGORY)
                .description("Font size for timestamp display")
                .build();
        
        qupath.getPreferencePane()
                .getPropertySheet()
                .getItems()
                .addAll(enableProperty, enableMouseProperty, fontSizeProperty);
    }
    
    private void addMenuItem(QuPathGUI qupath) {
        var menu = qupath.getMenu("Extensions>" + EXTENSION_NAME, true);
        
        MenuItem toggleItem = new MenuItem("Toggle timestamp display");
        toggleItem.setOnAction(e -> {
            enableTimestamp.set(!enableTimestamp.get());
            qupath.getAllViewers().forEach(QuPathViewer::repaint);
        });
        menu.getItems().add(toggleItem);
        
        MenuItem showLogItem = new MenuItem("Show event log");
        showLogItem.setOnAction(e -> showEventLog());
        menu.getItems().add(showLogItem);
        
        MenuItem clearLogItem = new MenuItem("Clear event log");
        clearLogItem.setOnAction(e -> {
            eventLog.clear();
            mouseMoveLog.clear();
            logger.info("Event logs cleared");
            Dialogs.showInfoNotification(TIMESTAMP_CATEGORY, "Event and Mouse logs cleared");
        });
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
    
    /**
     * Install event listeners to track user interactions
     */
    private void installEventListeners(QuPathViewer viewer) {
        var view = viewer.getView();
        
        // Track mouse clicks
        view.addEventFilter(MouseEvent.MOUSE_CLICKED, event -> {
            if (enableTimestamp.get()) {
                logEvent("Click", String.format("x=%.1f, y=%.1f, button=%s", 
                    event.getX(), event.getY(), event.getButton()), viewer);
                viewer.repaint();
            }
        });
        
        // Track zoom events
        view.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (enableTimestamp.get() && event.getDeltaY() != 0) {
                String direction = event.getDeltaY() > 0 ? "Zoom In" : "Zoom Out";
                logEvent(direction, String.format("downsample=%.2f", viewer.getDownsampleFactor()), viewer);
                viewer.repaint();
            }
        });
        
        // Track panning (mouse drag)
        view.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (enableTimestamp.get() && event.isPrimaryButtonDown()) {
                logEvent("Pan", String.format("x=%.1f, y=%.1f", event.getX(), event.getY()), viewer);
            }
        });

        // Track mouse movement (throttled to avoid log spam, e.g., max 10 times per second)
        long[] lastMouseMoveTime = {0};
        view.addEventFilter(MouseEvent.MOUSE_MOVED, event -> {
            if (enableTimestamp.get()) {
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
            if (!enableTimestamp.get()) return;
            if (event.isChanging()) return;
            if (event.getEventType() != HierarchyEventType.ADDED) return;

            for (PathObject pathObject : event.getChangedObjects()) {
                if (pathObject.isAnnotation() && pathObject.getROI() != null) {
                    ROI roi = pathObject.getROI();

                    // Collect polygon vertices
                    List<double[]> vertexList = new ArrayList<>();
                    for (var p : roi.getAllPoints()) {
                        vertexList.add(new double[]{p.getX(), p.getY()});
                    }

                    AnnotationGeometry geom = new AnnotationGeometry(
                            roi.getRoiName(),
                            roi.getBoundsX(), roi.getBoundsY(),
                            roi.getBoundsWidth(), roi.getBoundsHeight(),
                            roi.getNumPoints(), vertexList);

                    String details = String.format("type=%s, points=%d, bounds=[%.1f, %.1f, %.1f, %.1f]",
                            roi.getRoiName(), roi.getNumPoints(),
                            roi.getBoundsX(), roi.getBoundsY(),
                            roi.getBoundsWidth(), roi.getBoundsHeight());

                    logEvent("Annotate", details, viewer, geom);
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
        }
        
        if (logger.isInfoEnabled()) {
            logger.info("Event: {} - {} - {} - view:[x={}, y={}, w={}, h={}, z={}, t={}]",
                formatter.format(now), eventType, details, vx, vy, vw, vh, vz, vt);
        }
    }
    
    /**
     * Show the event log in a dialog
     */
    private void showEventLog() {
        StringBuilder sb = new StringBuilder();
        sb.append("Event Log (").append(eventLog.size()).append(" events):\n\n");
        
        int maxEvents = Math.min(100, eventLog.size()); // Show last 100 events
        int startIndex = Math.max(0, eventLog.size() - maxEvents);
        
        for (int i = startIndex; i < eventLog.size(); i++) {
            EventRecord entry = eventLog.get(i);
            sb.append(String.format("[%s] %s: %s%n", 
                formatter.format(entry.timestamp), 
                entry.eventType, 
                entry.details));
        }
        
        Dialogs.showMessageDialog("Event Log", sb.toString());
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

