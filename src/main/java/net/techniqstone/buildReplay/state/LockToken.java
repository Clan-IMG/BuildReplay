package net.techniqstone.buildReplay.state;

/** Besitznachweis einer verteilten Sperre; Freigabe nur mit passendem Token (Compare-and-Delete). */
public record LockToken(String name, String token, boolean distributed) {
}
