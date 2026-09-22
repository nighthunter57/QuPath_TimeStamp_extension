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
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TimeStampTest {

    @Test
    void imageIdentitySurvivesEventExportAndRecovery(@TempDir Path directory) throws Exception {
        RecordedImage first = RecordedImage.identify("project:file:///case.qpproj#1", "Slide A");
        RecordedImage second = RecordedImage.identify("project:file:///case.qpproj#2", "Slide B");
        assertFalse(first.id().equals(second.id()));
        assertEquals(first.id(), RecordedImage.identify(first.source(), "Renamed slide").id());
        assertEquals("null", TimeStamp.recordedImageJson(null));
        Class<?> viewClass = Class.forName("qupath.ext.timestamp.TimeStamp$ViewBounds");
        var viewConstructor = viewClass.getDeclaredConstructor(double.class, double.class,
                double.class, double.class, double.class, double.class, int.class, int.class,
                double.class, double.class, RecordedImage.class);
        viewConstructor.setAccessible(true);
        Class<?> eventClass = Class.forName("qupath.ext.timestamp.TimeStamp$EventRecord");
        Class<?> annotationClass = Class.forName("qupath.ext.timestamp.TimeStamp$AnnotationGeometry");
        var eventConstructor = eventClass.getDeclaredConstructor(long.class, LocalDateTime.class,
                Instant.class, String.class, String.class, viewClass, annotationClass, String.class);
        eventConstructor.setAccessible(true);
        var events = new java.util.ArrayList<>();
        for (RecordedImage image : List.of(first, second)) {
            Object view = viewConstructor.newInstance(10., 20., 300., 200., 160., 120., 0, 0, 2., 0., image);
            events.add(eventConstructor.newInstance((long) events.size() + 1, LocalDateTime.now(),
                    Instant.now(), "Click", "same coordinates", view, null, "case"));
        }
        Path checkpoint = directory.resolve("events.bin");
        try (var output = new java.io.ObjectOutputStream(Files.newOutputStream(checkpoint))) {
            output.writeObject(events);
        }
        try (var input = new ObjectInputStream(Files.newInputStream(checkpoint))) {
            var recovered = (List<?>) input.readObject();
            var json = TimeStamp.class.getDeclaredMethod("serializeEventJson", List.class, String.class, String.class);
            json.setAccessible(true);
            String contents = (String) json.invoke(null, recovered, "events", "case");
            assertTrue(contents.contains(first.id()));
            assertTrue(contents.contains(second.id()));
            var csv = TimeStamp.class.getDeclaredMethod("serializeEventCsv", List.class, String.class);
            csv.setAccessible(true);
            String rows = (String) csv.invoke(null, recovered, "case");
            assertTrue(rows.contains("Image_ID,Image_Name,Image_Source"));
            assertTrue(rows.contains(first.id()));
            assertTrue(rows.contains(second.id()));
        }
    }

    @Test
    void reviewRevisionsSurviveResumeAndRequireExplicitResolution(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("take_previous_review.json");
        String machine = "negative\n";
        String edited = "clear\n";
        String review = TimeStamp.reviewedTranscriptJson(edited, List.of());
        var revision = ReviewRevisionStore.preserve(file, machine, review);
        assertEquals(edited, ReviewRevisionStore.read(file).text());
        assertEquals("clear\nnew speech\n", revision.project("negative\nnew speech\n"));
        assertEquals("different final draft", revision.project("different final draft"));
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> ReviewRevisionStore.preserve(file, "new", review));
        ReviewRevisionStore.resolve(file, revision);
        assertTrue(ReviewRevisionStore.read(file).resolved());
        assertEquals(edited, ReviewRevisionStore.read(file).text());
        ReviewRevisionStore.preserve(file, "new", review);
        try (var history = Files.list(directory.resolve("review-history"))) {
            assertEquals(2, history.count());
        }
        Files.writeString(file, "broken revision");
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> ReviewRevisionStore.read(file));
        assertEquals("broken revision", Files.readString(file));
    }

    @Test
    void exportsRejectWorkingFoldersAndFileAliases(@TempDir Path directory) throws Exception {
        Path working = Files.createDirectory(directory.resolve("working"));
        Path source = Files.writeString(working.resolve("audio.wav"), "original audio");
        for (Path target : List.of(working, working.resolve("export"), directory)) {
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                    () -> ExportSafety.requireSeparateDestination(working, target));
        }
        ExportSafety.requireSeparateDestination(working, directory.resolve("exports/session"));
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> ExportSafety.requireDifferentFiles(source, source));
        Path alias = directory.resolve("alias");
        try {
            Files.createSymbolicLink(alias, working);
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                    () -> ExportSafety.requireSeparateDestination(working, alias.resolve("export")));
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException ignored) {
            // Symbolic links may be unavailable without developer privileges on Windows.
        }
        Path hardLink = directory.resolve("audio-link.wav");
        try {
            Files.createLink(hardLink, source);
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                    () -> ExportSafety.requireDifferentFiles(source, hardLink));
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException ignored) {
            // Some filesystems do not implement hard links.
        }
        assertEquals("original audio", Files.readString(source));
    }

    @Test
    void excludingAudioNeverDeletesAnAliasedSource(@TempDir Path directory) throws Exception {
        Path transcript = Files.writeString(directory.resolve("take.txt"), "machine text");
        Path audio = Files.writeString(directory.resolve("take_audio.wav"), "original audio");
        var copy = TimeStamp.class.getDeclaredMethod("copyWorkingRecordingFiles",
                java.io.File.class, java.io.File.class, boolean.class);
        copy.setAccessible(true);
        for (boolean include : List.of(false, true)) {
            var failure = org.junit.jupiter.api.Assertions.assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> copy.invoke(null, transcript.toFile(), transcript.toFile(), include));
            assertTrue(failure.getCause() instanceof java.io.IOException);
            assertEquals("original audio", Files.readString(audio));
            assertEquals("machine text", Files.readString(transcript));
        }
        var managed = TimeStamp.class.getDeclaredMethod("copyOrRemoveManagedFile",
                java.io.File.class, java.io.File.class, boolean.class);
        managed.setAccessible(true);
        org.junit.jupiter.api.Assertions.assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> managed.invoke(null, audio.toFile(), audio.toFile(), false));
        assertEquals("original audio", Files.readString(audio));
    }

    @Test
    void savedSessionChecksumsDetectCorruptionAndRejectOutsidePaths(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("transcript.txt"), "negative");
        String manifest = "{\"schemaVersion\":2,\"transcript\":{\"path\":\"transcript.txt\",\"exists\":true,\"bytes\":8}}";
        String checked = SessionIntegrity.addChecksums(manifest, directory);
        SessionIntegrity.verify(checked, directory);
        Files.writeString(directory.resolve("transcript.txt"), "positive");
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> SessionIntegrity.verify(checked, directory));
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> SessionIntegrity.addChecksums(manifest.replace("transcript.txt", "../outside.txt"), directory));
    }

    @Test
    void reviewRecoveryRequiresExactSourceAndPreservesCheckedWords() {
        var checked = List.of(new TimeStamp.ReviewWord("negative", 0, 8, 0, 500, .35, false));
        String review = TimeStamp.reviewedTranscriptJson("negative", checked);
        String checkpoint = ReviewRecovery.encode("machine source", review);
        assertEquals(review, ReviewRecovery.matchingReview(checkpoint, "machine source"));
        assertEquals(checked, TimeStamp.parseReviewWords(
                ReviewRecovery.matchingReview(checkpoint, "machine source"), "negative"));
        assertNull(ReviewRecovery.matchingReview(checkpoint, "new recording"));
        assertNull(ReviewRecovery.matchingReview("broken", "machine source"));
    }

    @Test
    void compactTimestampsPreserveTheRecordingClock() {
        var local = LocalDateTime.parse("2026-09-09T10:00:00");
        var origin = local.atZone(java.time.ZoneId.systemDefault()).toInstant();
        assertEquals("00:03", TimeStamp.compactCaptionTimestamp("[2026-09-09T10:00:03.250]", origin));
        assertEquals("10:00:03", TimeStamp.compactCaptionTimestamp("[2026-09-09T10:00:03.250]", null));
        assertEquals("[unclear speech - review]", TimeStamp.compactCaptionTimestamp("[unclear speech - review]", origin));
    }

    @Test
    void captionTokensKeepRawUtf16OffsetsAfterShorteningTimestamps() {
        String raw = "[2026-09-09T10:00:03.250] 🙂 negative.\n";
        var tokens = TimeStamp.captionTokens(raw, 7, null);
        assertEquals("10:00:03", tokens.getFirst().text());
        assertTrue(tokens.getFirst().timestamp());
        for (var token : tokens) {
            if (!token.timestamp()) assertEquals(token.text(), raw.substring(token.start() - 7, token.end() - 7));
        }
        assertEquals(raw.length() + 7, tokens.getLast().end());
    }

    @Test
    void reviewedSnapshotRetainsCheckedDecisionsAndNullableConfidence() {
        var words = List.of(new TimeStamp.ReviewWord("no", 0, 2, 0, 500, .35, false),
                new TimeStamp.ReviewWord("invasion", 3, 11, 500, 1000, null, true));
        String json = TimeStamp.reviewedTranscriptJson("no invasion", words);
        assertEquals(words, TimeStamp.parseReviewWords(json, "no invasion"));
        assertTrue(TimeStamp.parseReviewWords(json, "edited").isEmpty());
    }

    @Test
    void reviewMetadataRequiresExactTextAndValidOffsets() {
        String json = """
                {"version":1,"transcript":"no invasion","words":[
                  {"word":"no","start":0,"end":2,"start_ms":0,"end_ms":500,
                   "confidence":0.35,"needs_review":true},
                  {"word":"invasion","start":3,"end":11,"start_ms":500,"end_ms":1000,
                   "confidence":null,"needs_review":false}]}
                """;
        var words = TimeStamp.parseReviewWords(json, "no invasion");
        assertEquals(2, words.size());
        assertTrue(words.getFirst().needsReview());
        assertNull(words.getLast().confidence());
        assertTrue(TimeStamp.parseReviewWords(json, "invasion").isEmpty());
        assertTrue(TimeStamp.parseReviewWords(json.replace("\"end\":11", "\"end\":100"), "no invasion").isEmpty());
        assertTrue(TimeStamp.parseReviewWords(json.replace("0.35", "1.5"), "no invasion").isEmpty());
        assertTrue(TimeStamp.parseReviewWords("broken", "no invasion").isEmpty());
    }

    @Test
    void correctingOneWordKeepsOtherWordConfidenceAndAudioTiming() {
        var words = List.of(
                new TimeStamp.ReviewWord("no", 0, 2, 0, 500, 0.3, true),
                new TimeStamp.ReviewWord("invasion", 3, 11, 500, 1000, 0.4, true));
        var rebased = TimeStamp.rebaseReviewWords(words, "no invasion", "negative invasion");
        assertEquals(1, rebased.size());
        assertEquals("invasion", rebased.getFirst().word());
        assertEquals(9, rebased.getFirst().start());
        assertEquals(17, rebased.getFirst().end());
        assertEquals(500, rebased.getFirst().startMs());
        assertEquals(0.4, rebased.getFirst().confidence());
        assertTrue(TimeStamp.rebaseReviewWords(words, "no invasion", "").isEmpty());
    }

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
                "AUDIO_CHECK_RESULT\t14.0\tgood",
                "AUDIO_LEVEL\t4.0\tlow",
                "AUDIO_CLIPPING\t0.125",
                "AUDIO_SILENT\t30.0",
                "AUDIO_RECOVERED",
                "TRANSCRIPT_READY",
                "CAPTURE_STATE\tpaused",
                "RECORDING_ORIGIN\t2026-08-25T20:00:00.000Z",
                "LIVE_MODEL_READY\tsmall.en",
                "TRANSCRIPT_UPDATED",
                "TRANSCRIPT_PARTIAL\tprovisional words",
                "TURN_ENDED\t2026-08-25T20:00:04.000Z",
                "FINALIZE_PROGRESS\t12.0\t60.0",
                "FINALIZATION_RESULT\tfinal");
        var covered = EnumSet.noneOf(TimeStamp.TranscriptMessageType.class);
        for (String message : messages) {
            TimeStamp.TranscriptMessage parsed = TimeStamp.parseTranscriptMessage(message);
            assertFalse(parsed.type() == TimeStamp.TranscriptMessageType.MALFORMED, message);
            assertFalse(parsed.type() == TimeStamp.TranscriptMessageType.LOG, message);
            covered.add(parsed.type());
        }
        // LOG and MALFORMED are local sentinels, never sent on the wire.
        var wireMessages = EnumSet.allOf(TimeStamp.TranscriptMessageType.class);
        wireMessages.remove(TimeStamp.TranscriptMessageType.LOG);
        wireMessages.remove(TimeStamp.TranscriptMessageType.MALFORMED);
        assertEquals(wireMessages, covered,
                "every protocol message needs an example in this test");
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
    void formatsSignalQualityAtClinicalThresholds() {
        assertEquals("Signal: calibrating — pause, then speak",
                TimeStamp.signalQualityLabel(-1, "calibrating"));
        assertEquals("Signal 14 dB — good", TimeStamp.signalQualityLabel(14, "good"));
        assertEquals("Signal 4 dB — below 8 dB recommended",
                TimeStamp.signalQualityLabel(4, "low"));
        assertEquals("Signal 2 dB — too noisy", TimeStamp.signalQualityLabel(2, "critical"));
        assertEquals(-1.0, TimeStamp.signalQualityProgress(-1, "calibrating"));
        assertEquals(0.5, TimeStamp.signalQualityProgress(15, "good"));
    }

    @Test
    void suppressesStructuralLoopsWithoutDestroyingLongSpeech() {
        String longSpeech = java.util.stream.IntStream.range(0, 137)
                .mapToObj(index -> "clinical" + index)
                .collect(java.util.stream.Collectors.joining(" "));
        String repeatedLoop = String.join(
                " ",
                java.util.Collections.nCopies(30, "and system repeats"));
        String contents = "[2026-08-26T12:00:00.000] " + longSpeech + "\n" +
                "[2026-08-26T12:00:01.000] " + repeatedLoop + "\n";

        String rendered = TimeStamp.suppressRunawayTranscriptLines(contents);

        assertTrue(rendered.contains(longSpeech));
        assertTrue(rendered.contains(
                "[2026-08-26T12:00:01.000] [decode error suppressed]"));
        assertFalse(rendered.contains(repeatedLoop));
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
    void captionUpdatesAppendOnlyUntilFinalTextReplacesThePreview() {
        assertEquals(" second", TimeStamp.appendOnlyCaptionSuffix("first", "first second"));
        assertEquals("", TimeStamp.appendOnlyCaptionSuffix("same", "same"));
        assertNull(TimeStamp.appendOnlyCaptionSuffix("live wording", "final wording"));
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
        assertEquals(List.of("--capture-only", "--interactive-control"),
                TimeStamp.transcriptLifecycleArguments(false));
        assertEquals(List.of("--finalize-existing"),
                TimeStamp.transcriptLifecycleArguments(true));
    }

    @Test
    void mapsPauseResumeDoneWorkflowActionsWithoutEndingTheTake() {
        assertEquals(TimeStamp.TranscriptMessageType.CAPTURE_STATE,
                TimeStamp.parseTranscriptMessage("CAPTURE_STATE\tpaused").type());
        assertEquals(TimeStamp.TranscriptMessageType.CAPTURE_STATE,
                TimeStamp.parseTranscriptMessage("CAPTURE_STATE\trecording").type());
        assertEquals(TimeStamp.TranscriptMessageType.MALFORMED,
                TimeStamp.parseTranscriptMessage("CAPTURE_STATE\tunknown").type());
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
        Files.writeString(source.resolveSibling("working_transcript_review.json"), "review metadata");
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
        assertEquals("review metadata",
                Files.readString(destination.resolveSibling("saved_transcript_review.json")));

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
