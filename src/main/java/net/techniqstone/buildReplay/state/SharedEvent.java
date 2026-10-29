package net.techniqstone.buildReplay.state;

import java.util.Map;

/**
 * Netzwerkweite Benachrichtigung zwischen BuildReplay-Instanzen (Redis Pub/Sub, ohne Zustellgarantie).
 * Typen: {@link #SEGMENT_SAVED}, {@link #SESSION_CLOSED}, {@link #SEGMENT_DELETED}.
 */
public record SharedEvent(String type, String originInstance, Map<String, String> data) {

    public static final String SEGMENT_SAVED = "segment-saved";
    public static final String SEGMENT_DELETED = "segment-deleted";
    public static final String SESSION_CLOSED = "session-closed";

    public SharedEvent {
        data = Map.copyOf(data);
    }
}
