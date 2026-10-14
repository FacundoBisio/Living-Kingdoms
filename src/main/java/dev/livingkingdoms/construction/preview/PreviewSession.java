package dev.livingkingdoms.construction.preview;

import java.util.UUID;

/** A placement capability belongs to exactly one live player connection and expires without renewal. */
public record PreviewSession(UUID token, UUID player, String dimension, long expiresAt) {
    public static final int LIFETIME = 2400;
    public boolean accepts(UUID token, UUID player, String dimension, long now) {
        return this.token.equals(token) && this.player.equals(player) && this.dimension.equals(dimension)
                && now >= expiresAt - LIFETIME && now < expiresAt;
    }
}
