package net.techniqstone.buildReplay.activity;

import java.util.List;

/**
 * Reine Funktion: Zeitachse einer Pufferaufnahme → Abschnitte.
 *
 * <ol>
 *   <li>Relevante Aktionen werden zu Aktivitätsphasen gruppiert. Eine neue Phase beginnt, wenn seit der letzten
 *       relevanten Aktion mehr als {@code activity.relevant-idle-seconds} vergangen sind - außer der Spieler war
 *       dazwischen nachweislich aktiv (Indikatoren ohne Lücke größer {@code activity.afk-timeout-seconds}) und die
 *       Lücke ist höchstens {@code activity.max-indicator-extension-seconds} lang.</li>
 *   <li>Abschnitt = [erste Aktion − Vorlauf, letzte Aktion + Nachlauf], begrenzt auf [0, endTick].</li>
 *   <li>Abschnitte mit Abstand ≤ {@code recording.merge-gap-seconds} werden verbunden.</li>
 *   <li>Abschnitte mit weniger als {@code recording.min-relevant-actions} relevanten Aktionen oder kürzer als
 *       {@code recording.min-segment-seconds} entfallen.</li>
 *   <li>Abschnitte länger als {@code recording.max-segment-minutes} werden in aufeinanderfolgende Teile zerlegt.</li>
 * </ol>
 * Zeitangaben werden mit 20 Ticks pro Sekunde in Ticks umgerechnet.
 */
public interface SegmentPlanning {

    List<SegmentPlan> plan(List<TimelineEvent> events, int endTick);
}
