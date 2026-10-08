package dev.livingkingdoms.citizen;

import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** Names are generated once and stored with identity; no client-side rerolling. */
public final class CitizenNames {
    private static final String[] FIRST = {"Alden", "Alina", "Ansel", "Arden", "Astrid", "Bastian", "Bram", "Brynn",
            "Celia", "Corin", "Dalia", "Dorian", "Edric", "Elara", "Elin", "Emrys", "Finn", "Freya", "Galen",
            "Greta", "Hale", "Hazel", "Ida", "Ilan", "Iris", "Jonas", "Kaia", "Kellan", "Lena", "Liora",
            "Maren", "Mira", "Nolan", "Orin", "Petra", "Rowan", "Soren", "Talia", "Theo", "Wren"};
    private static final String[] LAST = {"Ashford", "Briar", "Brook", "Cedar", "Clay", "Dale", "Fairbrook",
            "Fern", "Fielding", "Flint", "Greenbough", "Hill", "Holloway", "Oakley", "Reed", "Riverstone",
            "Thorn", "Vale", "Westwood", "Willow"};

    private CitizenNames() {}

    public static String generate(RandomGenerator random) {
        Objects.requireNonNull(random);
        return FIRST[random.nextInt(FIRST.length)] + " " + LAST[random.nextInt(LAST.length)];
    }

    /** Existing entity receipts yield stable migration names even before its chunk loads. */
    public static String forIdentity(UUID entityId) {
        Objects.requireNonNull(entityId);
        return generate(new Random(entityId.getMostSignificantBits() ^ entityId.getLeastSignificantBits()));
    }
}
