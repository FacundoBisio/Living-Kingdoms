package dev.livingkingdoms.onboarding.domain;

/** Personal progress; a shared kingdom is registered independently of its collaborators. */
public enum OnboardingStage {
    WELCOME, PEOPLE, NEEDS, RESPONSIBILITIES, NAMING, JOINED;

    public OnboardingStage next(String action) {
        return switch (this) {
            case WELCOME -> action.equals("NEXT") ? PEOPLE : this;
            case PEOPLE -> action.equals("NEXT") ? NEEDS : this;
            case NEEDS -> action.equals("HELP") ? RESPONSIBILITIES : this;
            case RESPONSIBILITIES -> action.equals("ACCEPT") ? NAMING : this;
            case NAMING, JOINED -> this;
        };
    }
}
