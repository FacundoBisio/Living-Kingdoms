package dev.livingkingdoms.commerce;

import dev.livingkingdoms.economy.BuildingDevelopment;
import dev.livingkingdoms.item.KingdomItems;
import dev.livingkingdoms.onboarding.KingdomOnboardingService;
import dev.livingkingdoms.rpg.EventService;
import dev.livingkingdoms.rpg.KingdomTomes;
import dev.livingkingdoms.rpg.LootChests;
import dev.livingkingdoms.rpg.RpgClock;
import dev.livingkingdoms.rpg.domain.LootRarity;
import dev.livingkingdoms.settlement.persistence.SettlementSavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import java.util.*;

/**
 * Market stalls: every Merchant runs one stall with the classic villager trades of its specialty (more tiers as
 * the Merchant levels up) plus a few rare kingdom goods that are hard to get and rotate every day. Offers and
 * their used counts live in the Merchant's own entity data, so limits survive restarts without new SavedData.
 */
public final class StallService {
    public enum Stall {
        FARMER(VillagerProfession.FARMER), LIBRARIAN(VillagerProfession.LIBRARIAN), ARMORER(VillagerProfession.ARMORER),
        WEAPONSMITH(VillagerProfession.WEAPONSMITH), TOOLSMITH(VillagerProfession.TOOLSMITH), CLERIC(VillagerProfession.CLERIC),
        CARTOGRAPHER(VillagerProfession.CARTOGRAPHER), FLETCHER(VillagerProfession.FLETCHER), BUTCHER(VillagerProfession.BUTCHER),
        MASON(VillagerProfession.MASON), SHEPHERD(VillagerProfession.SHEPHERD), LEATHERWORKER(VillagerProfession.LEATHERWORKER),
        FISHERMAN(VillagerProfession.FISHERMAN);
        final VillagerProfession profession;
        Stall(VillagerProfession profession) { this.profession = profession; }
        public String id() { return name().toLowerCase(Locale.ROOT); }
        public Stall next() { return values()[(ordinal() + 1) % values().length]; }
    }
    public static final String KEY = "livingkingdoms:stall";
    public static final int TRADES_PER_TIER = 2, MAX_RARE = 3;

    /** A rare kingdom good: price, result, uses per day and the minimum market level that unlocks it. */
    record Rare(String id, int marketLevel, int uses, java.util.function.Function<ServerLevel, MerchantOffer> offer) {}
    private static final List<Rare> RARES = List.of(
            new Rare("whetstone", 1, 2, l -> new MerchantOffer(new ItemCost(Items.EMERALD, 18), Optional.of(new ItemCost(Items.DIAMOND, 1)), new ItemStack(KingdomItems.ROYAL_WHETSTONE.get()), 2, 10, 0.05F)),
            new Rare("tome", 1, 1, l -> new MerchantOffer(new ItemCost(Items.EMERALD, 24), Optional.of(new ItemCost(Items.BOOK, 1)), KingdomTomes.roll(l, l.getRandom()), 1, 15, 0.05F)),
            new Rare("rare_chest", 1, 1, l -> new MerchantOffer(new ItemCost(Items.EMERALD, 20), Optional.of(new ItemCost(Items.GOLD_INGOT, 4)), LootChests.chest(LootRarity.RARE), 1, 10, 0.05F)),
            new Rare("spike_traps", 1, 3, l -> new MerchantOffer(new ItemCost(Items.EMERALD, 10), Optional.of(new ItemCost(Items.IRON_INGOT, 4)), new ItemStack(dev.livingkingdoms.block.KingdomBlocks.SPIKE_TRAP.get(), 8), 3, 5, 0.05F)),
            new Rare("epic_chest", 2, 1, l -> new MerchantOffer(new ItemCost(Items.EMERALD, 40), Optional.of(new ItemCost(Items.DIAMOND, 2)), LootChests.chest(LootRarity.EPIC), 1, 20, 0.05F)),
            new Rare("totem", 2, 1, l -> new MerchantOffer(new ItemCost(Items.EMERALD, 32), Optional.of(new ItemCost(Items.GOLDEN_APPLE, 1)), new ItemStack(Items.TOTEM_OF_UNDYING), 1, 20, 0.05F)),
            new Rare("legendary_chest", 3, 1, l -> new MerchantOffer(new ItemCost(Items.EMERALD, 64), Optional.of(new ItemCost(Items.NETHERITE_SCRAP, 1)), LootChests.chest(LootRarity.LEGENDARY), 1, 30, 0.05F)),
            new Rare("whetstone_bundle", 3, 1, l -> new MerchantOffer(new ItemCost(Items.EMERALD, 48), Optional.of(new ItemCost(Items.DIAMOND, 3)), new ItemStack(KingdomItems.ROYAL_WHETSTONE.get(), 4), 1, 25, 0.05F)));
    private StallService() {}

