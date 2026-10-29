package net.techniqstone.buildReplay.storage.sql;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.techniqstone.buildReplay.config.PluginConfig;

/** Legt Stores für einen Test an und schließt sie am Ende garantiert wieder. */
final class StoreHarness implements AutoCloseable {

    final TestSupport.CapturingLog log = new TestSupport.CapturingLog();
    final TestSupport.MutableClock clock = new TestSupport.MutableClock(TestSupport.T0);
    private final List<SqlReplayStore> stores = new ArrayList<>();

    /** Nicht gestarteter Store. */
    SqlReplayStore create(PluginConfig.DatabaseSettings settings, Path dataFolder, int outboxMax) {
        SqlReplayStore store = new SqlReplayStore(settings, TestSupport.resilience(outboxMax), dataFolder, clock,
                log.logger(), TestSupport.FAST);
        synchronized (stores) {
            stores.add(store);
        }
        return store;
    }

    SqlReplayStore start(PluginConfig.DatabaseSettings settings, Path dataFolder) {
        SqlReplayStore store = create(settings, dataFolder, 10_000);
        store.start();
        return store;
    }

    @Override
    public void close() {
        List<SqlReplayStore> copy;
        synchronized (stores) {
            copy = new ArrayList<>(stores);
            stores.clear();
        }
        for (SqlReplayStore store : copy) {
            store.close();
        }
    }
}
