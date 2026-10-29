package net.techniqstone.buildReplay.activity;

import java.time.Instant;

/** Aktivität auf der Zeitachse einer Pufferaufnahme: AdvancedReplay-Tick und Wanduhrzeit. */
public record TimelineEvent(int tick, Instant at, ActivityType type, boolean relevant) {
}