    public static Stall stall(Villager worker) {
        var tag = worker.getPersistentData().getCompound(KEY);
        if (tag.contains("stall")) try { return Stall.valueOf(tag.getString("stall")); } catch (IllegalArgumentException ignored) {}
        return Stall.values()[Math.floorMod(worker.getUUID().hashCode(), Stall.values().length)];
    }

    /** Switches the stall's specialty (members only). Today's offers are rebuilt on the next visit. */
    public static boolean cycle(ServerPlayer player, Villager worker) {
        var p = MerchantWork.profile(worker).orElse(null);
        if (p == null || SettlementSavedData.get(player.server).get(p.settlementId()).filter(s -> KingdomOnboardingService.canHelp(player, s)).isEmpty()) return false;
        var tag = new CompoundTag(); tag.putString("stall", stall(worker).next().name());
        worker.getPersistentData().put(KEY, tag);
        return true;
    }

    /** Market level of the Merchant's workplace (1..3), which unlocks rarer goods and more of them. */
    static int marketLevel(ServerLevel level, Villager worker) {
        var p = MerchantWork.profile(worker).orElse(null);
        if (p == null || p.workplaceId() == null) return 1;
        return Math.clamp(BuildingDevelopment.level(level.getServer(), p.settlementId(), p.workplaceId()), 1, 3);
    }

