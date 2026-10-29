package net.techniqstone.buildReplay.command;

import java.util.UUID;

/** Ein über seinen Namen aufgelöster Spieler. */
public record KnownPlayer(UUID uuid, String name) {
}
