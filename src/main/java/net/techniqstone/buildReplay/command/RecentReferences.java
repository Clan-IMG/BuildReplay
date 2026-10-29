package net.techniqstone.buildReplay.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Merkt sich pro Befehlsabsender die zuletzt angezeigten Sitzungsreferenzen und Abschnitt-IDs, damit die
 * Tab-Vervollständigung sinnvolle Vorschläge machen kann. Begrenzt (LRU), threadsicher.
 */
public final class RecentReferences {

    private static final int MAX_PER_SENDER = 50;

    private final Map<String, Entry> entries;

    public RecentReferences(int maxSenders) {
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > maxSenders;
            }
        };
    }

    public synchronized void rememberRefs(String sender, List<String> refs) {
        Entry entry = entries.computeIfAbsent(sender, key -> new Entry());
        addAll(entry.refs, refs);
    }

    public synchronized void rememberSegments(String sender, List<Long> segmentIds) {
        Entry entry = entries.computeIfAbsent(sender, key -> new Entry());
        addAll(entry.segmentIds, segmentIds.stream().map(String::valueOf).toList());
    }

    public synchronized List<String> refs(String sender) {
        Entry entry = entries.get(sender);
        return entry == null ? List.of() : List.copyOf(entry.refs);
    }

    public synchronized List<String> segmentIds(String sender) {
        Entry entry = entries.get(sender);
        return entry == null ? List.of() : List.copyOf(entry.segmentIds);
    }

    private static void addAll(LinkedHashSet<String> target, List<String> values) {
        for (String value : values) {
            target.remove(value);
            target.add(value);
        }
        if (target.size() > MAX_PER_SENDER) {
            List<String> keep = new ArrayList<>(target).subList(target.size() - MAX_PER_SENDER, target.size());
            target.clear();
            target.addAll(keep);
        }
    }

    private static final class Entry {
        private final LinkedHashSet<String> refs = new LinkedHashSet<>();
        private final LinkedHashSet<String> segmentIds = new LinkedHashSet<>();
    }
}
