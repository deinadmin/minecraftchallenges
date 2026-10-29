package de.carl.minecraftchallenges;

import de.carl.minecraftchallenges.challenge.ChallengeManager;
import de.carl.minecraftchallenges.challenge.ChallengeSessionService;
import de.carl.minecraftchallenges.custom.CustomChallenge;
import de.carl.minecraftchallenges.custom.CustomChallengeListener;
import de.carl.minecraftchallenges.custom.CustomChallengeMenu;
import de.carl.minecraftchallenges.custom.CustomChallengeSlots;
import de.carl.minecraftchallenges.listener.ChallengeListener;
import de.carl.minecraftchallenges.listener.LobbyListener;
import de.carl.minecraftchallenges.jumpnrun.JumpAndRunListener;
import de.carl.minecraftchallenges.jumpnrun.JumpAndRunService;
import de.carl.minecraftchallenges.lobby.GameLobbyRegistry;
import de.carl.minecraftchallenges.lobby.ItemRestrictionService;
import de.carl.minecraftchallenges.lobby.LobbyIsolationService;
import de.carl.minecraftchallenges.menu.ChallengeMenu;
import de.carl.minecraftchallenges.menu.AdminMenu;
import de.carl.minecraftchallenges.menu.LobbyMenu;
import de.carl.minecraftchallenges.rank.RankService;
import de.carl.minecraftchallenges.npc.NpcService;
import de.carl.minecraftchallenges.randomizer.RandomizerChallenge;
import de.carl.minecraftchallenges.randomizer.RandomizerChallengeListener;
import de.carl.minecraftchallenges.randomizer.RandomizerType;
import de.carl.minecraftchallenges.randomizer.SpongeWorldListener;
import de.carl.minecraftchallenges.smp.BackupCommand;
import de.carl.minecraftchallenges.smp.BackupService;
import de.carl.minecraftchallenges.smp.SmpCommands;
import de.carl.minecraftchallenges.smp.SmpListener;
import de.carl.minecraftchallenges.smp.SmpService;
import de.carl.minecraftchallenges.smp.SpawnProtectionListener;
import de.carl.minecraftchallenges.smp.shop.ShopCommand;
import de.carl.minecraftchallenges.smp.shop.ShopMenu;
import de.carl.minecraftchallenges.smp.shop.ShopService;
import de.carl.minecraftchallenges.util.LobbyService;
import de.carl.minecraftchallenges.util.Messages;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.List;
import java.util.logging.Level;

