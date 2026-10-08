package dev.livingkingdoms.citizen.domain;

public record HousingSummary(int total, int occupied, int free) {
    public HousingSummary {
        if (total < 0 || occupied < 0 || occupied > total || free != total - occupied)
            throw new IllegalArgumentException("Invalid housing summary");
    }
}
