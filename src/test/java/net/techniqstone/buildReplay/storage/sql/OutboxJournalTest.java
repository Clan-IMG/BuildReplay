package net.techniqstone.buildReplay.storage.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.techniqstone.buildReplay.storage.model.RecordingStatus;
import net.techniqstone.buildReplay.storage.model.RecordingUpsert;
import net.techniqstone.buildReplay.storage.model.SegmentStatus;
import net.techniqstone.buildReplay.storage.model.SegmentUpsert;
import net.techniqstone.buildReplay.storage.model.SessionSource;
import net.techniqstone.buildReplay.storage.model.SessionUpsert;
import net.techniqstone.buildReplay.storage.model.VisitEndReason;
import net.techniqstone.buildReplay.storage.model.VisitStart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutboxJournalTest {

    private static final UUID PLAYER = UUID.fromString("8e167ad3-ad36-45a8-ba56-6a590f79164b");
    private static final Instant T = Instant.parse("2026-10-20T12:00:00.123Z");

    @TempDir
    Path dir;

    static List<WriteOp> everyKindOfWrite() {
        SessionUpsert session = new SessionUpsert("59dc83e5-987b-4adc-85d7-2aaeaf7ddb53",
                "59dc83e5-987b-4adc-85d7-2aaeaf7ddb53", PLAYER, SessionSource.SIMPLECLOUD, T, null, null);
        return List.of(
                new WriteOp.UpsertPlayer(PLAYER, "WrobelXXL", T),
                new WriteOp.UpsertSession(session),
                new WriteOp.CloseSession("prov-1", T.plusSeconds(5), 5L),
                new WriteOp.CloseSession("prov-2", T.plusSeconds(5), null),
                new WriteOp.MergeProvisional("prov-1", session),
                new WriteOp.StartVisit(new VisitStart("lobby-1:x:1", "prov-1", PLAYER, "WrobelXXL", "lobby", "lobby-1", T)),
                new WriteOp.EndVisit("lobby-1:x:1", T.plusSeconds(9), VisitEndReason.QUIT),
                new WriteOp.UpsertRecording(new RecordingUpsert("brraw-abc", null, PLAYER, "WrobelXXL", "build",
                        "build-1", "world \"ä\" <x>", T, RecordingStatus.RECORDING)),
                new WriteOp.FinishRecording("brraw-abc", RecordingStatus.FINALIZED, null, 7, "Fehler:\nzweite Zeile"),
                new WriteOp.UpsertSegment(new SegmentUpsert("br-abc", "prov-1", "brraw-abc", PLAYER, "WrobelXXL", "build",
                        null, "advancedreplay", null, T, T.plusSeconds(60), T.plusSeconds(1), T.plusSeconds(59), 1200,
                        10_000, 10_000, 12, Map.of("BLOCK_PLACE", 10, "WORLDEDIT", 2), SegmentStatus.SAVING, 4096L)),
                new WriteOp.UpdateSegmentStatus("br-abc", SegmentStatus.AVAILABLE, null));
    }

    @Test
    void everyKindOfWriteSurvivesARoundTrip() throws IOException {
        WriteOpCodec codec = new WriteOpCodec();
        for (WriteOp op : everyKindOfWrite()) {
            assertEquals(op, codec.decode(codec.encode(op)), op.type());
        }
        OutboxJournal journal = new OutboxJournal(dir, codec, new TestSupport.CapturingLog().logger());
        assertTrue(journal.write(everyKindOfWrite(), 1));
        assertEquals(everyKindOfWrite().size(), journal.persistedEntries());
        OutboxJournal reopened = new OutboxJournal(dir, codec, new TestSupport.CapturingLog().logger());
        OutboxJournal.Loaded loaded = reopened.load();
        assertEquals(everyKindOfWrite(), loaded.ops());
        assertEquals(0, loaded.skippedLines());
    }

    @Test
    void everyWriteTypeHasADistinctStableTag() {
        List<String> tags = everyKindOfWrite().stream().map(WriteOp::type).distinct().toList();
        assertEquals(10, tags.size());
    }

    @Test
    void unreadableLinesAreSkippedAndCounted() throws IOException {
        WriteOpCodec codec = new WriteOpCodec();
        WriteOp good = new WriteOp.UpsertPlayer(PLAYER, "A", T);
        Files.writeString(dir.resolve("outbox.jsonl"), String.join("\n",
                codec.encode(good),
                "{kaputt",
                "{\"v\":1,\"type\":\"unbekannt\",\"op\":{}}",
                "{\"v\":1,\"type\":\"player\",\"op\":{\"name\":\"ohne uuid\"}}",
                "",
                codec.encode(good)), StandardCharsets.UTF_8);
        TestSupport.CapturingLog log = new TestSupport.CapturingLog();
        OutboxJournal.Loaded loaded = new OutboxJournal(dir, codec, log.logger()).load();
        assertEquals(List.of(good, good), loaded.ops());
        assertEquals(3, loaded.skippedLines());
        assertTrue(log.all().contains("unlesbar"), log.all());
    }

    @Test
    void decodingRejectsUnknownTypes() {
        assertThrows(JsonParseException.class, () -> new WriteOpCodec().decode("{\"type\":\"drop-table\",\"op\":{}}"));
        assertThrows(JsonParseException.class, () -> new WriteOpCodec().decode("[1,2]"));
    }

    @Test
    void anOlderSnapshotNeverOverwritesANewerOne() throws IOException {
        WriteOpCodec codec = new WriteOpCodec();
        OutboxJournal journal = new OutboxJournal(dir, codec, new TestSupport.CapturingLog().logger());
        List<WriteOp> newer = everyKindOfWrite().subList(0, 3);
        assertTrue(journal.write(newer, 10));
        assertTrue(journal.write(everyKindOfWrite(), 5), "älterer Stand ist durch den neueren abgedeckt");
        assertEquals(newer, new OutboxJournal(dir, codec, new TestSupport.CapturingLog().logger()).load().ops());
    }

    @Test
    void anEmptySnapshotDeletesTheFile() throws IOException {
        OutboxJournal journal = new OutboxJournal(dir, new WriteOpCodec(), new TestSupport.CapturingLog().logger());
        journal.write(everyKindOfWrite(), 1);
        assertTrue(Files.exists(journal.file()));
        journal.delete(2);
        assertFalse(Files.exists(journal.file()));
        assertEquals(0, journal.persistedEntries());
        assertFalse(journal.exists());
    }

    @Test
    void aFrozenJournalIgnoresLaterChanges() throws IOException {
        OutboxJournal journal = new OutboxJournal(dir, new WriteOpCodec(), new TestSupport.CapturingLog().logger());
        journal.write(everyKindOfWrite(), 1);
        journal.freeze();
        assertFalse(journal.write(List.of(), 2));
        assertTrue(Files.exists(journal.file()));
        assertEquals(everyKindOfWrite().size(), journal.persistedEntries());
    }

    @Test
    void noFileMeansNothingToReplay() throws IOException {
        OutboxJournal.Loaded loaded = new OutboxJournal(dir.resolve("fehlt"), new WriteOpCodec(),
                new TestSupport.CapturingLog().logger()).load();
        assertTrue(loaded.ops().isEmpty());
    }
}
