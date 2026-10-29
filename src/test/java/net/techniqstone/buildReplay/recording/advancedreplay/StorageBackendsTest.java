package net.techniqstone.buildReplay.recording.advancedreplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.logging.Logger;
import me.jumper251.replay.filesystem.saving.DefaultReplaySaver;
import me.jumper251.replay.filesystem.saving.IReplaySaver;
import me.jumper251.replay.replaysystem.Replay;
import me.jumper251.replay.utils.fetcher.Consumer;
import org.junit.jupiter.api.Test;

class StorageBackendsTest {

    /** Wird nie instanziiert - DefaultReplaySavers statischer Initialisierer bräuchte ein laufendes AR. */
    private abstract static class ExtendedFileSaver extends DefaultReplaySaver {
    }

    private static final class OwnSaver implements IReplaySaver {
        @Override
        public void saveReplay(Replay replay) {
        }

        @Override
        public void loadReplay(String replayName, Consumer<Replay> consumer) {
            consumer.accept(null);
        }

        @Override
        public boolean replayExists(String replayName) {
            return false;
        }

        @Override
        public void deleteReplay(String replayName) {
        }

        @Override
        public List<String> getReplays() {
            return List.of();
        }
    }

    @Test
    void advancedReplaySaverClassNamesMapToTheirBackends() {
        assertEquals("FILE", StorageBackends.ofClassName("me.jumper251.replay.filesystem.saving.DefaultReplaySaver"));
        assertEquals("MYSQL", StorageBackends.ofClassName("me.jumper251.replay.filesystem.saving.DatabaseReplaySaver"));
        assertEquals("S3", StorageBackends.ofClassName("me.jumper251.replay.filesystem.saving.S3ReplaySaver"));
        assertEquals("CUSTOM", StorageBackends.ofClassName("org.example.CloudSaver"));
        assertEquals("CUSTOM", StorageBackends.ofClassName(null));
    }

    @Test
    void theFileSaverClassAndItsSubclassesAreFileStorage() {
        assertEquals("FILE", StorageBackends.ofClass(DefaultReplaySaver.class));
        assertEquals("FILE", StorageBackends.ofClass(ExtendedFileSaver.class));
    }

    @Test
    void unknownSaversAndOurOwnDecoratorAroundThemAreCustom() {
        OwnSaver own = new OwnSaver();
        SaverInterceptor interceptor = new SaverInterceptor(own, name -> null, snapshot -> {
        }, Logger.getAnonymousLogger());

        assertEquals("CUSTOM", StorageBackends.of(own));
        assertEquals("CUSTOM", StorageBackends.of(interceptor));
        assertEquals("CUSTOM", StorageBackends.of(null));
    }

    @Test
    void storageReferencesDependOnTheBackend() {
        assertEquals("plugins/AdvancedReplay/replays/br-abc.replay", StorageBackends.storageRef("FILE", "br-abc"));
        assertEquals("mysql:replays/br-abc", StorageBackends.storageRef("MYSQL", "br-abc"));
        assertEquals("s3:br-abc.replay", StorageBackends.storageRef("S3", "br-abc"));
        assertNull(StorageBackends.storageRef("CUSTOM", "br-abc"));
    }
}