public final class MinecraftChallengesPlugin extends JavaPlugin {
    private LobbyService lobbyService;
    private GameLobbyRegistry lobbies;
    private JumpAndRunService jumpAndRunService;
    private SmpService smp;
    private BackupService backups;
    private ShopMenu shopMenu;
    private NpcService npcs;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        lobbyService = new LobbyService(this);
        RankService rankService = new RankService(this, lobbyService);
        ChallengeSessionService sessions = new ChallengeSessionService();
        lobbies = new GameLobbyRegistry(this, lobbyService, sessions);
        try {
            for (int lobbyId = 1; lobbyId <= GameLobbyRegistry.LOBBY_COUNT; lobbyId++) {
                lobbies.register(new ChallengeManager(this, lobbyService, rankService, sessions, lobbyId));
            }
            for (RandomizerType type : RandomizerType.values()) {
                for (int lobbyId = 1; lobbyId <= GameLobbyRegistry.LOBBY_COUNT; lobbyId++) {
                    RandomizerChallenge challenge = new RandomizerChallenge(this, lobbyService, rankService,
                            sessions, type, lobbyId);
                    lobbies.register(challenge);
                    if (type == RandomizerType.SPONGE) {
                        SpongeWorldListener spongeListener = new SpongeWorldListener(this, challenge::overworld);
                        getServer().getPluginManager().registerEvents(spongeListener, this);
                        challenge.onWorldsInitialized(spongeListener::scanInitiallyLoadedChunks);
                    }
                }
            }
            for (int slot = 1; slot <= CustomChallenge.SLOT_COUNT; slot++) {
                registerCustomSlot(rankService, sessions, slot);
            }
        } catch (IllegalStateException exception) {
            getLogger().severe("Lobbys konnten nicht angelegt werden: " + exception.getMessage());
            lobbies = null;
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        lobbies.startIdleMonitor();
        GameLobbyRegistry registry = lobbies;
        Messages.setChallengeLocator(player -> registry.instance(player.getWorld()) != null);

        try {
            smp = new SmpService(this, lobbyService);
            backups = new BackupService(this, smp);
            SmpService smpService = smp;
            Messages.setSmpLocator(player -> smpService.isSmpWorld(player.getWorld()));
            getServer().getPluginManager().registerEvents(new SmpListener(this, smp, lobbyService, lobbies), this);
            getServer().getPluginManager().registerEvents(new SpawnProtectionListener(this, smp), this);
            ShopService shop = new ShopService(getDataFolder().toPath().resolve("shop.yml"), smp.writer(), getLogger());
            shopMenu = new ShopMenu(this, smp, shop);
            getServer().getPluginManager().registerEvents(shopMenu, this);
        } catch (IOException | IllegalStateException exception) {
            getLogger().log(Level.SEVERE, "Das SMP konnte nicht gestartet werden und bleibt deaktiviert.", exception);
            smp = null;
            backups = null;
            shopMenu = null;
        }

        jumpAndRunService = new JumpAndRunService(this, lobbyService);
        ChallengeMenu challengeMenu = new ChallengeMenu(lobbies, jumpAndRunService, smp);
        LobbyMenu lobbyMenu = new LobbyMenu(this, lobbies);
        CustomChallengeSlots customSlots = new CustomChallengeSlots(this, lobbyService, rankService, lobbies, slot -> {
            try {
                return registerCustomSlot(rankService, sessions, slot);
            } catch (IllegalStateException exception) {
                getLogger().severe("Zusätzlicher Challenge-Platz " + slot + " konnte nicht angelegt werden: "
                        + exception.getMessage());
                return null;
            }
        });
        getServer().getPluginManager().registerEvents(customSlots, this);
        CustomChallengeMenu customMenu = new CustomChallengeMenu(this, lobbies, challengeMenu, customSlots);
        npcs = new NpcService(this, lobbyService, lobbies,
                challengeMenu, lobbyMenu, customMenu);
        getServer().getPluginManager().registerEvents(npcs, this);
        PluginCommand npcCommand = getCommand("npc");
        if (npcCommand != null) npcCommand.setExecutor(npcs);
        AdminMenu adminMenu = new AdminMenu();
        LobbyIsolationService isolation = new LobbyIsolationService(this, lobbyService, lobbies, smp);

        getServer().getPluginManager().registerEvents(
                new LobbyListener(this, lobbyService, lobbies, challengeMenu, lobbyMenu, customMenu), this);
        getServer().getPluginManager().registerEvents(
                new CustomChallengeListener(this, lobbyService, lobbies, customMenu), this);
        getServer().getPluginManager().registerEvents(
                new ChallengeListener(this, lobbyService, lobbies, adminMenu), this);
        getServer().getPluginManager().registerEvents(
                new JumpAndRunListener(jumpAndRunService), this);
        getServer().getPluginManager().registerEvents(rankService, this);
        getServer().getPluginManager().registerEvents(isolation, this);
        getServer().getPluginManager().registerEvents(
                new ItemRestrictionService(this, lobbyService, lobbies), this);
        getServer().getPluginManager().registerEvents(
                new RandomizerChallengeListener(this, lobbyService, lobbies), this);

        PluginCommand command = getCommand("challenge");
        if (command != null) {
            ChallengeCommand executor = new ChallengeCommand(this, lobbyService, lobbies, challengeMenu);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        PluginCommand lobbyCommand = getCommand("lobby");
        if (lobbyCommand != null) {
            lobbyCommand.setExecutor(new LobbyCommand(lobbyService, lobbies));
        }
        PluginCommand editCommand = getCommand("edit");
        if (editCommand != null) {
            editCommand.setExecutor(new EditCommand(lobbyService, smp));
        }
        HelpCommand help = new HelpCommand(lobbyService, lobbies, smp);
        getServer().getPluginManager().registerEvents(help, this);
        PluginCommand helpCommand = getCommand("help");
        if (helpCommand != null) helpCommand.setExecutor(help);
        registerSmpCommands();
        PluginCommand jumpAndRunCommand = getCommand("jumpnrun");
        if (jumpAndRunCommand != null) {
            JumpAndRunCommand executor = new JumpAndRunCommand(lobbyService, jumpAndRunService);
            jumpAndRunCommand.setExecutor(executor);
            jumpAndRunCommand.setTabCompleter(executor);
        }

        getServer().getOnlinePlayers().forEach(lobbyService::sendToLobby);
        isolation.refreshAll();
        getLogger().info("MinecraftChallenges ist bereit (" + GameLobbyRegistry.LOBBY_COUNT
                + " Lobbys pro Spiel).");
    }

    private CustomChallenge registerCustomSlot(RankService rankService, ChallengeSessionService sessions, int slot) {
        CustomChallenge custom = new CustomChallenge(this, lobbyService, rankService, sessions, slot);
        lobbies.register(custom);
        SpongeWorldListener spongeListener = new SpongeWorldListener(this, custom::spongeOverworld);
        getServer().getPluginManager().registerEvents(spongeListener, this);
        custom.onSpongeRuleEnabled(spongeListener::scanInitiallyLoadedChunks);
        return custom;
    }

    private void registerSmpCommands() {
        SmpCommands smpCommands = smp == null ? null : new SmpCommands(smp);
        BackupCommand backupCommand = backups == null ? null : new BackupCommand(backups);
        ShopCommand shopCommand = shopMenu == null ? null : new ShopCommand(shopMenu);
        for (String name : List.of("sethome", "home", "spawn", "pay", "money", "eco", "setspawn", "ec", "backup", "shop")) {
            PluginCommand command = getCommand(name);
            if (command == null) continue;
            boolean missing = switch (name) {
                case "backup" -> backupCommand == null;
                case "shop" -> shopCommand == null;
                default -> smpCommands == null;
            };
            if (missing) {
                command.setExecutor((sender, ignored, label, args) -> {
                    Messages.sendSystem(sender, Messages.Type.ERROR, "Das SMP ist gerade nicht verfügbar.");
                    return true;
                });
                continue;
            }
            if (name.equals("backup")) {
                command.setExecutor(backupCommand);
                command.setTabCompleter(backupCommand);
            } else if (name.equals("shop")) {
                command.setExecutor(shopCommand);
                command.setTabCompleter(shopCommand);
            } else {
                command.setExecutor(smpCommands);
                command.setTabCompleter(smpCommands);
            }
        }
    }

    @Override
    public void onDisable() {
        if (npcs != null) npcs.shutdown();
        if (backups != null) backups.shutdown();
        if (smp != null) smp.shutdown();
        if (jumpAndRunService != null) {
            jumpAndRunService.clearAll();
        }
        if (lobbyService != null) {
            lobbyService.shutdownEditing();
        }
        if (lobbies != null) lobbies.shutdown();
    }
}
