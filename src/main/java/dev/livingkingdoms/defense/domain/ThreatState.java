package dev.livingkingdoms.defense.domain;

/** Settlement threat state stays separate from settlement founding/established lifecycle. */
public enum ThreatState {
    DETECTED, ACTIVE, RESOLVED, FAILED;

    public boolean threatened() { return this == DETECTED || this == ACTIVE; }
}
