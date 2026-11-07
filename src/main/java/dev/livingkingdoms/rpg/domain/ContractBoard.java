package dev.livingkingdoms.rpg.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * A player's daily and weekly contracts plus a forgiving streak. Days are calendar days supplied by the
 * caller (epoch days), so the board resets at local midnight even in single player. Missing days lowers
 * the streak by one per day missed instead of resetting it; the reward multiplier caps at x2.
 */
public record ContractBoard(long day, List<Contract> daily, long week, Contract weekly, int streak,
                            long lastFullDay, boolean dailyBonusClaimed) {
    public static final int MAX_STREAK = 365;
    public static final int MAX_DAILY = 4;

    public ContractBoard {
        Objects.requireNonNull(daily, "daily");
        daily = List.copyOf(daily);
        if (daily.size() > MAX_DAILY || streak < 0 || streak > MAX_STREAK) throw new IllegalArgumentException("Invalid contract board");
    }

    public static ContractBoard empty() { return new ContractBoard(Long.MIN_VALUE, List.of(), Long.MIN_VALUE, null, 0, Long.MIN_VALUE, false); }

    /** x1.0 … x2.0 in 10% steps. */
    public static double multiplier(int streak) { return 1.0 + 0.1 * Math.min(10, Math.max(0, streak)); }
    public double multiplier() { return multiplier(streak); }

    /** Streak as it stands today: decays by one per fully missed day since the last completed day. */
    public static int decayedStreak(int streak, long lastFullDay, long today) {
        if (lastFullDay == Long.MIN_VALUE || today <= lastFullDay + 1) return streak;
        long missed = today - lastFullDay - 1;
        return (int) Math.max(0, streak - missed);
    }

    /** Regenerates daily/weekly sets when their period changed. Deterministic per player seed and period. */
    public ContractBoard refresh(long today, long thisWeek, long seed, int dailyCount, boolean weeklyUnlocked, boolean trialsUnlocked) {
        var next = this;
        if (today != day) {
            int current = decayedStreak(streak, lastFullDay, today);
            next = new ContractBoard(today, generateDaily(seed, today, Math.max(1, Math.min(MAX_DAILY, dailyCount))), week, weekly,
                    current, lastFullDay, false);
        }
        if (weeklyUnlocked && (thisWeek != next.week || next.weekly == null))
            next = new ContractBoard(next.day, next.daily, thisWeek, generateWeekly(seed, thisWeek, trialsUnlocked), next.streak, next.lastFullDay, next.dailyBonusClaimed);
        return next;
    }

    public ContractBoard progress(Contract.Kind kind, int amount) {
        var list = new ArrayList<Contract>();
        for (var contract : daily) list.add(contract.kind() == kind ? contract.advance(amount) : contract);
        var w = weekly != null && weekly.kind() == kind ? weekly.advance(amount) : weekly;
        return new ContractBoard(day, list, week, w, streak, lastFullDay, dailyBonusClaimed);
    }

    public ContractBoard claimDaily(int index) {
        if (index < 0 || index >= daily.size()) throw new IllegalArgumentException("No such contract");
        var list = new ArrayList<>(daily);
        list.set(index, list.get(index).claim());
        return new ContractBoard(day, list, week, weekly, streak, lastFullDay, dailyBonusClaimed);
    }

    public ContractBoard claimWeekly() {
        if (weekly == null) throw new IllegalStateException("No weekly contract");
        return new ContractBoard(day, daily, week, weekly.claim(), streak, lastFullDay, dailyBonusClaimed);
    }

    public boolean allDailyClaimed() { return !daily.isEmpty() && daily.stream().allMatch(Contract::claimed); }

    /** Completing every daily once per day extends the streak and pays the bonus chest. */
    public ContractBoard claimDailyBonus() {
        if (!allDailyClaimed() || dailyBonusClaimed) throw new IllegalStateException("Daily bonus not available");
        // refresh() already applied the decay when this day began; decaying again would double-count missed days.
        int next = Math.min(MAX_STREAK, streak + 1);
        return new ContractBoard(day, daily, week, weekly, next, day, true);
    }

    static List<Contract> generateDaily(long seed, long day, int count) {
        var random = new Random(seed * 31 + day * 1_000_003L);
        var kinds = new ArrayList<>(List.of(Contract.Kind.SLAY, Contract.Kind.DELIVER, Contract.Kind.QUEST, Contract.Kind.SUPPLY));
        java.util.Collections.shuffle(kinds, random);
        var result = new ArrayList<Contract>();
        for (int i = 0; i < count; i++) {
            var kind = i < kinds.size() ? kinds.get(i) : Contract.Kind.DELIVER;
            result.add(switch (kind) {
                case SLAY -> Contract.of(kind, "", 8 + random.nextInt(9));
                case QUEST -> Contract.of(kind, "", 1 + random.nextInt(2));
                case SUPPLY -> Contract.of(kind, "", 1);
                default -> delivery(random, result);
            });
        }
        return result;
    }

    private static Contract delivery(Random random, List<Contract> existing) {
        var options = new ArrayList<>(List.of("logs", "stone", "wheat", "iron_ingot"));
        existing.forEach(c -> options.remove(c.target()));
        String target = options.get(random.nextInt(options.size()));
        int amount = switch (target) { case "iron_ingot" -> 6 + random.nextInt(7); case "wheat" -> 16 + random.nextInt(17); default -> 24 + random.nextInt(25); };
        return Contract.of(Contract.Kind.DELIVER, target, amount);
    }

    static Contract generateWeekly(long seed, long week, boolean trialsUnlocked) {
        var random = new Random(seed * 17 + week * 7_368_787L);
        return switch (random.nextInt(trialsUnlocked ? 3 : 2)) {
            case 0 -> Contract.of(Contract.Kind.SLAY, "", 60 + random.nextInt(41));
            case 1 -> Contract.of(Contract.Kind.QUEST, "", 5 + random.nextInt(4));
            default -> Contract.of(Contract.Kind.TRIAL, "", 2);
        };
    }
}
