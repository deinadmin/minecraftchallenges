package de.carl.minecraftchallenges.smoke;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.smp.SmpService;
import de.carl.minecraftchallenges.smp.claim.Claim;
import de.carl.minecraftchallenges.smp.claim.ClaimFlag;
import de.carl.minecraftchallenges.smp.claim.ClaimService;
import de.carl.minecraftchallenges.smp.economy.ItemValues;
import de.carl.minecraftchallenges.smp.economy.MarketService;
import de.carl.minecraftchallenges.smp.npc.Artifacts;
import de.carl.minecraftchallenges.smp.npc.NpcArchetype;
import de.carl.minecraftchallenges.smp.npc.QuestSpec;
import de.carl.minecraftchallenges.smp.npc.SmpNpc;
import de.carl.minecraftchallenges.smp.npc.SmpNpcService;
import de.carl.minecraftchallenges.smp.shop.DailyShop;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.PluginCommand;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mannequin;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.generator.structure.Structure;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.StructureSearchResult;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Opt-in checks of the SMP economy, plots and NPCs on a real server. Run only in a disposable server directory. */
public final class SmpServerSmoke extends JavaPlugin {
    private int checks;
    private SmpService smp;
    private Object features;

    @Override public void onEnable() {
        if (!Boolean.getBoolean("minecraftchallenges.smp-smoke")) {
            getLogger().severe("Refusing smoke tests without -Dminecraftchallenges.smp-smoke=true");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                MinecraftChallengesPlugin plugin = (MinecraftChallengesPlugin) getServer().getPluginManager().getPlugin("MinecraftChallenges");
                // No player is online here, so NPCs would never wake; tests that watch them walk need them awake.
                plugin.getConfig().set("smp.npcs.wake-distance", 0);
                smp = (SmpService) field(plugin, "smp");
                features = field(plugin, "smpFeatures");
                expect(smp != null && features != null, "The SMP and its features start");
                for (String name : List.of("shop", "sell", "prices", "claim", "quest", "smpnpc", "pos", "spectate", "craftingtable", "buylevel", "ec", "invsee", "afktoken")) {
                    PluginCommand command = getServer().getPluginCommand(name);
                    expect(command != null && command.getExecutor() != plugin, "/" + name + " is registered");
                }
                expect(getServer().getPluginCommand("buylevel").getExecutor()
                        instanceof de.carl.minecraftchallenges.smp.economy.BuyLevelCommand, "/buylevel sells levels");
                economy();
                afkTokens();
                artifacts();
                npcs();
                placedBlocks();
                spectating();
                claims();
                structures().thenCompose(ignored -> traders()).thenCompose(ignored -> walkingQuestGiver()).whenComplete((ignored, error) -> finish(error));
            } catch (Throwable error) {
                finish(error);
            }
        });
    }

    private void afkTokens() {
        MinecraftChallengesPlugin plugin = (MinecraftChallengesPlugin) getServer().getPluginManager().getPlugin("MinecraftChallenges");
        Object command = getServer().getPluginCommand("afktoken").getExecutor();
        expect(command instanceof de.carl.minecraftchallenges.smp.afk.AfkTokenCommand, "/afktoken keeps chunks loaded");
        de.carl.minecraftchallenges.smp.afk.AfkTokenService tokens =
                (de.carl.minecraftchallenges.smp.afk.AfkTokenService) field(command, "tokens");
        org.bukkit.World world = smp.overworld();
        Location spot = new Location(world, 3000.5, 70, -3000.5);
        var token = tokens.create(spot, 3_600_000L, UUID.randomUUID(), "Smoke");
        int cx = token.chunkX(), cz = token.chunkZ();
        expect(world.isChunkLoaded(cx, cz) && world.getPluginChunkTickets(cx, cz).contains(plugin),
                "An AFK token puts a plugin ticket on exactly its chunk");
        expect(!world.getPluginChunkTickets(cx + 1, cz).contains(plugin), "Its neighbour gets no ticket");
        var renewed = tokens.create(spot.clone().add(3, 0, 3), 7_200_000L, UUID.randomUUID(), "Smoke");
        expect(renewed.id() == token.id() && tokens.all().size() == 1 && renewed.expiresAt() > token.expiresAt(),
                "A second token in the same chunk renews the first");
        var capped = tokens.create(spot.clone().add(64, 0, 0), 365L * 86_400_000L, UUID.randomUUID(), "Smoke");
        expect(capped.remaining(System.currentTimeMillis()) <= tokens.maxMillis(), "Durations are capped at max-days");
        expect(tokens.delete(capped.id()) && tokens.delete(token.id()), "Tokens can be deleted");
        expect(!world.getPluginChunkTickets(cx, cz).contains(plugin) && tokens.all().isEmpty(),
                "Deleting a token releases its chunk");
    }

    private void economy() {
        MarketService market = (MarketService) field(features, "market");
        ItemValues values = market.values();
        int books = 0;
        for (ItemValues.Entry entry : values.entries()) {
            ItemStack item = ItemValues.createItem(entry, entry.bundle());
            if (item.isEmpty()) throw new AssertionError(entry.id() + " builds no item");
            if (entry.enchant() != null) {
                if (!(item.getItemMeta() instanceof EnchantmentStorageMeta meta) || meta.getStoredEnchants().isEmpty()) {
                    throw new AssertionError(entry.id() + " is not an enchanted book");
                }
                books++;
            }
        }
        expect(books >= 12, "All " + values.entries().size() + " value entries build real items, " + books + " of them enchanted books");

        ItemValues.Entry iron = values.entry("IRON_INGOT");
        UUID seller = UUID.randomUUID();
        long first = market.sell(seller, Map.of(iron, 640.0)).total();
        long second = market.sell(seller, Map.of(iron, 640.0)).total();
        expect(first > 1000 && second < first / 2, "Dumping iron pays " + first + ", then only " + second + " coins");
        expect(market.factor(iron) < 0.2, "The iron price has dropped for everyone");
        expect(market.wanted().size() == values.wantedCount(), "Today's wanted items are chosen");

        DailyShop shop = (DailyShop) field(features, "dailyShop");
        expect(shop.offers().size() == 9, "The daily shop has nine offers");
        List<DailyShop.Offer> before = shop.offers();
        shop.shuffle();
        expect(!before.equals(shop.offers()) && shop.offers().size() == 9, "A shuffle replaces the offers");
    }

    private void artifacts() {
        Artifacts artifacts = ((SmpNpcService) field(features, "npcs")).artifacts();
        Random random = new Random(7);
        int legendary = 0;
        for (int index = 0; index < 400; index++) {
            var reward = artifacts.roll(random, "Smoke");
            ItemStack item = reward.item();
            if (!artifacts.isArtifact(item)) throw new AssertionError("Artifact without marker");
            boolean beyond = false;
            for (Map.Entry<Enchantment, Integer> enchant : item.getEnchantments().entrySet()) {
                beyond |= enchant.getValue() > enchant.getKey().getMaxLevel();
            }
            if (!beyond) throw new AssertionError(item.getType() + " has no enchantment beyond the vanilla maximum: " + item.getEnchantments());
            if (reward.value() == Artifacts.LEGENDARY_VALUE) legendary++;
        }
        expect(legendary > 5 && legendary < 100, "400 artifacts all exceed vanilla levels; " + legendary + " are legendary");
    }

    private void npcs() {
        SmpNpcService npcs = (SmpNpcService) field(features, "npcs");
        World world = smp.overworld();
        Location spawn = world.getSpawnLocation();
        Location spot = new Location(world, spawn.getBlockX() + 0.5, spawn.getBlockY() + 3, spawn.getBlockZ() + 0.5);
        spot.getChunk().load(true);
        for (NpcArchetype archetype : NpcArchetype.all()) {
            for (QuestSpec quest : archetype.quests()) {
                for (String target : quest.targets()) {
                    if (quest.type() == QuestSpec.Type.KILL) EntityType.valueOf(target);
                    else if (!Material.valueOf(target).isBlock()) throw new AssertionError(target + " is no block");
                }
            }
            if (archetype.trader()) continue;
            SmpNpc npc = npcs.createQuestGiver(archetype, spot, null);
            if (npc.offers().isEmpty() || npc.offers().size() > 3) throw new AssertionError(archetype.key() + " offers " + npc.offers().size() + " quests");
            Mannequin body = body(world, spot);
            if (body == null) throw new AssertionError(archetype.key() + " has no body");
            if (body.getProfile().properties().isEmpty()) throw new AssertionError(archetype.key() + " has no skin");
            if (!body.isInvulnerable() || body.isPersistent()) throw new AssertionError(archetype.key() + " is not protected or would be saved");
            // Quests that take items away always pay clearly more than those items are worth.
            SmpNpc current = npc;
            for (int roll = 0; roll < 40; roll++) {
                current = npcs.reroll(current);
                for (SmpNpc.Offer offer : current.offers()) {
                    // Rewards follow the quest: the shortest quest of a tier still pays 75 % of 75 % of the tier budget.
                    long floor = switch (offer.quest().tier()) { case 1 -> 330; case 2 -> 840; default -> 2500; };
                    if (offer.rewardValue() < floor) {
                        throw new AssertionError(offer.quest().id() + " (tier " + offer.quest().tier() + ") pays only "
                                + offer.rewardValue() + ": " + offer.reward().getType());
                    }
                    if (offer.quest().handIn() != null) {
                        var entry = ((ItemValues) field(npcs, "values")).entry(offer.quest().handIn().name());
                        if (entry == null) throw new AssertionError(offer.quest().id() + " hands in an item without a value");
                        double cost = entry.value() * offer.quest().amount();
                        if (offer.rewardValue() < cost * 1.5) {
                            throw new AssertionError(offer.quest().id() + " takes " + cost + " coins of items for a reward worth only " + offer.rewardValue());
                        }
                    }
                }
            }
            npcs.remove(current);
            if (body(world, spot) != null) throw new AssertionError(archetype.key() + " leaves a body behind");
        }
        expect(true, "All quest givers spawn with skin and 1 to 3 quests, and vanish when removed");

        SmpNpc trader = npcs.createTrader(spot, 3_600_000L);
        expect(trader.trader() && trader.stock().size() >= 3 && trader.stock().stream().allMatch(stock -> stock.price() > 0 && !stock.item().isEmpty()),
                "A trader brings " + trader.stock().size() + " priced wares");
        expect(body(world, spot) != null && npcs.near(spot, 2) == trader, "The trader stands where he was placed");
        npcs.remove(trader);
        expect(npcs.all().isEmpty(), "No test NPC stays behind");
    }

    private static Mannequin body(World world, Location spot) {
        for (Entity entity : world.getNearbyEntities(spot, 1, 2, 1)) {
            if (entity instanceof Mannequin mannequin && mannequin.isValid()) return mannequin;
        }
        return null;
    }

    private void claims() {
        ClaimService claims = (ClaimService) field(features, "claims");
        World world = smp.overworld();
        Location spawn = world.getSpawnLocation();
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        expect(claims.blocked(owner, spawn.getBlockX() + 100, spawn.getBlockZ(), 20, null) != null, "No plot inside the spawn protection");
        int x = spawn.getBlockX() + 400;
        int z = spawn.getBlockZ() + 400;
        String reason = claims.blocked(owner, x, z, 20, null);
        expect(reason == null, "A plot far from spawn is allowed" + (reason == null ? "" : ": " + reason));
        Claim claim = claims.create(owner, x, z, 150);
        expect(claims.at(world, x + 20, z - 20) == claim && claims.at(world, x + 21, z) == null, "The plot covers exactly its square");
        expect(claims.blocked(stranger, x + 45, z, 20, null) != null, "A stranger's plot needs distance");
        expect(claims.blocked(stranger, x + 56, z, 20, null) == null, "Fifteen blocks away is fine");
        expect(claims.blocked(owner, x + 41, z, 20, null) == null && claims.blocked(owner, x + 40, z, 20, null) != null,
                "Own plots may touch but not overlap");
        Claim neighbour = claims.create(stranger, x + 56, z, 150);
        expect(claims.blocked(owner, x, z, 30, claim) == null && claims.blocked(owner, x, z, 40, claim) != null,
                "A plot grows up to its neighbour, not into it");

        world.getChunkAt((x + 20) >> 4, z >> 4).load(true);
        world.getChunkAt((x + 21) >> 4, z >> 4).load(true);
        Block inside = world.getBlockAt(x + 20, 200, z);
        Block outside = world.getBlockAt(x + 21, 200, z);
        BlockFromToEvent flowIn = new BlockFromToEvent(outside, inside);
        BlockFromToEvent flowWithin = new BlockFromToEvent(inside.getRelative(BlockFace.WEST), inside);
        BlockFromToEvent flowOut = new BlockFromToEvent(inside, outside);
        Bukkit.getPluginManager().callEvent(flowIn);
        Bukkit.getPluginManager().callEvent(flowWithin);
        Bukkit.getPluginManager().callEvent(flowOut);
        expect(flowIn.isCancelled() && !flowWithin.isCancelled() && !flowOut.isCancelled(), "Liquids do not flow into a plot from outside");
        BlockPistonExtendEvent push = new BlockPistonExtendEvent(outside.getRelative(BlockFace.EAST), List.of(outside), BlockFace.WEST);
        BlockPistonExtendEvent ownPush = new BlockPistonExtendEvent(inside.getRelative(BlockFace.WEST, 2), List.of(), BlockFace.EAST);
        Bukkit.getPluginManager().callEvent(push);
        Bukkit.getPluginManager().callEvent(ownPush);
        expect(push.isCancelled() && !ownPush.isCancelled(), "Pistons do not push into a plot from outside");
        BlockBurnEvent burn = new BlockBurnEvent(inside, outside);
        Bukkit.getPluginManager().callEvent(burn);
        expect(burn.isCancelled(), "Fire from outside burns nothing on a plot");

        // Permissions survive a reload and do not pass to a new owner.
        claims.setAllowed(claim, ClaimFlag.DOORS, true);
        claims.setAllowed(claim, ClaimFlag.PVP, false);
        smp.writer().flush();
        claims.reload();
        Claim reloaded = claims.find(claim.id());
        expect(reloaded != null && reloaded.allows(ClaimFlag.DOORS) && !reloaded.allows(ClaimFlag.PVP)
                && reloaded.allows(ClaimFlag.CRAFTING_TABLE) && !reloaded.allows(ClaimFlag.CHESTS), "Permissions survive a reload");
        claims.transfer(reloaded, stranger);
        expect(reloaded.allowed().equals(ClaimFlag.defaults()), "A new owner starts with the default permissions");
        claim = reloaded;
        neighbour = claims.find(neighbour.id());

        claims.remove(neighbour);
        claims.remove(claim);
        expect(claims.isEmpty(), "No test plot stays behind");
    }

    /** End to end: loading the chunks of a nether fortress makes its quest giver appear. */
    private CompletableFuture<Void> structures() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        SmpNpcService npcs = (SmpNpcService) field(features, "npcs");
        World nether = smp.nether();
        StructureSearchResult found = nether.locateNearestStructure(new Location(nether, 0, 64, 0), Structure.FORTRESS, 200, false);
        expect(found != null, "A nether fortress exists near the origin");
        int chunkX = found.getLocation().getBlockX() >> 4;
        int chunkZ = found.getLocation().getBlockZ() >> 4;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) nether.getChunkAtAsync(chunkX + dx, chunkZ + dz, true, chunk -> chunk.addPluginChunkTicket(this));
        }
        int[] waited = {0};
        Bukkit.getScheduler().runTaskTimer(this, task -> {
            try {
                SmpNpc giver = npcs.all().stream().filter(npc -> npc.archetype().key().equals("fortress")).findFirst().orElse(null);
                if (giver == null) {
                    if (++waited[0] > 60) throw new AssertionError("No quest giver appeared at the fortress within 60 seconds");
                    return;
                }
                task.cancel();
                Location at = giver.location();
                expect(giver.structure() != null && at.getWorld().equals(nether), "The fortress got its quest giver " + giver.title()
                        + " at " + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ());
                expect(at.getBlock().getRelative(BlockFace.DOWN).getType().isSolid() && at.getBlock().getType().isAir()
                        && at.getBlock().getRelative(BlockFace.UP).getType().isAir(), "He stands on solid ground with room above");
                expect(body(nether, at) != null, "His body is in the world");
                expect(npcs.all().stream().filter(npc -> npc.archetype().key().equals("fortress")).count() == 1,
                        "One fortress, one quest giver");
                done.complete(null);
            } catch (Throwable error) {
                task.cancel();
                done.completeExceptionally(error);
            }
        }, 20L, 20L);
        return done;
    }

    /** A trader walks inside his square, stands still while held, blocks plots and protects his surroundings. */
    private CompletableFuture<Void> traders() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            SmpNpcService npcs = (SmpNpcService) field(features, "npcs");
            ClaimService claims = (ClaimService) field(features, "claims");
            World world = smp.overworld();
            Location spawn = world.getSpawnLocation();
            int cx = spawn.getBlockX() + 700;
            int cz = spawn.getBlockZ() + 700;
            for (int x = cx - 14; x <= cx + 14; x++) {
                for (int z = cz - 14; z <= cz + 14; z++) {
                    world.getBlockAt(x, 199, z).setType(Material.STONE);
                    for (int y = 200; y <= 203; y++) world.getBlockAt(x, y, z).setType(Material.AIR);
                }
            }
            // Pillars to walk around and a one-block step to climb, so a body cutting into blocks gets caught.
            for (int[] pillar : new int[][] {{3, 0}, {-3, 2}, {0, -4}, {5, 5}, {-6, -5}, {-2, 7}}) {
                for (int y = 200; y <= 202; y++) world.getBlockAt(cx + pillar[0], y, cz + pillar[1]).setType(Material.STONE);
            }
            for (int x = cx + 6; x <= cx + 8; x++) {
                for (int z = cz - 3; z <= cz - 1; z++) world.getBlockAt(x, 200, z).setType(Material.STONE);
            }
            for (int chunkX = (cx - 14) >> 4; chunkX <= (cx + 14) >> 4; chunkX++) {
                for (int chunkZ = (cz - 14) >> 4; chunkZ <= (cz + 14) >> 4; chunkZ++) {
                    world.getChunkAt(chunkX, chunkZ).addPluginChunkTicket(this);
                }
            }
            Location start = new Location(world, cx + 0.5, 200, cz + 0.5);
            SmpNpc trader = npcs.createTrader(start, 600_000L);
            UUID owner = UUID.randomUUID();
            String near = claims.blocked(owner, cx + 12, cz, 3, null);
            expect(near != null && near.contains("Händler"), "No plot may reach into a trader's area: " + near);
            String far = claims.blocked(owner, cx + 40, cz, 3, null);
            expect(far == null || !far.contains("Händler"), "A plot well outside his area is not blocked by him");
            SmpNpc giver = npcs.createQuestGiver(NpcArchetype.all().stream().filter(a -> !a.trader()).findFirst().orElseThrow(),
                    new Location(world, cx + 20.5, 200, cz + 20.5), null);
            BlockBurnEvent floor = new BlockBurnEvent(world.getBlockAt(cx + 20, 199, cz + 20), null);
            BlockBurnEvent neighbour = new BlockBurnEvent(world.getBlockAt(cx + 21, 199, cz + 20), null);
            Bukkit.getPluginManager().callEvent(floor);
            Bukkit.getPluginManager().callEvent(neighbour);
            expect(floor.isCancelled() && !neighbour.isCancelled(), "A quest giver keeps exactly the block he stands on");
            npcs.remove(giver);
            BlockBurnEvent inside = new BlockBurnEvent(world.getBlockAt(cx + 10, 200, cz), null);
            BlockBurnEvent outside = new BlockBurnEvent(world.getBlockAt(cx + 11, 200, cz), null);
            Bukkit.getPluginManager().callEvent(inside);
            Bukkit.getPluginManager().callEvent(outside);
            expect(inside.isCancelled() && !outside.isCancelled(), "Blocks within ten blocks of the trader are protected");

            int[] phase = {0};
            int[] ticks = {0};
            Location[] mark = {null};
            Bukkit.getScheduler().runTaskTimer(this, task -> {
                try {
                    Mannequin body = world.getEntitiesByClass(Mannequin.class).stream()
                            .filter(m -> m.isValid() && Math.abs(m.getX() - cx) < 20 && Math.abs(m.getZ() - cz) < 20)
                            .findFirst().orElse(null);
                    if (body == null) throw new AssertionError("The trader has no body");
                    Location now = body.getLocation();
                    noClip(world, body);
                    if (Math.abs(now.getX() - (cx + 0.5)) > 10.6 || Math.abs(now.getZ() - (cz + 0.5)) > 10.6) {
                        throw new AssertionError("The trader left his area: " + now);
                    }
                    ticks[0]++;
                    if (phase[0] == 0) {
                        if (now.distance(start) > 1.5) {
                            expect(true, "The trader strolls around his home within ten blocks");
                            Entity nameTag = body.getPassengers().isEmpty() ? null : body.getPassengers().get(0);
                            Entity roleTag = nameTag == null || nameTag.getPassengers().isEmpty() ? null : nameTag.getPassengers().get(0);
                            expect(nameTag instanceof org.bukkit.entity.TextDisplay && roleTag instanceof org.bukkit.entity.TextDisplay,
                                    "His name and role ride on his body, so they cannot lag behind");
                            double nameLift = ((org.bukkit.entity.TextDisplay) nameTag).getTransformation().getTranslation().y();
                            double roleLift = ((org.bukkit.entity.TextDisplay) roleTag).getTransformation().getTranslation().y();
                            double bodyHeight = body.getHeight();
                            expect(Math.abs(nameLift + bodyHeight - 2.45) < 0.01 && Math.abs(roleLift + bodyHeight - 2.17) < 0.01,
                                    "The name floats 2.45 and the role 2.17 blocks above his feet");
                            expect(Math.hypot(nameTag.getX() - now.getX(), nameTag.getZ() - now.getZ()) < 0.01
                                    && Math.hypot(roleTag.getX() - now.getX(), roleTag.getZ() - now.getZ()) < 0.01,
                                    "The tags travel with him while he walks");
                            npcs.hold(trader.id());
                            phase[0] = 1;
                            ticks[0] = 0;
                        } else if (ticks[0] > 1200) {
                            throw new AssertionError("The trader never moved");
                        }
                    } else if (phase[0] == 1) {
                        if (ticks[0] == 3) mark[0] = now.clone();
                        if (ticks[0] > 3 && now.distanceSquared(mark[0]) > 1e-6) {
                            throw new AssertionError("The trader moved although his menu was open");
                        }
                        if (ticks[0] == 80) {
                            expect(true, "The trader stands still while his menu is open");
                            npcs.release(trader.id());
                            phase[0] = 2;
                            ticks[0] = 0;
                            mark[0] = now.clone();
                        }
                    } else if (now.distance(mark[0]) > 0.5) {
                        task.cancel();
                        expect(true, "He walks on once the menu is closed");
                        npcs.remove(trader);
                        String after = claims.blocked(owner, cx + 12, cz, 3, null);
                        expect(after == null || !after.contains("Händler"), "Plots are possible again once the trader has left");
                        done.complete(null);
                    } else if (ticks[0] > 1200) {
                        throw new AssertionError("The trader never walked on after the menu closed");
                    }
                } catch (Throwable error) {
                    task.cancel();
                    done.completeExceptionally(error);
                }
            }, 1L, 1L);
        } catch (Throwable error) {
            done.completeExceptionally(error);
        }
        return done;
    }

    /** A quest giver with a box strolls inside it, and the block under his feet follows him. */
    private CompletableFuture<Void> walkingQuestGiver() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            SmpNpcService npcs = (SmpNpcService) field(features, "npcs");
            World world = smp.overworld();
            Location spawn = world.getSpawnLocation();
            int cx = spawn.getBlockX() + 700;
            int cz = spawn.getBlockZ() + 700;
            expect(NpcArchetype.get("village").roams() && NpcArchetype.get("fortress").roams()
                            && !NpcArchetype.get("desert_pyramid").roams() && !NpcArchetype.get("ruined_portal").roams(),
                    "Villages and fortresses roam; trapped or lava structures keep their quest giver still");
            java.lang.reflect.Method canStand = SmpNpcService.class.getDeclaredMethod("canStand",
                    World.class, int.class, int.class, int.class, boolean.class);
            canStand.setAccessible(true);
            int sx = cx + 12;
            int sz = cz + 12;
            boolean stone = (boolean) canStand.invoke(null, world, sx, 200, sz, false);
            record Floor(Material material, String name) { }
            for (Floor floor : List.of(new Floor(Material.POINTED_DRIPSTONE, "pointed dripstone"),
                    new Floor(Material.STONE_SLAB, "a slab"), new Floor(Material.OAK_FENCE, "a fence"),
                    new Floor(Material.WATER, "water"), new Floor(Material.LAVA, "lava"),
                    new Floor(Material.MAGMA_BLOCK, "magma"), new Floor(Material.SOUL_SAND, "soul sand"))) {
                world.getBlockAt(sx, 199, sz).setType(floor.material());
                if ((boolean) canStand.invoke(null, world, sx, 200, sz, false)) throw new AssertionError("A spot on " + floor.name() + " is accepted");
            }
            world.getBlockAt(sx, 199, sz).setType(Material.DIRT_PATH);
            boolean path = (boolean) canStand.invoke(null, world, sx, 200, sz, false);
            world.getBlockAt(sx, 199, sz).setType(Material.STONE);
            expect(stone && path, "NPCs are placed on stone and paths, but never on liquids, dripstone, slabs, fences, magma or soul sand");
            int[] bounds = {cx - 9, 200, cz - 9, cx + 9, 203, cz + 9};
            SmpNpc giver = npcs.createQuestGiver(NpcArchetype.get("village"), new Location(world, cx + 0.5, 200, cz + 0.5),
                    "smoke", bounds);
            expect(giver.bounds() != null && giver.bounds()[0] == cx - 9, "The quest giver remembers his box");
            Location start = new Location(world, cx + 0.5, 200, cz + 0.5);
            // With the real wake distance and nobody near, he must not take a single step.
            getServer().getPluginManager().getPlugin("MinecraftChallenges").getConfig().set("smp.npcs.wake-distance", 64);
            int[] frozen = {0};
            int[] ticks = {0};
            boolean[] moved = {false};
            double[] farthest = {0};
            Bukkit.getScheduler().runTaskTimer(this, task -> {
                try {
                    Mannequin body = world.getEntitiesByClass(Mannequin.class).stream()
                            .filter(m -> m.isValid() && Math.abs(m.getX() - cx) < 20 && Math.abs(m.getZ() - cz) < 20)
                            .findFirst().orElse(null);
                    if (body == null) throw new AssertionError("The quest giver has no body");
                    Location now = body.getLocation();
                    noClip(world, body);
                    if (now.getX() < cx - 9 || now.getX() > cx + 10 || now.getZ() < cz - 9 || now.getZ() > cz + 10) {
                        throw new AssertionError("The quest giver left his box: " + now);
                    }
                    if (frozen[0] < 200) {
                        if (now.distance(start) > 0.01) throw new AssertionError("The quest giver walked with nobody near: " + now);
                        if (++frozen[0] == 200) {
                            expect(true, "Walking NPCs stand still while no player is within the wake distance");
                            getServer().getPluginManager().getPlugin("MinecraftChallenges").getConfig().set("smp.npcs.wake-distance", 0);
                        }
                        return;
                    }
                    ticks[0]++;
                    farthest[0] = Math.max(farthest[0], now.distance(start));
                    if (!moved[0]) {
                        if (ticks[0] > 1200) throw new AssertionError("The quest giver never walked");
                        if (now.distance(start) <= 1.5) return;
                        moved[0] = true;
                        ticks[0] = 0;
                        expect(true, "The quest giver strolls around inside his box");
                    int bx = now.getBlockX();
                    int bz = now.getBlockZ();
                    BlockBurnEvent under = new BlockBurnEvent(world.getBlockAt(bx, 199, bz), null);
                    int farX = bx > cx ? bx - 3 : bx + 3;
                    BlockBurnEvent away = new BlockBurnEvent(world.getBlockAt(farX, 199, bz), null);
                    Bukkit.getPluginManager().callEvent(under);
                    Bukkit.getPluginManager().callEvent(away);
                    expect(under.isCancelled() && !away.isCancelled(), "The block under his feet is protected, the ones around it are not");
                        return;
                    }
                    // Keep watching for 40 seconds: around pillars and over a one-block step he must never cut into a block.
                    if (ticks[0] < 800) return;
                    task.cancel();
                    expect(farthest[0] > 3, "In 40 seconds he walked up to " + Math.round(farthest[0]) + " blocks from home and never cut into a block");
                    npcs.remove(giver);
                    done.complete(null);
                } catch (Throwable error) {
                    task.cancel();
                    done.completeExceptionally(error);
                }
            }, 1L, 1L);
        } catch (Throwable error) {
            done.completeExceptionally(error);
        }
        return done;
    }

    /** The body must never overlap a block, not even by a sliver. */
    private static void noClip(World world, Mannequin body) {
        org.bukkit.util.BoundingBox box = body.getBoundingBox().expand(-0.03);
        if (world.hasCollisionsIn(box)) throw new AssertionError("The NPC cut into a block at " + body.getLocation());
    }

    private void finish(Throwable error) {
        if (error == null) {
            try {
                var board = getServer().getScoreboardManager().getMainScoreboard();
                var admin = board.getTeam("mc_rank_admin");
                var mod = board.getTeam("mc_rank_mod");
                var player = board.getTeam("mc_rank_player");
                expect(admin != null && mod != null && player != null
                                && net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                                .serialize(admin.prefix()).equals("Admin | ")
                                && admin.color() == net.kyori.adventure.text.format.NamedTextColor.RED
                                && player.color() == net.kyori.adventure.text.format.NamedTextColor.GRAY,
                        "Rank teams give name tags the rank prefix and colour");
            } catch (Throwable failure) {
                error = failure;
            }
        }
        if (error == null) getLogger().info("SMP_SMOKE_PASSED: " + checks + " checks");
        else { getLogger().severe("SMP_SMOKE_FAILED: " + error); error.printStackTrace(); }
        Bukkit.shutdown();
    }

    /** Mining quests: a piston carries the "placed" mark along, and pending saves are written before a backup. */
    private void placedBlocks() throws Exception {
        Object quests = field(features, "quests");
        var remember = quests.getClass().getDeclaredMethod("rememberPlaced", org.bukkit.block.Block.class);
        var forget = quests.getClass().getDeclaredMethod("forgetPlaced", org.bukkit.block.Block.class);
        remember.setAccessible(true);
        forget.setAccessible(true);
        World world = smp.overworld();
        Location spawn = world.getSpawnLocation();
        int x = spawn.getBlockX() + 40;
        int z = spawn.getBlockZ() + 40;
        world.getChunkAt(x >> 4, z >> 4).load(true);
        org.bukkit.block.Block piston = world.getBlockAt(x, 220, z);
        piston.setType(Material.STICKY_PISTON);
        var facing = (org.bukkit.block.data.Directional) piston.getBlockData();
        facing.setFacing(org.bukkit.block.BlockFace.EAST);
        piston.setBlockData(facing);
        org.bukkit.block.Block near = piston.getRelative(org.bukkit.block.BlockFace.EAST);
        org.bukkit.block.Block far = near.getRelative(org.bukkit.block.BlockFace.EAST);

        near.setType(Material.GOLD_BLOCK);
        remember.invoke(quests, near);
        Bukkit.getPluginManager().callEvent(new org.bukkit.event.block.BlockPistonExtendEvent(piston, List.of(near),
                org.bukkit.block.BlockFace.EAST));
        boolean pushedPlaced = (boolean) forget.invoke(quests, far);
        boolean oldSpotPlaced = (boolean) forget.invoke(quests, near);
        expect(pushedPlaced && !oldSpotPlaced, "A placed gold block stays placed when a piston pushes it");

        near.setType(Material.AIR);
        far.setType(Material.GOLD_BLOCK);
        remember.invoke(quests, far);
        Bukkit.getPluginManager().callEvent(new org.bukkit.event.block.BlockPistonRetractEvent(piston, List.of(far),
                org.bukkit.block.BlockFace.WEST));
        boolean pulledPlaced = (boolean) forget.invoke(quests, near);
        boolean leftPlaced = (boolean) forget.invoke(quests, far);
        expect(pulledPlaced && !leftPlaced, "A sticky piston pulls the mark back with the block");

        far.setType(Material.AIR);
        near.setType(Material.MELON);
        remember.invoke(quests, near);
        Bukkit.getPluginManager().callEvent(new org.bukkit.event.block.BlockPistonExtendEvent(piston, List.of(near),
                org.bukkit.block.BlockFace.EAST));
        boolean melonMoved = (boolean) forget.invoke(quests, far);
        boolean melonLeft = (boolean) forget.invoke(quests, near);
        expect(!melonMoved && !melonLeft, "A melon a piston breaks leaves no mark behind");
        near.setType(Material.AIR);
        piston.setType(Material.AIR);

        SmpNpcService npcs = (SmpNpcService) field(features, "npcs");
        SmpNpc giver = npcs.createQuestGiver(NpcArchetype.get("village"), new Location(world, x + 0.5, 221, z + 0.5), null);
        boolean waiting = field(npcs, "pendingSave") != null;
        smp.saveAllAndFlush();
        expect(waiting && field(npcs, "pendingSave") == null, "A backup writes the NPC save that was still waiting");
        npcs.remove(giver);
    }

    /** Admin spectating: blocks vanish silently with what stood on them, and the tab-list packet resolves. */
    private void spectating() throws Exception {
        expect(getServer().getPluginCommand("ct") != null, "/ct is an alias of /craftingtable");
        new de.carl.minecraftchallenges.util.TabListPackets();
        expect(true, "The tab-list packet for invisible admins exists on this server");

        Location dressing = smp.overworld().getSpawnLocation().clone().add(0, 200, 0);
        Mannequin source = smp.overworld().spawn(dressing, Mannequin.class, m -> {
            m.setPersistent(false);
            m.getEquipment().setHelmet(new ItemStack(Material.DIAMOND_HELMET));
            m.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_PICKAXE));
        });
        Mannequin twin = smp.overworld().spawn(dressing, Mannequin.class, m -> {
            m.setPersistent(false);
            de.carl.minecraftchallenges.smp.spectate.AdminSpectateService.wear(m, source.getEquipment());
        });
        boolean dressed = twin.getEquipment().getHelmet().getType() == Material.DIAMOND_HELMET
                && twin.getEquipment().getItemInMainHand().getType() == Material.IRON_PICKAXE;
        source.remove();
        twin.remove();
        expect(dressed, "The spectator's double is dressed while it spawns");

        mobsIgnoreHidden(dressing);

        World world = smp.overworld();
        Location spawn = world.getSpawnLocation();
        int x = spawn.getBlockX() + 60;
        int z = spawn.getBlockZ() + 60;
        world.getChunkAt(x >> 4, z >> 4).load(true);
        Block base = world.getBlockAt(x, 230, z);
        base.setType(Material.STONE);
        Block torch = base.getRelative(BlockFace.UP);
        torch.setType(Material.TORCH);
        Block wallTorch = base.getRelative(BlockFace.NORTH);
        var wall = (org.bukkit.block.data.Directional) Material.WALL_TORCH.createBlockData();
        wall.setFacing(BlockFace.NORTH);
        wallTorch.setBlockData(wall, false);
        int removed = de.carl.minecraftchallenges.smp.spectate.SilentBlocks.breakSilently(base);
        expect(base.getType().isAir() && torch.getType().isAir() && wallTorch.getType().isAir() && removed == 3,
                "A silent break also removes the torches that stood on the block (" + removed + ")");

        Block floor = world.getBlockAt(x + 3, 229, z);
        floor.setType(Material.STONE);
        Block lower = floor.getRelative(BlockFace.UP);
        Block upper = lower.getRelative(BlockFace.UP);
        var bottom = (org.bukkit.block.data.type.Door) Material.OAK_DOOR.createBlockData();
        bottom.setHalf(org.bukkit.block.data.Bisected.Half.BOTTOM);
        var top = (org.bukkit.block.data.type.Door) Material.OAK_DOOR.createBlockData();
        top.setHalf(org.bukkit.block.data.Bisected.Half.TOP);
        lower.setBlockData(bottom, false);
        upper.setBlockData(top, false);
        var items = world.getNearbyEntities(upper.getLocation(), 4, 4, 4, entity -> entity instanceof org.bukkit.entity.Item).size();
        de.carl.minecraftchallenges.smp.spectate.SilentBlocks.breakSilently(upper);
        int itemsAfter = world.getNearbyEntities(upper.getLocation(), 4, 4, 4, entity -> entity instanceof org.bukkit.entity.Item).size();
        expect(upper.getType().isAir() && lower.getType().isAir() && itemsAfter == items,
                "Breaking the upper door half removes the whole door without drops");

        Block chest = world.getBlockAt(x + 6, 230, z);
        chest.setType(Material.CHEST);
        ((org.bukkit.block.Chest) chest.getState(false)).getBlockInventory().addItem(new ItemStack(Material.DIAMOND, 3));
        de.carl.minecraftchallenges.smp.spectate.SilentBlocks.breakSilently(chest);
        long diamonds = world.getNearbyEntities(chest.getLocation(), 3, 3, 3, entity -> entity instanceof org.bukkit.entity.Item item
                && item.getItemStack().getType() == Material.DIAMOND).size();
        expect(chest.getType().isAir() && diamonds == 1, "A silently broken chest still spills its items like in creative");
        floor.setType(Material.AIR);
    }

    /** A mob's look-at goal rejects a hidden target, but still accepts it once it is visible again. */
    private void mobsIgnoreHidden(Location at) throws Exception {
        Class<?> type = Class.forName("de.carl.minecraftchallenges.smp.spectate.MobAwareness");
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object awareness = constructor.newInstance();
        var patch = type.getDeclaredMethod("patch", org.bukkit.entity.Mob.class);
        var hide = type.getDeclaredMethod("hideHandle", Object.class);
        var reveal = type.getDeclaredMethod("revealHandle", Object.class);
        patch.setAccessible(true);
        hide.setAccessible(true);
        reveal.setAccessible(true);
        World world = at.getWorld();
        org.bukkit.entity.Villager watcher = world.spawn(at, org.bukkit.entity.Villager.class, v -> v.setPersistent(false));
        org.bukkit.entity.Zombie looker = world.spawn(at, org.bukkit.entity.Zombie.class, z -> z.setPersistent(false));
        org.bukkit.entity.Cow target = world.spawn(at.clone().add(1, 0, 0), org.bukkit.entity.Cow.class, c -> c.setPersistent(false));
        try {
            patch.invoke(awareness, looker);
            Object lookerHandle = looker.getClass().getMethod("getHandle").invoke(looker);
            Object targetHandle = target.getClass().getMethod("getHandle").invoke(target);
            Object level = world.getClass().getMethod("getHandle").invoke(world);
            Class<?> living = Class.forName("net.minecraft.world.entity.LivingEntity");
            Class<?> serverLevel = Class.forName("net.minecraft.server.level.ServerLevel");
            Class<?> conditionsType = Class.forName("net.minecraft.world.entity.ai.targeting.TargetingConditions");
            var test = conditionsType.getMethod("test", serverLevel, living, living);
            Object conditions = conditionsType.getMethod("forNonCombat").invoke(null);
            conditions = conditionsType.getMethod("range", double.class).invoke(conditions, 8.0);
            // A fresh condition, filtered through the shared sensor conditions: patch one by hand like a goal's.
            var patchConditions = type.getDeclaredMethod("patchConditions", Object.class);
            patchConditions.setAccessible(true);
            patchConditions.invoke(awareness, conditions);
            boolean before = (boolean) test.invoke(conditions, level, lookerHandle, targetHandle);
            hide.invoke(awareness, targetHandle);
            boolean hidden = (boolean) test.invoke(conditions, level, lookerHandle, targetHandle);
            var sensorConditions = Class.forName("net.minecraft.world.entity.ai.sensing.Sensor").getDeclaredField("TARGET_CONDITIONS");
            sensorConditions.setAccessible(true);
            boolean sensorHidden = (boolean) test.invoke(sensorConditions.get(null), level,
                    watcher.getClass().getMethod("getHandle").invoke(watcher), targetHandle);
            reveal.invoke(awareness, targetHandle);
            boolean after = (boolean) test.invoke(conditions, level, lookerHandle, targetHandle);
            expect(before && !hidden && !sensorHidden && after,
                    "Mobs and villager sensors ignore a hidden spectator and notice it again afterwards");

            var priority = type.getDeclaredMethod("playerLookPriority", org.bukkit.entity.Mob.class);
            priority.setAccessible(true);
            org.bukkit.entity.Cod cod = world.spawn(at, org.bukkit.entity.Cod.class, c -> c.setPersistent(false));
            Object zombiePriority = priority.invoke(awareness, looker);
            Object villagerPriority = priority.invoke(awareness, watcher);
            Object codPriority = priority.invoke(awareness, cod);
            cod.remove();
            expect(zombiePriority != null && Integer.valueOf(8).equals(villagerPriority) && codPriority == null,
                    "Mobs that look at players learn to look at the double (zombie " + zombiePriority
                            + ", villager " + villagerPriority + ", cod " + codPriority + ")");

            Mannequin body = world.spawn(at.clone().add(2, 0, 0), Mannequin.class, m -> m.setPersistent(false));
            Class<?> goalType = Class.forName("de.carl.minecraftchallenges.smp.spectate.LookAtDoubleGoal");
            var goalConstructor = goalType.getDeclaredConstructor(com.destroystokyo.paper.entity.ai.GoalKey.class,
                    org.bukkit.entity.Mob.class, java.util.function.Function.class, java.util.function.Predicate.class);
            goalConstructor.setAccessible(true);
            var key = com.destroystokyo.paper.entity.ai.GoalKey.of(org.bukkit.entity.Mob.class,
                    new org.bukkit.NamespacedKey(this, "smoke_look"));
            java.util.function.Function<org.bukkit.entity.Mob, Mannequin> nearest = mob -> body;
            java.util.function.Predicate<org.bukkit.entity.Player> noOne = player -> false;
            @SuppressWarnings("unchecked")
            var goal = (com.destroystokyo.paper.entity.ai.Goal<org.bukkit.entity.Mob>) goalConstructor.newInstance(
                    key, looker, nearest, noOne);
            boolean activates = false;
            for (int attempt = 0; attempt < 2000 && !activates; attempt++) activates = goal.shouldActivate();
            goal.start();
            goal.tick();
            boolean stays = goal.shouldStayActive();
            getServer().getMobGoals().addGoal(looker, 8, goal);
            boolean added = getServer().getMobGoals().hasGoal(looker, key);
            body.remove();
            expect(activates && stays && added, "A mob looks at the double now and then, like at an AFK player");
        } finally {
            watcher.remove();
            looker.remove();
            target.remove();
        }
    }

    private void expect(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        getLogger().info("PASS: " + message);
        checks++;
    }

    private static Object field(Object target, String name) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
