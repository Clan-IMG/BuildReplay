package net.techniqstone.buildReplay.state.redis;

import java.time.Clock;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.state.SharedState;

/** Erzeugt den {@link SharedState} passend zur Konfiguration. */
public final class SharedStates {

    private SharedStates() {
    }

    /**
     * {@code redis.enabled=false}: rein lokaler Zustand ({@link LocalSharedState}). Sonst ein bereits gestarteter
     * {@link FailoverSharedState} über Redis mit lokalem Ausweichzustand. Blockiert nicht (keine Verbindung im
     * Aufrufer-Thread) und wirft nicht; der Aufrufer muss {@link SharedState#close()} beim Abschalten aufrufen.
     *
     * @param instanceId eindeutige ID dieser BuildReplay-Instanz (Client-Name in Redis)
     */
    public static SharedState create(PluginConfig.RedisSettings settings, String instanceId, Clock clock,
                                     Logger logger) {
        if (!settings.enabled()) {
            logger.info("Redis ist deaktiviert - Sperren, Leader und Ereignisse gelten nur für diese Instanz");
            return new LocalSharedState(clock, logger);
        }
        RedisSharedState redis;
        try {
            redis = new RedisSharedState(settings, instanceId, clock, logger);
        } catch (RuntimeException e) {
            String reason = RedisSharedState.describe(e);
            logger.log(Level.SEVERE, "Redis-Anbindung konnte nicht eingerichtet werden (" + reason
                    + ") - verwende ausschließlich lokalen Zustand", e);
            return new LocalSharedState(clock, logger, true, reason);
        }
        FailoverSharedState state = new FailoverSharedState(redis, new LocalSharedState(clock, logger), clock,
                logger, FailoverSharedState.DEFAULT_HEALTH_INTERVAL);
        state.start();
        return state;
    }
}
