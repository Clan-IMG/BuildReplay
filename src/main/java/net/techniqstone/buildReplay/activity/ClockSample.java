package net.techniqstone.buildReplay.activity;

import java.time.Instant;

/** Zuordnung Aufnahme-Tick ↔ Uhrzeit (AdvancedReplay-Ticks folgen dem Server-Tick, nicht der Wanduhr). */
public record ClockSample(int tick, Instant at) {
}
