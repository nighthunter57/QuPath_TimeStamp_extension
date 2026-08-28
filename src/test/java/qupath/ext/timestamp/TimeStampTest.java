package qupath.ext.timestamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.Instant;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.ObjectInputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TimeStampTest {

    @Test
    void exposesExtensionVersionForPanel() {
        assertEquals("0.1.0", TimeStamp.extensionVersion());
    }

    @Test
    void packagesTranscriptHelper() {
        assertNotNull(TimeStamp.class.getResourceAsStream(
                "/qupath/ext/timestamp/scripts/live_whisper_demo.py"));
    }

    @Test
    void resolvesDoctorRuntimeAndModelCacheInsideQuPathUserDirectory(@TempDir Path temporaryDirectory) {
        assertEquals(
                temporaryDirectory.resolve("timestamp/runtime/.venv/bin/python"),
                TimeStamp.doctorRuntimePython(temporaryDirectory, false));
        assertEquals(
                temporaryDirectory.resolve("timestamp/runtime/.venv/Scripts/python.exe"),
                TimeStamp.doctorRuntimePython(temporaryDirectory, true));
        assertEquals(
                temporaryDirectory.resolve("timestamp/model-cache"),
                TimeStamp.doctorModelCache(temporaryDirectory));
    }

    @Test
    void formatsJsonNumbersIndependentlyOfDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("12.50", TimeStamp.formatDecimal("%.2f", 12.5));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void escapesCsvFields() {
        assertEquals("plain", TimeStamp.csvEscape("plain"));
        assertEquals("\"left, right\"", TimeStamp.csvEscape("left, right"));
        assertEquals("\"a\"\"b\"", TimeStamp.csvEscape("a\"b"));
    }

    @Test
    void escapesJsonControlCharacters() {
        assertEquals("line\\n\\t\\\"value\\\"", TimeStamp.escapeJson("line\n\t\"value\""));
        assertEquals("\\u0001", TimeStamp.escapeJson("\u0001"));
    }

    @Test
    void formatsCompactOverlayTimeWithoutDateOrMilliseconds() {
        LocalDateTime time = LocalDateTime.of(2026, 7, 31, 12, 17, 31, 737_000_000);
        assertEquals("12:17:31", TimeStamp.formatOverlayTime(time));
    }

    @Test
    void showsOnlyRecentEventsInOverlay() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 31, 12, 17, 35);
        assertTrue(TimeStamp.isOverlayEventRecent(now.minusSeconds(4), now));
        assertFalse(TimeStamp.isOverlayEventRecent(now.minusSeconds(5), now));
        assertFalse(TimeStamp.isOverlayEventRecent(now.plusSeconds(1), now));
    }

    @Test
    void createsCollisionSafeTranscriptArchiveDirectories(@TempDir Path temporaryDirectory) throws Exception {
        LocalDateTime timestamp = LocalDateTime.of(2026, 8, 12, 12, 34, 56, 789_000_000);

        Path first = TimeStamp.createUniqueArchiveDirectory(temporaryDirectory, timestamp);
        Path second = TimeStamp.createUniqueArchiveDirectory(temporaryDirectory, timestamp);

        assertTrue(first.toFile().isDirectory());
        assertTrue(second.toFile().isDirectory());
        assertFalse(first.equals(second));
        assertEquals("20260812_123456_789", first.getFileName().toString());
        assertEquals("20260812_123456_789_1", second.getFileName().toString());
    }

    @Test
    void locksDoctorsToClinicalHighAccuracySettings() {
        TimeStamp.ClinicalTranscriptSettings settings = TimeStamp.clinicalTranscriptSettings();
        assertEquals("large-v3", settings.finalModel());
        assertEquals("10.0", settings.liveContextSeconds());
        assertEquals("int8_float32", settings.computeType());
        assertEquals("8", settings.beamSize());
        assertEquals("8", settings.bestOf());
        assertTrue(settings.previousText());
    }

    @Test
    void parsesEveryTranscriptProtocolMessageAndRejectsBadGrammar() {
        List<String> messages = List.of(
                "DEVICE\tBuilt-in Microphone\t0 - Built-in Microphone",
                "AUDIO_CHECK_READY",
                "AUDIO_CHECK_RESULT\t0.012\thearing",
                "AUDIO_LEVEL\t0.004\thearing",
                "AUDIO_CLIPPING\t0.125",
                "AUDIO_SILENT\t30.0",
                "AUDIO_RECOVERED",
                "TRANSCRIPT_READY",
                "RECORDING_ORIGIN\t2026-08-25T20:00:00.000Z",
                "LIVE_MODEL_READY\tsmall.en",
                "TRANSCRIPT_UPDATED",
                "TRANSCRIPT_PARTIAL\tprovisional words",
                "FINALIZE_PROGRESS\t12.0\t60.0",
                "FINALIZATION_RESULT\tfinal");
        for (String message : messages) {
            TimeStamp.TranscriptMessage parsed = TimeStamp.parseTranscriptMessage(message);
            assertFalse(parsed.type() == TimeStamp.TranscriptMessageType.MALFORMED, message);
            assertFalse(parsed.type() == TimeStamp.TranscriptMessageType.LOG, message);
        }
        assertEquals(TimeStamp.TranscriptMessageType.MALFORMED,
                TimeStamp.parseTranscriptMessage("AUDIO_LEVEL\tnot-a-number\thearing").type());
        assertEquals(TimeStamp.TranscriptMessageType.MALFORMED,
                TimeStamp.parseTranscriptMessage("AUDIO_CLIPPING\tnot-a-number").type());
        assertEquals(TimeStamp.TranscriptMessageType.MALFORMED,
                TimeStamp.parseTranscriptMessage("TRANSCRIPT_READY\textra").type());
        assertEquals(TimeStamp.TranscriptMessageType.LOG,
                TimeStamp.parseTranscriptMessage("ordinary diagnostic output").type());
    }

    @Test
    void suppressesOnlyTranscriptLinesOverEightyWords() {
        String eightyWords = String.join(" ", java.util.Collections.nCopies(80, "word"));
        String eightyOneWords = String.join(" ", java.util.Collections.nCopies(81, "loop"));
        String contents = "[2026-08-26T12:00:00.000] " + eightyWords + "\n" +
                "[2026-08-26T12:00:01.000] " + eightyOneWords + "\n";

        String rendered = TimeStamp.suppressRunawayTranscriptLines(contents);

        assertTrue(rendered.contains(eightyWords));
        assertTrue(rendered.contains(
                "[2026-08-26T12:00:01.000] [decode error suppressed]"));
        assertFalse(rendered.contains(eightyOneWords));
        assertTrue(rendered.endsWith("\n"));
    }

    @Test
    void followsTranscriptOnlyWhenViewportWasAtTail() {
        assertTrue(TimeStamp.shouldFollowTranscriptTail(1.0, 1.0, 0, 100));
        assertTrue(TimeStamp.shouldFollowTranscriptTail(0.9995, 1.0, 0, 100));
        assertFalse(TimeStamp.shouldFollowTranscriptTail(0.5, 1.0, 100, 100));
        assertTrue(TimeStamp.shouldFollowTranscriptTail(0.0, 0.0, 100, 100));
        assertFalse(TimeStamp.shouldFollowTranscriptTail(0.0, 0.0, 50, 100));
    }

    @Test
    void choosesResponsivePanelOrientationAndClampsDivider() {
        assertFalse(TimeStamp.usesHorizontalPanelLayout(719.9));
        assertTrue(TimeStamp.usesHorizontalPanelLayout(720.0));
        assertEquals(0.15, TimeStamp.clampPanelDivider(-1.0));
        assertEquals(0.5, TimeStamp.clampPanelDivider(Double.NaN));
        assertEquals(0.85, TimeStamp.clampPanelDivider(2.0));
    }

    @Test
    void formatsRecordingAndEventElapsedTimes() {
        Instant origin = Instant.parse("2026-08-26T20:00:00Z");
        assertEquals("02:14", TimeStamp.formatRecordingElapsed(
                origin, origin.plusSeconds(134)));
        assertEquals("1:02:03", TimeStamp.formatRecordingElapsed(
                origin, origin.plusSeconds(3_723)));
        assertEquals("00:00", TimeStamp.formatRecordingElapsed(null, origin));
        assertEquals("02:14", TimeStamp.formatEventElapsed(134_999L));
        assertEquals("—", TimeStamp.formatEventElapsed(null));
    }

    @Test
    void usesSeparateCaptureAndFinalizationHelperModes() {
        assertEquals(List.of("--capture-only"),
                TimeStamp.transcriptLifecycleArguments(false));
        assertEquals(List.of("--finalize-existing"),
                TimeStamp.transcriptLifecycleArguments(true));
    }

    @Test
    void mapsPauseResumeDoneWorkflowActionsWithoutEndingTheTake() {
        assertEquals(TimeStamp.RecordingPrimaryAction.START,
                TimeStamp.recordingPrimaryAction(TimeStamp.RecordingWorkflowState.READY));
        assertEquals(TimeStamp.RecordingPrimaryAction.PAUSE,
                TimeStamp.recordingPrimaryAction(TimeStamp.RecordingWorkflowState.RECORDING));
        assertEquals(TimeStamp.RecordingPrimaryAction.RESUME,
                TimeStamp.recordingPrimaryAction(TimeStamp.RecordingWorkflowState.PAUSED));
        assertEquals(TimeStamp.RecordingPrimaryAction.SAVE,
                TimeStamp.recordingPrimaryAction(TimeStamp.RecordingWorkflowState.UNSAVED_REVIEW));
        assertEquals(TimeStamp.RecordingPrimaryAction.WAIT,
                TimeStamp.recordingPrimaryAction(TimeStamp.RecordingWorkflowState.FINALIZING));
    }

    @Test
    void mapsTranscriptWindowsAndEventsByRecordingClock() {
        String contents = "[2026-08-26T12:00:00.000] first line\n" +
                "[2026-08-26T12:00:05.000] second line\n";
        TimeStamp.TranscriptWindow second = TimeStamp.transcriptWindowAtCaret(
                contents, contents.indexOf("second"));
        Instant firstTime = LocalDateTime.parse("2026-08-26T12:00:00.000")
                .atZone(java.time.ZoneId.systemDefault()).toInstant();

        assertNotNull(second);
        assertEquals(contents.indexOf("[2026", 1), second.start());
        assertEquals(firstTime.plusSeconds(5), second.startTime());
        assertEquals(firstTime, TimeStamp.transcriptWindowForInstant(
                contents, firstTime.plusSeconds(3)).startTime());
        assertEquals(1, TimeStamp.nearestInstantIndex(
                List.of(firstTime, firstTime.plusSeconds(5)), firstTime.plusSeconds(4)));
    }

    @Test
    void derivesElapsedTimeOnlyFromUtcRecordingOrigin() {
        Instant origin = Instant.parse("2026-08-25T20:00:00Z");
        assertEquals(1250L, TimeStamp.derivedElapsedMillis(
                origin, Instant.parse("2026-08-25T20:00:01.250Z")));
        assertNull(TimeStamp.derivedElapsedMillis(null, Instant.now()));
        assertNull(TimeStamp.derivedElapsedMillis(
                origin, Instant.parse("2026-08-25T19:59:59.999Z")));
    }

    @Test
    void scalesFinalizationTimeoutWithRecordingDuration() {
        assertEquals(600L, TimeStamp.computeFinalizeTimeoutSeconds(0));
        assertEquals(700L, TimeStamp.computeFinalizeTimeoutSeconds(100));
        assertEquals(14_400L, TimeStamp.computeFinalizeTimeoutSeconds(10_000));
        assertEquals(600L, TimeStamp.computeFinalizeTimeoutSeconds(Double.NaN));
    }

    @Test
    void atomicallyReplacesSavedSessionArtifact(@TempDir Path temporaryDirectory) throws Exception {
        Path artifact = temporaryDirectory.resolve("events").resolve("case_event.json");

        TimeStamp.atomicWriteString(artifact, "first snapshot");
        TimeStamp.atomicWriteString(artifact, "complete snapshot");

        assertEquals("complete snapshot", Files.readString(artifact));
        assertFalse(Files.exists(artifact.resolveSibling("case_event.json.tmp")));
    }

    @Test
    void atomicallyCopiesWorkingRecordingFileToChosenFolder(@TempDir Path temporaryDirectory) throws Exception {
        Path source = temporaryDirectory.resolve("working").resolve("capture_audio.wav");
        Path destination = temporaryDirectory.resolve("chosen-session")
                .resolve("video").resolve("chosen-session_audio.wav");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "first capture");

        TimeStamp.copyFileAtomically(source.toFile(), destination.toFile());
        Files.writeString(source, "final capture");
        TimeStamp.copyFileAtomically(source.toFile(), destination.toFile());

        assertEquals("final capture", Files.readString(destination));
        assertFalse(Files.exists(destination.resolveSibling("chosen-session_audio.wav.tmp")));
    }

    @Test
    void acceptsOnlySingleSafeSessionFolderNames() {
        assertTrue(TimeStamp.isValidSessionName("case-2026_08_20"));
        assertFalse(TimeStamp.isValidSessionName(""));
        assertFalse(TimeStamp.isValidSessionName(".."));
        assertFalse(TimeStamp.isValidSessionName("case/subfolder"));
        assertFalse(TimeStamp.isValidSessionName("case\\subfolder"));
    }

    @Test
    void validatesPersistedArtifactContents(@TempDir Path temporaryDirectory) throws Exception {
        Path artifact = temporaryDirectory.resolve("session_manifest.json");
        TimeStamp.atomicWriteString(artifact, "{\"workflowState\":\"complete\"}");

        TimeStamp.validateExactTextFile(
                artifact, "{\"workflowState\":\"complete\"}", "recording manifest");
    }

    @Test
    @SuppressWarnings("unchecked")
    void savesCompleteTimestampSnapshotAndCommittedManifest(@TempDir Path temporaryDirectory) throws Exception {
        Class<?> viewClass = Class.forName("qupath.ext.timestamp.TimeStamp$ViewBounds");
        Constructor<?> viewConstructor = viewClass.getDeclaredConstructor(
                double.class, double.class, double.class, double.class,
                double.class, double.class, int.class, int.class, double.class, double.class);
        viewConstructor.setAccessible(true);
        Object view = viewConstructor.newInstance(10.0, 20.0, 300.0, 200.0,
                160.0, 120.0, 0, 0, 2.0, 0.0);

        Class<?> annotationClass = Class.forName("qupath.ext.timestamp.TimeStamp$AnnotationGeometry");
        Class<?> eventClass = Class.forName("qupath.ext.timestamp.TimeStamp$EventRecord");
        Constructor<?> eventConstructor = eventClass.getDeclaredConstructor(
                long.class, LocalDateTime.class, Instant.class,
                String.class, String.class, viewClass, annotationClass, String.class);
        eventConstructor.setAccessible(true);
        Instant recordedAt = Instant.parse("2026-08-23T20:00:01Z");
        Object event = eventConstructor.newInstance(
                1L, LocalDateTime.of(2026, 8, 23, 15, 0, 1), recordedAt,
                "Click", "imageX=42.0, imageY=84.0", view, null, "working-session");

        Field eventLogField = TimeStamp.class.getDeclaredField("eventLog");
        eventLogField.setAccessible(true);
        List<Object> events = (List<Object>) eventLogField.get(null);
        Field mouseLogField = TimeStamp.class.getDeclaredField("mouseMoveLog");
        mouseLogField.setAccessible(true);
        List<Object> mouseEvents = (List<Object>) mouseLogField.get(null);
        Field recordingOriginField = TimeStamp.class.getDeclaredField("recordingStartedInstant");
        recordingOriginField.setAccessible(true);
        Object previousRecordingOrigin = recordingOriginField.get(null);

        Path session = temporaryDirectory.resolve("doctor-session");
        Path transcript = session.resolve("video/doctor-session_transcript.txt");
        Files.createDirectories(transcript.getParent());
        Files.writeString(transcript, "[2026-08-23T15:00:01.000] test transcript\n");

        Method saveArtifacts = TimeStamp.class.getDeclaredMethod(
                "saveSessionArtifacts", java.io.File.class, java.io.File.class,
                String.class, int.class);
        saveArtifacts.setAccessible(true);

        events.clear();
        mouseEvents.clear();
        events.add(event);
        recordingOriginField.set(null, Instant.parse("2026-08-23T19:59:59.750Z"));
        try {
            assertTrue((boolean) saveArtifacts.invoke(
                    null, session.toFile(), transcript.toFile(), "final", 0));

            String csv = Files.readString(session.resolve("events/doctor-session_event.csv"));
            String json = Files.readString(session.resolve("events/doctor-session_event.json"));
            String cursor = Files.readString(session.resolve("cursor/doctor-session_cursor.json"));
            String manifest = Files.readString(session.resolve("doctor-session_recording_manifest.json"));
            assertTrue(csv.contains("Sequence,Timestamp,Recorded_At_UTC,Elapsed_ms"));
            assertTrue(csv.contains("doctor-session,1"));
            assertTrue(json.contains("\"recordedAtUtc\": \"2026-08-23T20:00:01Z\""));
            assertTrue(json.contains("\"elapsedMs\": 1250"));
            assertTrue(cursor.contains("\"totalEvents\": 0"));
            assertTrue(manifest.contains("\"workflowState\": \"complete\""));
            assertTrue(manifest.contains("\"count\": 1"));
        } finally {
            events.clear();
            mouseEvents.clear();
            recordingOriginField.set(null, previousRecordingOrigin);
        }
    }

    @Test
    void excludesAudioByDefaultAndCopiesItWhenRequested(@TempDir Path temporaryDirectory) throws Exception {
        Path source = temporaryDirectory.resolve("working/video/working_transcript.txt");
        Path destination = temporaryDirectory.resolve("saved/video/saved_transcript.txt");
        Files.createDirectories(source.getParent());
        Files.createDirectories(destination.getParent());
        Files.writeString(source, "machine transcript");
        Files.writeString(source.resolveSibling("working_transcript_live.txt"), "live transcript");
        Files.writeString(source.resolveSibling("working_transcript_segments.csv"), "segment header\n");
        Files.writeString(source.resolveSibling("working_transcript_words.csv"), "word header\n");
        Files.writeString(source.resolveSibling("working_transcript_audio.raw"), "raw audio");
        Files.writeString(source.resolveSibling("working_transcript_audio.wav"), "wave audio");
        Files.writeString(source.resolveSibling("working_transcript_audio.start.txt"), "start time");
        Path savedRaw = destination.resolveSibling("saved_transcript_audio.raw");
        Files.writeString(savedRaw, "stale audio");

        Method copyWorkingFiles = TimeStamp.class.getDeclaredMethod(
                "copyWorkingRecordingFiles", java.io.File.class, java.io.File.class, boolean.class);
        copyWorkingFiles.setAccessible(true);
        copyWorkingFiles.invoke(null, source.toFile(), destination.toFile(), false);

        assertFalse(Files.exists(savedRaw));
        assertEquals("machine transcript",
                Files.readString(destination.resolveSibling("saved_transcript_timed.txt")));
        assertEquals("segment header\n",
                Files.readString(destination.resolveSibling("saved_transcript_segments.csv")));

        copyWorkingFiles.invoke(null, source.toFile(), destination.toFile(), true);
        assertEquals("raw audio", Files.readString(savedRaw));
        assertEquals("wave audio",
                Files.readString(destination.resolveSibling("saved_transcript_audio.wav")));
    }

    @Test
    void checkpointsUnsavedWorkingSessionForCrashRecovery(@TempDir Path temporaryDirectory) throws Exception {
        Field sessionDirectoryField = TimeStamp.class.getDeclaredField("transcriptSessionDir");
        Field dirtyField = TimeStamp.class.getDeclaredField("recordingSessionDirty");
        Field checkpointInProgressField = TimeStamp.class.getDeclaredField("recoveryCheckpointInProgress");
        sessionDirectoryField.setAccessible(true);
        dirtyField.setAccessible(true);
        checkpointInProgressField.setAccessible(true);
        Object previousSessionDirectory = sessionDirectoryField.get(null);
        boolean previousDirty = dirtyField.getBoolean(null);

        Method checkpoint = TimeStamp.class.getDeclaredMethod("checkpointWorkingSession");
        checkpoint.setAccessible(true);
        Path snapshot = temporaryDirectory.resolve(".timestamp-recovery.bin");
        try {
            sessionDirectoryField.set(null, temporaryDirectory.toFile());
            dirtyField.setBoolean(null, true);
            checkpoint.invoke(null);
            for (int attempt = 0; attempt < 100 && !Files.isRegularFile(snapshot); attempt++) {
                Thread.sleep(20L);
            }
            assertTrue(Files.isRegularFile(snapshot));
            try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(snapshot))) {
                Object recovered = input.readObject();
                assertEquals("RecoverySnapshot", recovered.getClass().getSimpleName());
            }
        } finally {
            for (int attempt = 0; attempt < 100 && checkpointInProgressField.getBoolean(null); attempt++) {
                Thread.sleep(20L);
            }
            sessionDirectoryField.set(null, previousSessionDirectory);
            dirtyField.setBoolean(null, previousDirty);
        }
    }
}
