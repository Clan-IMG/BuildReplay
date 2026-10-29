package net.techniqstone.buildReplay.simplecloud.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.techniqstone.buildReplay.config.EnvResolver;
import net.techniqstone.buildReplay.config.PluginConfig;
import net.techniqstone.buildReplay.simplecloud.SimpleCloudPlatform;
import net.techniqstone.buildReplay.simplecloud.SimpleCloudPlatform.ConnectOutcome;
import org.bukkit.Server;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Die SimpleCloud-API liegt nicht auf dem Test-Classpath; geprüft wird daher die Auswahl von
 * {@link SimpleCloudPlatform#NONE}. {@link CloudApiPlatform} ist per javap gegen das ausgelieferte Jar geprüft.
 */
class SimpleCloudPlatformsTest {

    private final List<LogRecord> records = new ArrayList<>();
    private Logger logger;

    @BeforeEach
    void captureLog() {
        logger = Logger.getLogger("BuildReplayPlatformTest-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    @Test
    void usesNoPlatformWhenTheJavaApiIsDisabledInTheConfig() {
        AtomicInteger lookups = new AtomicInteger();
        MemoryConfiguration yaml = new MemoryConfiguration();
        yaml.set("simplecloud.use-java-api", false);
        PluginConfig config = PluginConfig.from(yaml, new EnvResolver(name -> null), new ArrayList<>());

        SimpleCloudPlatform platform = SimpleCloudPlatforms.create(fakePlugin(true, lookups), config, logger);

        assertSame(SimpleCloudPlatform.NONE, platform);
        assertEquals(0, lookups.get(), "Plugin-Manager wird gar nicht gefragt");
    }

    @Test
    void usesNoPlatformWhenTheSimpleCloudApiPluginIsNotEnabled() {
        AtomicInteger lookups = new AtomicInteger();

        SimpleCloudPlatform platform = SimpleCloudPlatforms.create(fakePlugin(false, lookups), PluginConfig.defaults(),
                logger);

        assertSame(SimpleCloudPlatform.NONE, platform);
        assertEquals(1, lookups.get());
    }

    @Test
    void usesNoPlatformAndWarnsWhenTheApiClassesAreMissing() {
        SimpleCloudPlatform platform = SimpleCloudPlatforms.create(fakePlugin(true, new AtomicInteger()),
                PluginConfig.defaults(), logger);

        assertSame(SimpleCloudPlatform.NONE, platform);
        assertTrue(records.stream().anyMatch(r -> r.getLevel().equals(Level.WARNING)));
    }

    @Test
    void theNoOpPlatformReportsUnsupportedConnects() throws Exception {
        SimpleCloudPlatform none = SimpleCloudPlatform.NONE;

        assertFalse(none.isAvailable());
        assertEquals(ConnectOutcome.UNSUPPORTED, none.connect(UUID.randomUUID(), "dev").get(1, TimeUnit.SECONDS));
    }

    /** Minimaler Plugin-Ersatz über dynamische Proxys: nur {@code getServer().getPluginManager().isPluginEnabled}. */
    private static Plugin fakePlugin(boolean apiEnabled, AtomicInteger lookups) {
        PluginManager manager = proxy(PluginManager.class, (method, args) -> {
            if (method.equals("isPluginEnabled") && args != null && args.length == 1 && args[0] instanceof String name) {
                lookups.incrementAndGet();
                return apiEnabled && name.equals(SimpleCloudPlatforms.API_PLUGIN);
            }
            throw new UnsupportedOperationException(method);
        });
        Server server = proxy(Server.class, (method, args) -> {
            if (method.equals("getPluginManager")) {
                return manager;
            }
            throw new UnsupportedOperationException(method);
        });
        return proxy(Plugin.class, (method, args) -> {
            if (method.equals("getServer")) {
                return server;
            }
            if (method.equals("getName")) {
                return "BuildReplay";
            }
            throw new UnsupportedOperationException(method);
        });
    }

    private interface Answer {
        Object answer(String method, Object[] args);
    }

    private static <T> T proxy(Class<T> type, Answer answer) {
        Object instance = Proxy.newProxyInstance(SimpleCloudPlatformsTest.class.getClassLoader(), new Class<?>[]{type},
                (self, method, args) -> switch (method.getName()) {
                    case "toString" -> "Fake" + type.getSimpleName();
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    default -> answer.answer(method.getName(), args);
                });
        return type.cast(instance);
    }
}
