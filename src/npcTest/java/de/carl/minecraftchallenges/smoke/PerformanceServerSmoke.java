package de.carl.minecraftchallenges.smoke;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.challenge.ChallengeManager;
import de.carl.minecraftchallenges.custom.CustomChallenge;
import de.carl.minecraftchallenges.custom.CustomSettings;
import de.carl.minecraftchallenges.custom.CustomRule;
import de.carl.minecraftchallenges.lobby.GameLobbyRegistry;
import de.carl.minecraftchallenges.lobby.GameType;
import de.carl.minecraftchallenges.randomizer.RandomizerChallenge;
import de.carl.minecraftchallenges.speedrun.SpeedrunRun;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.EquipmentSlot;
import java.util.concurrent.TimeUnit;
import de.carl.minecraftchallenges.util.WorldLoader;
import de.carl.minecraftchallenges.util.WorldFiles;
import de.carl.minecraftchallenges.challenge.ChallengeState;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit opt-in integration checks. Run only in a disposable Paper server directory. */
public final class PerformanceServerSmoke extends JavaPlugin {
    private int checks;
    private MinecraftChallengesPlugin challenges;
    private GameLobbyRegistry registry;

    @Override public void onEnable() {
        if (!Boolean.getBoolean("minecraftchallenges.performance-smoke")) {
            getLogger().severe("Refusing smoke tests without -Dminecraftchallenges.performance-smoke=true");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        challenges = (MinecraftChallengesPlugin) getServer().getPluginManager().getPlugin("MinecraftChallenges");
        registry = (GameLobbyRegistry) field(challenges, "lobbies");
        Bukkit.getScheduler().runTask(this, () -> {
            try { runChecks().whenComplete((ignored, error) -> finish(error)); }
            catch (Throwable error) { finish(error); }
        });
        Bukkit.getScheduler().runTaskLater(this, () -> finish(new AssertionError("Smoke test timed out")), 20 * 180);
    }

    private CompletableFuture<Void> runChecks() {
        ChallengeManager mob = (ChallengeManager) registry.lobby(GameType.MOB_HUNT, 1);
        RandomizerChallenge block = (RandomizerChallenge) registry.lobby(GameType.BLOCK_DROP, 1);
        RandomizerChallenge sponge = (RandomizerChallenge) registry.lobby(GameType.SPONGE, 1);
        CustomChallenge custom = (CustomChallenge) registry.lobby(GameType.CUSTOM, 1);
        var mobReady = mob.prepare();
        expect(mob.prepare() == mobReady, "Repeated joins share pending world preparation");
        var blockReady = block.prepare();
        var spongeReady = sponge.prepare();
        UUID ownerId = UUID.randomUUID();
        Player owner = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> ownerId;
                    case "getName" -> "SmokeOwner";
                    default -> throw new AssertionError("Unexpected owner access " + method);
                });
        var customReady = custom.create(owner, new CustomSettings());
        expect(!custom.isAvailable() && custom.isCreating(), "Custom slot is reserved before queued creation starts");
        expect(custom.prepare() == customReady, "Joining a queued custom challenge shares its creation future");
        return CompletableFuture.allOf(mobReady, blockReady, spongeReady, customReady).thenCompose(ignored -> {
            expect(mobReady.join() && blockReady.join() && spongeReady.join() && customReady.join(), "All queued challenge types become ready");
            expect(block.nether() == null && block.end() == null && sponge.nether() == null && sponge.end() == null,
                    "Standard randomizers load only the overworld initially");
            World mobWorld = (World) field(mob, "challengeWorld");
            expect(mobWorld.getPluginChunkTickets().getOrDefault(challenges, java.util.List.of()).size() == 49, "Mob waiting area has 49 tickets");
            expect(block.overworld().getPluginChunkTickets().getOrDefault(challenges, java.util.List.of()).size() == 49, "Randomizer waiting area has 49 tickets");
            NamespacedKey spongeKey = new NamespacedKey(challenges, "sponge_terrain_transformed");
            expect(sponge.overworld().getPluginChunkTickets().getOrDefault(challenges, java.util.List.of()).stream().allMatch(chunk ->
                    chunk.getPersistentDataContainer().has(spongeKey, PersistentDataType.BYTE)),
                    "Sponge waiting area is fully converted before preparation completes");
            invoke(mob, "beginRound");
            invoke(block, "beginRound");
            invoke(custom, "beginRound");
            expect(mobWorld.getPluginChunkTickets().getOrDefault(challenges, java.util.List.of()).isEmpty(), "Mob round releases waiting tickets");
            expect(block.overworld().getPluginChunkTickets().getOrDefault(challenges, java.util.List.of()).isEmpty(), "Randomizer round releases waiting tickets");
            World customWorld = (World) field(custom, "world");
            expect(customWorld.getPluginChunkTickets().getOrDefault(challenges, java.util.List.of()).size() == 49,
                    "A custom round with no participants stays waiting and retains its tickets");
            checkCustomReconnect(custom, customWorld);
            WorldLoader.releaseSpawn(challenges, customWorld);
            expect(customWorld.getPluginChunkTickets().getOrDefault(challenges, java.util.List.of()).isEmpty(),
                    "Custom world tickets use the same releasable lifecycle");
            WorldLoader.retainSpawn(challenges, mobWorld);
            expect(mobWorld.getPluginChunkTickets().getOrDefault(challenges, java.util.List.of()).size() == 49, "Waiting tickets can be reacquired without world generation");
            var nether = block.portalTarget(World.Environment.NETHER);
            var duplicate = block.portalTarget(World.Environment.NETHER);
            return CompletableFuture.allOf(nether, duplicate).thenCompose(done -> {
                expect(nether.join() != null && duplicate.join().getWorld() == nether.join().getWorld(), "Concurrent portal requests share a dimension");
                expect(block.readyPortalWorld(World.Environment.NETHER) == nether.join().getWorld(), "Prepared dimension is available for vanilla portal routing");
                return block.portalTarget(World.Environment.THE_END);
            }).thenCompose(end -> {
                expect(end != null && end.getWorld().getEnvironment() == World.Environment.THE_END, "End loads on first use");
                Path marker = block.nether().getWorldFolder().toPath().resolve("smoke-old-world.txt");
                try { Files.writeString(marker, "old dimension"); }
                catch (Exception error) { throw new IllegalStateException(error); }
                // Unload before resetting: lazy dimensions must be deleted even when not loaded.
                World oldNether = block.nether();
                expect(Bukkit.unloadWorld(oldNether, true), "A dimension can be unloaded before reset");
                setField(block, "nether", null);
                @SuppressWarnings("unchecked")
                CompletableFuture<Void> deletion = (CompletableFuture<Void>) invoke(block, "recreateWorlds");
                return deletion.thenCompose(done -> {
                    expect(!Files.exists(marker), "Regeneration removes unloaded dimension files too");
                    expect(block.readyPortalWorld(World.Environment.NETHER) == null, "Reset clears dimension readiness");
                    return block.prepare();
                }).thenApply(ready -> {
                    expect(ready && block.nether() == null && block.end() == null, "Regeneration recreates only the overworld");
                    return null;
                });
            }).thenCompose(done -> {
                CompletableFuture<Void> gate = new CompletableFuture<>();
                challenges.worldPreparation().submit(() -> gate);
                var stalePortal = block.portalTarget(World.Environment.NETHER);
                @SuppressWarnings("unchecked")
                CompletableFuture<Void> deletion = (CompletableFuture<Void>) invoke(block, "recreateWorlds");
                return deletion.thenCompose(ignoredDeletion -> {
                    gate.complete(null);
                    return stalePortal;
                }).thenCompose(target -> {
                    expect(target == null && block.nether() == null, "A queued portal cannot recreate a dimension after its round was reset");
                    return block.prepare();
                });
            }).thenCompose(done -> {
                World customWorldForPortal = (World) field(custom, "world");
                setField(custom, "state", ChallengeState.RUNNING);
                return custom.portalTarget(customWorldForPortal.getSpawnLocation(), PlayerTeleportEvent.TeleportCause.END_PORTAL)
                        .thenAccept(target -> {
                            expect(target != null && target.getWorld().getEnvironment() == World.Environment.THE_END,
                                    "Custom End preparation works through the shared queue");
                            expect(target.getWorld().getSeed() == customWorldForPortal.getSeed(), "Custom dimensions preserve their overworld seed");
                            expect(target.clone().subtract(0, 1, 0).getBlock().getType() == Material.OBSIDIAN,
                                    "Custom End arrival platform is preserved");
                        });
            }).thenCompose(done -> {
                expect(WorldFiles.deleteUnloadedWorldAsync(challenges, "world", Bukkit.getWorlds().getFirst().getWorldFolder().toPath())
                        .isCompletedExceptionally(), "World cleanup refuses the primary world");
                Path outside = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize().resolveSibling("unsafe_dimension");
                expect(WorldFiles.deleteUnloadedWorldAsync(challenges, "unsafe_dimension", outside).isCompletedExceptionally(),
                        "World cleanup refuses paths outside the test server");
                var chunk = mobWorld.getChunkAt(mobWorld.getSpawnLocation());
                // Ordinary terrain is scanned as well; this patch ensures actual edits are required.
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
                    chunk.getBlock(x, 200, z).setType(Material.DIRT, false);
                var converted = challenges.spongeTerrain().transform(chunk, () -> true);
                expect(!converted.isDone(), "Sponge conversion is deferred and budgeted");
                return converted.thenRun(() -> expect(chunk.getBlock(0, 200, 0).getType() == Material.SPONGE,
                        "Budgeted conversion still applies the complete terrain rule"));
            }).thenCompose(done -> {
                var chunk = mobWorld.getChunkAt((mobWorld.getSpawnLocation().getBlockX() >> 4) + 1,
                        mobWorld.getSpawnLocation().getBlockZ() >> 4);
                AtomicBoolean enabled = new AtomicBoolean(true);
                var cancelled = challenges.spongeTerrain().transform(chunk, enabled::get);
                enabled.set(false);
                return cancelled.handle((value, error) -> {
                    expect(error != null, "Disabling a sponge world cancels its outstanding work");
                    return null;
                });
            }).thenCompose(done -> {
                // Advance the counters directly: validate the real lifecycle without a five-minute wait.
                setField(block, "state", ChallengeState.RUNNING);
                World occupiedDimension = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("getPlayers")) return java.util.List.of(owner);
                            throw new AssertionError("Unexpected world access " + method);
                        });
                World oldBlockEnd = (World) field(block, "end");
                setField(block, "end", occupiedDimension);
                setField(block, "emptyRoundSeconds", 299);
                expect(!(boolean) invoke(block, "checkEmptyRoundTimeout") && (int) field(block, "emptyRoundSeconds") == 0,
                        "Any player in another dimension resets the standard round's empty timeout");
                setField(block, "end", oldBlockEnd);
                setField(block, "emptyRoundSeconds", 298);
                expect(!(boolean) invoke(block, "checkEmptyRoundTimeout"),
                        "An unlimited standard round remains open after 299 empty seconds");
                expect((boolean) invoke(block, "checkEmptyRoundTimeout"),
                        "An unlimited standard round closes at 300 empty seconds");
                expect(block.state() == ChallengeState.REGENERATING,
                        "Closing the empty standard round enters normal regeneration");
                setField(custom, "state", ChallengeState.RUNNING);
                World oldCustomEnd = (World) field(custom, "end");
                setField(custom, "end", occupiedDimension);
                setField(custom, "idleSeconds", 299);
                custom.tickIdle(120);
                expect(custom.state() == ChallengeState.RUNNING && (int) field(custom, "idleSeconds") == 0,
                        "Any player in another dimension resets the custom round's empty timeout");
                setField(custom, "end", oldCustomEnd);
                setField(custom, "idleSeconds", 298);
                setField(custom, "emptyRoundSeconds", 59);
                invoke(custom, "tickRound");
                expect(custom.state() == ChallengeState.RUNNING,
                        "Unlimited empty custom rounds bypass the old one-minute defeat");
                custom.tickIdle(120);
                expect(custom.state() == ChallengeState.RUNNING,
                        "Unlimited custom rounds bypass the old 15-second deletion and stay open through 299 seconds");
                custom.tickIdle(120);
                expect(custom.state() == ChallengeState.IDLE,
                        "An unlimited custom round is deleted at 300 empty seconds");
                return (CompletableFuture<Void>) field(custom, "pendingDeletion");
            }).thenCompose(done -> checkSpeedrunContest());
        });
    }

    @SuppressWarnings("unchecked")
    private void checkCustomReconnect(CustomChallenge custom, World world) {
        var settings = (CustomSettings) field(custom, "settings");
        settings.toggle(CustomRule.ONE_HEART);
        setField(custom, "state", ChallengeState.RUNNING);
        var runner = new ReconnectingPlayer(world.getSpawnLocation().add(1.25, 0, 0));
        var participants = (Set<UUID>) field(custom, "participants");
        participants.add(runner.id);
        Location savedLocation = runner.location.clone();
        World lobby = Bukkit.getWorlds().getFirst();

        custom.playerLeft(runner.player, true); // Disconnect while injured after earning more hearts.
        expect(runner.maxHealth == 20.0 && runner.health == 20.0, "Leaving resets challenge health for the lobby");
        runner.location = lobby.getSpawnLocation();
        custom.playerLeft(runner.player, true); // Login routes the player from the old world to the lobby.
        custom.select(runner.player);
        expect(runner.maxHealth == 6.0 && runner.health == 1.0 && runner.location.equals(savedLocation),
                "Reconnecting restores earned hearts, damage, and the exact logout location");

        runner.health = 3.0;
        runner.location = lobby.getSpawnLocation();
        custom.playerLeft(runner.player, true); // /lobby during the next visit, without a disconnect.
        custom.select(runner.player);
        expect(runner.maxHealth == 6.0 && runner.health == 3.0,
                "Returning again restores the latest run state");

        settings.toggle(CustomRule.SHARED_HEALTH);
        runner.location = lobby.getSpawnLocation();
        custom.playerLeft(runner.player, true);
        setField(custom, "sharedBaseMaxHealth", 8.0);
        setField(custom, "sharedHealth", 2.0);
        custom.select(runner.player);
        expect(runner.maxHealth == 8.0 && runner.health == 2.0,
                "A returning teammate gets the current shared health and heart capacity");

        custom.playerLeft(runner.player, true);
        participants.remove(runner.id);
        settings.toggle(CustomRule.SHARED_HEALTH);
        settings.toggle(CustomRule.ONE_HEART);
        setField(custom, "state", ChallengeState.WAITING);
    }

    private CompletableFuture<Void> checkSpeedrunContest() {
        var speedruns = registry.lobbies(GameType.SPEEDRUN);
        expect(speedruns.size() == 6 && speedruns.stream().allMatch(run -> run.maxPlayers() == 1),
                "The contest registers six isolated solo runs");
        var contest = field(challenges, "speedruns");
        World lobby = Bukkit.getWorld("lobby");
        var runner = new ReconnectingPlayer(lobby.getSpawnLocation());
        runner.maxHealth = 20;
        runner.health = 20;
        invoke(contest, "quickStart", new Class<?>[] {Player.class}, runner.player);
        var run = (SpeedrunRun) speedruns.getFirst();
        expect(run.state() == ChallengeState.WAITING && run.isParticipant(runner.player),
                "Quick start immediately reserves a run for its owner");
        invoke(contest, "quickStart", new Class<?>[] {Player.class}, runner.player);
        expect(speedruns.stream().filter(SpeedrunRun.class::isInstance).filter(game -> game.isRoundActive()).count() == 1,
                "Repeated quick start never reserves a second run");
        return ((CompletableFuture<?>) field(run, "preparation")).thenCompose(ignored -> {
            // The startup callback owns entry; defer until it has processed the prepared world.
            var entered = new CompletableFuture<Void>();
            Bukkit.getScheduler().runTask(this, () -> entered.complete(null));
            return entered;
        }).thenCompose(ignored -> {
            World world = runner.player.getWorld();
            expect(run.state() == ChallengeState.WAITING && registry.instance(world) == run
                            && runner.mode == GameMode.ADVENTURE && world.getWorldBorder().getSize() == 9.0,
                    "A fresh run starts in Adventure inside a nine-block waiting border");
            expect(runner.hotbar[3].getType() == Material.RED_BED && runner.hotbar[5].getType() == Material.CLOCK
                            && runner.hotbar[5].getItemMeta().displayName().toString().contains("Stoppuhr starten"),
                    "The waiting hotbar has the lobby bed and named start clock on either side of center");
            var block = new BlockBreakEvent(runner.location.getBlock(), runner.player);
            invoke(contest, "onBreak", new Class<?>[] {BlockBreakEvent.class}, block);
            expect(block.isCancelled(), "The waiting phase protects blocks");
            var click = new PlayerInteractEvent(runner.player, Action.RIGHT_CLICK_AIR, runner.hotbar[5],
                    null, org.bukkit.block.BlockFace.SELF, EquipmentSlot.HAND);
            invoke(contest, "onInteract", new Class<?>[] {PlayerInteractEvent.class}, click);
            expect(run.state() == ChallengeState.COUNTDOWN && runner.mode == GameMode.ADVENTURE
                            && (long) field(run, "started") == 0L,
                    "Right-clicking the clock starts the countdown without starting the stopwatch");
            var startedRun = new CompletableFuture<Void>();
            Bukkit.getScheduler().runTaskLater(this, () -> startedRun.complete(null), 61L);
            return startedRun;
        }).thenCompose(ignored -> {
            var border = runner.player.getWorld().getWorldBorder();
            double openingSize = border.getSize();
            expect(runner.titles.equals(List.of("3", "2", "1", "LOS!"))
                            && runner.mode == GameMode.SURVIVAL && runner.hotbar[3] == null && runner.hotbar[5] == null
                            && openingSize > 9 && openingSize < 320,
                    "After 3-2-1, the border expands smoothly while Survival begins");
            var opened = new CompletableFuture<Void>();
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (border.getSize() > 1_000_000) opened.complete(null);
                else opened.completeExceptionally(new AssertionError("The border should be removed after six seconds"));
            }, 120L);
            return opened;
        }).thenCompose(ignored -> {
            expect(run.state() == ChallengeState.RUNNING && registry.instance(runner.player.getWorld()) == run,
                    "The runner starts in a fresh registered Overworld");
            long started = (long) field(run, "started");
            World overworld = runner.player.getWorld();
            expect(overworld.getSeed() == (long) field(run, "seed"), "The Overworld uses the random run seed");
            var spectator = new ReconnectingPlayer(lobby.getSpawnLocation());
            invoke(run, "spectate", new Class<?>[] {Player.class}, spectator.player);
            expect(spectator.mode == GameMode.SPECTATOR && spectator.player.getWorld() == overworld
                    && !run.isParticipant(spectator.player), "Spectators enter the runner's world as viewers only");

            runner.health = 9;
            Location departure = runner.location.clone();
            invoke(run, "rememberExit", new Class<?>[] {Player.class, Location.class}, runner.player, departure);
            runner.location = lobby.getSpawnLocation();
            invoke(contest, "onWorldChange", new Class<?>[] {PlayerChangedWorldEvent.class},
                    new PlayerChangedWorldEvent(runner.player, overworld));
            registry.offerRejoin(runner.player);
            expect(runner.lastMessage != null && runner.lastMessage.toString().contains("/challenge rejoin"),
                    "Returning to the hub offers a clickable rejoin command");
            registry.rejoin(runner.player);
            expect(runner.location.equals(departure) && runner.health == 9 && (long) field(run, "started") == started,
                    "Rejoin restores the runner's state without resetting the clock");

            @SuppressWarnings("unchecked")
            var end = (CompletableFuture<World>) invoke(run, "dimension",
                    new Class<?>[] {World.Environment.class}, World.Environment.THE_END);
            return end.thenAccept(world -> {
                expect(registry.instance(world) == run && world.getSeed() == overworld.getSeed()
                                && world.getEnderDragonBattle() != null,
                        "The run has its own seeded End and a dragon battle");
                setField(run, "lastActivity", System.nanoTime() - TimeUnit.MINUTES.toNanos(5));
                run.tickIdle(120);
                expect(run.state() == ChallengeState.REGENERATING, "Five minutes of inactivity end the run");

                var offline = (SpeedrunRun) speedruns.get(1);
                setField(offline, "runner", UUID.randomUUID());
                setField(offline, "state", ChallengeState.RUNNING);
                setField(offline, "started", System.nanoTime() - TimeUnit.MINUTES.toNanos(6));
                setField(offline, "absentSince", System.nanoTime() - TimeUnit.MINUTES.toNanos(5));
                offline.tickIdle(120);
                expect(offline.state() == ChallengeState.REGENERATING, "Five minutes away end the run");
                var records = field(contest, "records");
                expect(((List<?>) invoke(records, "ranked")).isEmpty(), "Expired runs are never ranked");

                var player = UUID.randomUUID();
                var rival = UUID.randomUUID();
                Class<?>[] resultTypes = {UUID.class, String.class, long.class};
                invoke(records, "finish", resultTypes, player, "Runner", 85_000L);
                invoke(records, "finish", resultTypes, rival, "Rival", 73_000L);
                invoke(records, "finish", resultTypes, player, "Runner", 90_000L);
                var ranking = (List<?>) invoke(records, "ranked");
                expect(invoke(ranking.getFirst(), "player").equals(rival)
                        && invoke(ranking.get(1), "millis").equals(85_000L),
                        "Fastest players rank first and slower attempts preserve the personal best");
                expect(Files.isRegularFile(challenges.getDataFolder().toPath().resolve("speedrun-records.yml")),
                        "Ranked records are persisted on disk");
            });
        });
    }

    private static final class ReconnectingPlayer {
        final UUID id = UUID.randomUUID();
        Location location;
        double maxHealth = 6.0;
        double health = 1.0;
        GameMode mode = GameMode.SURVIVAL;
        final ItemStack[] hotbar = new ItemStack[9];
        final java.util.ArrayList<String> titles = new java.util.ArrayList<>();
        net.kyori.adventure.text.Component lastMessage;
        final AttributeInstance attribute = stub(AttributeInstance.class, (proxy, method, args) -> switch (method.getName()) {
            case "getBaseValue", "getValue" -> maxHealth;
            case "getDefaultValue" -> 20.0;
            case "setBaseValue" -> { maxHealth = (double) args[0]; yield null; }
            default -> throw new AssertionError(method);
        });
        final PlayerInventory inventory = stub(PlayerInventory.class, (proxy, method, args) -> switch (method.getName()) {
            case "getContents", "getArmorContents", "getExtraContents" -> new ItemStack[0];
            case "getHeldItemSlot" -> 0;
            case "clear" -> { java.util.Arrays.fill(hotbar, null); yield null; }
            case "setItem" -> { hotbar[(int) args[0]] = (ItemStack) args[1]; yield null; }
            case "setContents", "setArmorContents", "setExtraContents", "setHeldItemSlot" -> null;
            default -> throw new AssertionError(method);
        });
        final Player player = stub(Player.class, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "getName" -> "SmokeRunner";
            case "isOnline" -> true;
            case "getRespawnLocation" -> null;
            case "setRespawnLocation" -> null;
            case "getEnderChest" -> inventory;
            case "getWorld" -> location.getWorld();
            case "getLocation" -> location.clone();
            case "teleport" -> { location = ((Location) args[0]).clone(); yield true; }
            case "getGameMode" -> mode;
            case "setGameMode" -> { mode = (GameMode) args[0]; yield null; }
            case "getInventory" -> inventory;
            case "getAttribute" -> attribute;
            case "getHealth" -> health;
            case "setHealth" -> {
                health = (double) args[0];
                if (health > maxHealth) throw new AssertionError("Health exceeds maximum");
                yield null;
            }
            case "getActivePotionEffects" -> List.of();
            case "addPotionEffects" -> true;
            case "isDead" -> false;
            case "getAbsorptionAmount" -> 0.0;
            case "getSaturation", "getExhaustion", "getExp", "getFallDistance" -> 0.0f;
            case "getFoodLevel" -> 20;
            case "getLevel", "getTotalExperience", "getFireTicks" -> 0;
            case "getRemainingAir", "getMaximumAir" -> 300;
            case "setAbsorptionAmount", "setFoodLevel", "setSaturation", "setExhaustion", "setFireTicks",
                 "setLevel", "setExp", "setTotalExperience", "setRemainingAir", "setFallDistance",
                 "setAllowFlight", "setFlying", "setScoreboard", "closeInventory",
                 "sendActionBar", "showBossBar", "hideBossBar" -> null;
            case "showTitle" -> {
                var title = (net.kyori.adventure.title.Title) args[0];
                titles.add(((net.kyori.adventure.text.TextComponent) title.title()).content());
                yield null;
            }
            case "clearTitle" -> null;
            case "sendMessage" -> {
                if (args[0] instanceof net.kyori.adventure.text.Component message) lastMessage = message;
                yield null;
            }
            default -> throw new AssertionError(method);
        });

        ReconnectingPlayer(Location location) {
            this.location = location;
        }

        private static <T> T stub(Class<T> type, java.lang.reflect.InvocationHandler handler) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
        }
    }

    private void finish(Throwable error) {
        if (error == null) getLogger().info("PERFORMANCE_SMOKE_PASSED: " + checks + " checks");
        else { getLogger().severe("PERFORMANCE_SMOKE_FAILED: " + error); error.printStackTrace(); }
        Bukkit.shutdown();
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
    private static void setField(Object target, String name, Object value) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static Object invoke(Object target, String name) {
        return invoke(target, name, new Class<?>[0]);
    }

    private static Object invoke(Object target, String name, Class<?>[] parameters, Object... arguments) {
        try {
            var method = target.getClass().getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method.invoke(target, arguments);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
