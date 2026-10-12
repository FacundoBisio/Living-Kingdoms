package dev.livingkingdoms.onboarding.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;

/** Bounded plain names: formatting, invisible characters and network-controlled markup are rejected. */
public final class KingdomName {
    public static final int MAX_LENGTH = 32;
    private KingdomName() {}

    public static Optional<String> validate(String input) {
        if (input == null || input.length() > 48 || input.codePoints().anyMatch(Character::isISOControl)) return Optional.empty();
        String name = Normalizer.normalize(input, Normalizer.Form.NFKC).strip().replaceAll(" +", " ");
        if (name.isEmpty() || name.length() > MAX_LENGTH || !name.codePoints().anyMatch(Character::isLetterOrDigit))
            return Optional.empty();
        boolean safe = name.codePoints().allMatch(cp -> Character.isLetterOrDigit(cp) || cp == ' ' || cp == '-' || cp == '\'');
        return safe ? Optional.of(name) : Optional.empty();
    }

    public static String key(String name) { return name.toLowerCase(Locale.ROOT); }
}
