package de.carl.minecraftchallenges.smp;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.smp.afk.AfkTokenCommand;
import de.carl.minecraftchallenges.smp.afk.AfkTokenService;
import de.carl.minecraftchallenges.smp.claim.ClaimBorders;
import de.carl.minecraftchallenges.smp.claim.ClaimCommand;
import de.carl.minecraftchallenges.smp.claim.ClaimPermissionsMenu;
import de.carl.minecraftchallenges.smp.claim.ClaimProtectionListener;
import de.carl.minecraftchallenges.smp.claim.ClaimService;
import de.carl.minecraftchallenges.smp.economy.BuyLevelCommand;
import de.carl.minecraftchallenges.smp.economy.ItemValues;
import de.carl.minecraftchallenges.smp.economy.MarketService;
import de.carl.minecraftchallenges.smp.economy.PricesMenu;
import de.carl.minecraftchallenges.smp.economy.SellMenu;
import de.carl.minecraftchallenges.smp.economy.XpMarket;
import de.carl.minecraftchallenges.smp.npc.Artifacts;
import de.carl.minecraftchallenges.smp.npc.NpcCommand;
import de.carl.minecraftchallenges.smp.npc.NpcMenus;
import de.carl.minecraftchallenges.smp.npc.QuestService;
import de.carl.minecraftchallenges.smp.npc.SmpNpcService;
import de.carl.minecraftchallenges.smp.shop.DailyShop;
import de.carl.minecraftchallenges.smp.shop.ShopCommand;
import de.carl.minecraftchallenges.smp.shop.ShopMenu;
import de.carl.minecraftchallenges.smp.spectate.AdminSpectateService;
import de.carl.minecraftchallenges.smp.spectate.PositionCommand;
import de.carl.minecraftchallenges.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything that turns the SMP into its own game mode: the value list, the sell market, the daily
 * shop, land claims and the SMP's traders and quest givers.
 */
public final class SmpFeatures {
    private final MinecraftChallengesPlugin plugin;
    private final SmpService smp;
    private final Map<String, CommandExecutor> commands = new LinkedHashMap<>();
    private final MarketService market;
    private final DailyShop dailyShop;
    private final ClaimService claims;
    private final SmpNpcService npcs;
    private final QuestService quests;
    private final AdminSpectateService spectate;

    public SmpFeatures(MinecraftChallengesPlugin plugin, SmpService smp) throws IOException {
        this.plugin = plugin;
        this.smp = smp;
        Path data = smp.dataFolder();
        ItemValues values = loadValues();
        market = new MarketService(values, data.resolve("market.yml"), smp.writer(), plugin.getLogger());
        dailyShop = new DailyShop(values, data.resolve("dailyshop.yml"), smp.writer(), plugin.getLogger());
        smp.onReload(market::reload);
        smp.onReload(dailyShop::reload);

        ShopMenu shopMenu = new ShopMenu(plugin, smp, dailyShop);
        SellMenu sellMenu = new SellMenu(plugin, smp, market);
        PricesMenu pricesMenu = new PricesMenu(smp, market);
        register(shopMenu, sellMenu, pricesMenu);
        ShopCommand shopCommand = new ShopCommand(shopMenu, sellMenu, pricesMenu);
        commands.put("shop", shopCommand);
        commands.put("sell", shopCommand);
        commands.put("prices", shopCommand);

        XpMarket xpMarket = new XpMarket(values, plugin.getConfig().getConfigurationSection("smp.economy.buylevel"),
                data.resolve("xp-market.yml"), smp.writer(), plugin.getLogger());
        smp.onReload(xpMarket::reload);
        commands.put("buylevel", new BuyLevelCommand(smp, xpMarket));

        claims = new ClaimService(smp, data.resolve("claims.yml"), plugin.getConfig());
        smp.onReload(claims::reload);
        ClaimBorders borders = new ClaimBorders(plugin, smp, claims);
        ClaimPermissionsMenu permissionsMenu = new ClaimPermissionsMenu(claims);
        register(new ClaimProtectionListener(claims), permissionsMenu);
        commands.put("claim", new ClaimCommand(smp, claims, borders, permissionsMenu));

        npcs = new SmpNpcService(plugin, smp, values, claims, new Artifacts(plugin), data.resolve("npcs.yml"));
        quests = new QuestService(plugin, smp, npcs, data.resolve("quests.yml"));
        smp.onReload(npcs::reload);
        smp.onReload(quests::reload);
        smp.onFlush(() -> {
            npcs.flush();
            quests.flush();
        });
        register(npcs, quests, new NpcMenus(plugin, smp, npcs, quests));
        NpcCommand npcCommand = new NpcCommand(smp, npcs, quests);
        commands.put("quest", npcCommand);
        commands.put("smpnpc", npcCommand);

        spectate = new AdminSpectateService(plugin, smp);
        register(spectate);
        commands.put("pos", new PositionCommand(smp, spectate));
        commands.put("spectate", new de.carl.minecraftchallenges.smp.spectate.SpectateCommand(spectate));
        commands.put("craftingtable", new CraftingTableCommand(smp));

        AfkTokenService afkTokens = new AfkTokenService(plugin, smp, data.resolve("afktokens.yml"), smp.writer(),
                plugin.getConfig().getConfigurationSection("smp.afk-tokens"));
        smp.onReload(afkTokens::reload);
        commands.put("afktoken", new AfkTokenCommand(smp, afkTokens));

        market.onNewDay(this::announceNewDay);
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            market.tick();
            dailyShop.tick();
        }, 20L, 20L);
    }

    private ItemValues loadValues() throws IOException {
        Path file = plugin.getDataFolder().toPath().resolve("smp-values.yml");
        if (!Files.exists(file)) plugin.saveResource("smp-values.yml", false);
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException exception) {
            throw new IOException("smp-values.yml ist beschädigt: " + file, exception);
        }
        ItemValues values = ItemValues.load(yaml, plugin.getLogger()::warning);
        values.removeNonItems(plugin.getLogger()::warning);
        return values;
    }

    private void register(org.bukkit.event.Listener... listeners) {
        for (org.bukkit.event.Listener listener : listeners) {
            plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        }
    }

    private void announceNewDay() {
        List<Component> names = market.wanted().stream()
                .map(entry -> (Component) SmpText.name(entry.material()).color(NamedTextColor.YELLOW)).toList();
        Component text = Component.text("Ein neuer Tag: Der Shop hat neue Angebote (/shop).");
        if (!names.isEmpty()) {
            text = text.append(Component.text(" Heute gesucht bei /sell: "))
                    .append(Component.join(JoinConfiguration.commas(true), names))
                    .append(Component.text("."));
        }
        smp.broadcast(Messages.message(Messages.Topic.SMP, Messages.Type.INFO, text));
    }

    /** Names of all commands this class serves; they answer "not available" while the SMP is down. */
    public static List<String> commandNames() {
        return List.of("shop", "sell", "prices", "buylevel", "claim", "quest", "smpnpc", "pos", "spectate", "craftingtable", "afktoken");
    }

    public void registerCommands() {
        commands.forEach((name, executor) -> {
            PluginCommand command = plugin.getCommand(name);
            if (command == null) return;
            command.setExecutor(executor);
            if (executor instanceof TabCompleter completer) command.setTabCompleter(completer);
        });
    }

    public AdminSpectateService spectate() {
        return spectate;
    }

    public void shutdown() {
        spectate.shutdown();
        quests.shutdown();
        npcs.shutdown();
    }
}
