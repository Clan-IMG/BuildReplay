package net.techniqstone.buildReplay.recording.service.fakes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.techniqstone.buildReplay.simplecloud.SimpleCloudPlatform;

/** SimpleCloud-Java-API mit vorgegebenem Connect-Ergebnis. */
public final class FakePlatform implements SimpleCloudPlatform {

    public final List<String> connects = new ArrayList<>();
    public ConnectOutcome outcome = ConnectOutcome.SUCCESS;

    @Override
    public boolean isAvailable() {
        return outcome != ConnectOutcome.UNSUPPORTED;
    }

    @Override
    public CompletableFuture<ConnectOutcome> connect(UUID player, String serverName) {
        connects.add(player + "->" + serverName);
        return CompletableFuture.completedFuture(outcome);
    }

    @Override
    public void subscribe(CloudEventListener listener) {
        // keine Events
    }

    @Override
    public void close() {
        // nichts
    }
}
