package net.techniqstone.buildReplay.session;

import java.util.List;
import net.techniqstone.buildReplay.storage.model.SegmentRow;
import net.techniqstone.buildReplay.storage.model.VisitRow;

/** Inhalt von {@code /buildreplay session <ref>}. */
public record SessionDetails(
        SessionListEntry summary,
        List<SegmentRow> segments,
        List<VisitRow> visits,
        List<String> warnings) {

    public SessionDetails {
        segments = List.copyOf(segments);
        visits = List.copyOf(visits);
        warnings = List.copyOf(warnings);
    }
}
