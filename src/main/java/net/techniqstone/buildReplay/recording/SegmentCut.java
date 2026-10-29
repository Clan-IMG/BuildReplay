package net.techniqstone.buildReplay.recording;

/** Auszuschneidender Bereich einer Rohaufnahme und Name des neuen AdvancedReplay-Replays. */
public record SegmentCut(String replayName, int fromTick, int toTick) {

    public SegmentCut {
        if (fromTick < 0 || toTick <= fromTick) {
            throw new IllegalArgumentException("Ungültiger Schnitt " + fromTick + ".." + toTick);
        }
    }
}