    /** Today's offers for this stall: loaded with their used counts, or rolled fresh once per day/specialty. */
    public static MerchantOffers offers(ServerLevel level, Villager worker) {
        var stall = stall(worker); long day = RpgClock.today();
        var tag = worker.getPersistentData().getCompound(KEY);
        if (tag.getLong("day") == day && tag.getString("rolled").equals(stall.name()) && tag.contains("offers")) {
            var loaded = MerchantOffers.CODEC.parse(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), tag.getCompound("offers")).result();
            if (loaded.isPresent()) return loaded.get();
        }
        var offers = roll(level, worker, stall, day);
        save(level, worker, stall, day, offers);
        return offers;
    }

    static MerchantOffers roll(ServerLevel level, Villager worker, Stall stall, long day) {
        var random = RandomSource.create(worker.getUUID().getLeastSignificantBits() ^ day * 31 ^ stall.ordinal());
        var offers = new MerchantOffers();
        int merchantLevel = MerchantWork.profile(worker).map(p -> Math.clamp(p.level().value(), 1, 5)).orElse(1);
        var table = VillagerTrades.TRADES.get(stall.profession);
        if (table != null) for (int tier = 1; tier <= merchantLevel; tier++) {
            var listings = table.get(tier);
            if (listings == null || listings.length == 0) continue;
            var order = new ArrayList<>(Arrays.asList(listings));
            Collections.shuffle(order, new Random(random.nextLong()));
            int added = 0;
            for (var listing : order) {
                if (added >= TRADES_PER_TIER) break;
                MerchantOffer offer;
                try { offer = listing.getOffer(worker, random); } catch (RuntimeException ignored) { offer = null; }
                if (offer != null) { offers.add(offer); added++; }
            }
        }
        for (var rare : rares(level, worker, day)) offers.add(rare.offer().apply(level));
        return offers;
    }

    /** The rare goods on sale today at this stall (deterministic per Merchant and day). */
    static List<Rare> rares(ServerLevel level, Villager worker, long day) {
        int market = marketLevel(level, worker);
        var pool = new ArrayList<Rare>();
        for (var rare : RARES) if (rare.marketLevel() <= market) pool.add(rare);
        Collections.shuffle(pool, new Random(worker.getUUID().getMostSignificantBits() ^ day * 7919));
        var p = MerchantWork.profile(worker).orElse(null);
        boolean festival = p != null && EventService.festival(level.getServer(), p.settlementId());
        int count = Math.min(MAX_RARE, Math.min(pool.size(), market + (festival ? 1 : 0)));
        return pool.subList(0, count);
    }

    static void save(ServerLevel level, Villager worker, Stall stall, long day, MerchantOffers offers) {
        var tag = new CompoundTag();
        tag.putString("stall", stall.name()); tag.putString("rolled", stall.name()); tag.putLong("day", day);
        MerchantOffers.CODEC.encodeStart(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), offers).result()
                .ifPresent(nbt -> tag.put("offers", nbt));
        worker.getPersistentData().put(KEY, tag);
    }

    /** Opens the vanilla trading screen for this Merchant's stall. */
    public static boolean open(ServerPlayer player, Villager worker) {
        var p = MerchantWork.profile(worker).orElse(null);
        if (p == null || player.distanceToSqr(worker) > 64 || !MerchantWork.available(worker)
                || !dev.livingkingdoms.economy.KingdomEconomy.authorized(player, p.settlementId())) return false;
        var level = player.serverLevel();
        var stall = stall(worker);
        var merchant = new StallMerchant(worker, offers(level, worker));
        merchant.setTradingPlayer(player);
        var name = Component.translatable("stall.livingkingdoms.title", worker.getDisplayName(), Component.translatable("stall.livingkingdoms.kind." + stall.id()));
        merchant.openTradingScreen(player, name, Math.clamp(p.level().value(), 1, 5));
        var rares = rares(level, worker, RpgClock.today());
        if (!rares.isEmpty()) {
            var list = Component.empty();
            for (int i = 0; i < rares.size(); i++) {
                if (i > 0) list.append(", ");
                list.append(Component.translatable("stall.livingkingdoms.rare." + rares.get(i).id()));
            }
            long left = RpgClock.secondsUntilTomorrow();
            player.sendSystemMessage(Component.translatable("stall.livingkingdoms.rare_today", list, left / 3600, left % 3600 / 60).withStyle(ChatFormatting.GOLD));
        }
        return true;
    }

    /** Server-side merchant bound to a citizen Merchant; trades update the stored offers immediately. */
    static final class StallMerchant implements Merchant {
        private final Villager worker; private MerchantOffers offers; private Player trading;
        StallMerchant(Villager worker, MerchantOffers offers) { this.worker = worker; this.offers = offers; }
        @Override public void setTradingPlayer(Player player) { trading = player; }
        @Override public Player getTradingPlayer() {
            // Walking away or losing the Merchant closes the screen (MerchantMenu.stillValid checks this).
            if (trading != null && (!worker.isAlive() || worker.isRemoved() || trading.distanceToSqr(worker) > 100)) return null;
            return trading;
        }
        @Override public MerchantOffers getOffers() { return offers; }
        @Override public void overrideOffers(MerchantOffers next) { offers = next; }
        @Override public void notifyTrade(MerchantOffer offer) {
            offer.increaseUses();
            worker.playSound(SoundEvents.VILLAGER_YES, 1F, 1F);
            if (worker.level() instanceof ServerLevel level) {
                save(level, worker, stall(worker), RpgClock.today(), offers);
                if (trading instanceof ServerPlayer player) dev.livingkingdoms.chronicle.ChronicleAdvancements.grant(player, "first_trade");
            }
        }
        @Override public void notifyTradeUpdated(ItemStack stack) {
            if (!stack.isEmpty()) worker.playSound(SoundEvents.VILLAGER_TRADE, .6F, 1F);
        }
        @Override public int getVillagerXp() { return 0; }
        @Override public void overrideXp(int xp) {}
        @Override public boolean showProgressBar() { return false; }
        @Override public SoundEvent getNotifyTradeSound() { return SoundEvents.VILLAGER_YES; }
        @Override public boolean isClientSide() { return false; }
    }
}
