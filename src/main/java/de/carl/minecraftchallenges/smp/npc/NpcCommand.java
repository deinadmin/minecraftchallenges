package de.carl.minecraftchallenges.smp.npc;

import de.carl.minecraftchallenges.smp.SmpService;
import de.carl.minecraftchallenges.smp.SmpText;
import de.carl.minecraftchallenges.util.Messages;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** /quest for players and /smpnpc for admins. */
public final class NpcCommand implements CommandExecutor, TabCompleter {
    public static final String ADMIN_PERMISSION = "minecraftchallenges.command.smpnpc";

    private final SmpService smp;
    private final SmpNpcService npcs;
    private final QuestService quests;

    public NpcCommand(SmpService smp, SmpNpcService npcs, QuestService quests) {
        this.smp = smp;
        this.npcs = npcs;
        this.quests = quests;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.sendSystem(sender, Messages.Type.ERROR, "Dieser Befehl ist nur für Spieler.");
            return true;
        }
        if (command.getName().equalsIgnoreCase("quest")) quest(player, args);
        else admin(player, args);
        return true;
    }

    // ---------------------------------------------------------------- /quest

    private void quest(Player player, String[] args) {
        QuestService.Progress progress = quests.progress(player.getUniqueId());
        QuestSpec quest = progress == null ? null : quests.quest(progress);
        if (quest == null) {
            info(player, "Du hast gerade keine Quest. Quests bekommst du von den Bewohnern besonderer Orte: "
                    + "Netherfestungen, Bastionen, Endstädte, Monumente, Tempel, Dörfer und mehr.");
            return;
        }
        SmpNpc npc = npcs.find(progress.npc());
        String giver = npc == null ? "deinem Auftraggeber" : npc.title();
        if (args.length > 0 && args[0].equalsIgnoreCase("cancel")) {
            boolean finished = progress.count() >= quest.amount();
            quests.cancel(player);
            info(player, finished ? "Quest verworfen." : "Quest abgebrochen. " + giver + " gibt dir erst später wieder eine Aufgabe.");
            return;
        }
        if (progress.count() >= quest.amount()) {
            Messages.send(player, Messages.Type.SUCCESS, "Quest erfüllt: " + quest.text() + ". Hol dir die Belohnung bei "
                    + giver + where(npc) + ".");
            return;
        }
        info(player, "Deine Quest: " + quest.text() + " · " + progress.count() + "/" + quest.amount() + " · noch "
                + QuestService.clock(progress.remainingSeconds()) + " Minuten. Auftraggeber: " + giver + where(npc)
                + ". Abbrechen: /quest cancel");
    }

    private static String where(SmpNpc npc) {
        return npc == null ? "" : " (" + (int) Math.floor(npc.x()) + ", " + (int) Math.floor(npc.y()) + ", " + (int) Math.floor(npc.z()) + ")";
    }

    // ---------------------------------------------------------------- /smpnpc

    private void admin(Player player, String[] args) {
        if (!player.hasPermission(ADMIN_PERMISSION)) {
            Messages.sendSystem(player, Messages.Type.ERROR, "Dafür hast du keine Rechte.");
            return;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) {
            list(player);
            return;
        }
        if (!smp.isActive(player)) {
            error(player, "SMP-NPCs verwaltest du im SMP.");
            return;
        }
        Location here = player.getLocation();
        Location spot = new Location(here.getWorld(), here.getBlockX() + 0.5, here.getY(), here.getBlockZ() + 0.5);
        switch (sub) {
            case "trader" -> {
                long hours = Math.max(1, smp.plugin().getConfig().getInt("smp.npcs.traders.lifetime-hours-min", 24));
                SmpNpc npc = npcs.createTrader(spot, hours * 3_600_000L);
                success(player, npc.title() + " steht jetzt hier und bleibt " + hours + " Stunden.");
            }
            case "quest" -> {
                NpcArchetype archetype = args.length < 2 ? null : NpcArchetype.get(args[1].toLowerCase(Locale.ROOT));
                if (archetype == null || archetype.trader()) {
                    error(player, "Verwendung: /smpnpc quest <" + String.join("|", givers().toList()) + ">");
                    return;
                }
                SmpNpc npc = npcs.createQuestGiver(archetype, spot, null);
                success(player, npc.title() + " steht jetzt hier mit " + npc.offers().size() + " Quests.");
            }
            case "remove", "reroll" -> {
                SmpNpc npc = npcs.near(here, 6);
                if (npc == null) {
                    error(player, "Im Umkreis von 6 Blöcken steht kein SMP-NPC.");
                } else if (sub.equals("remove")) {
                    npcs.remove(npc);
                    success(player, npc.title() + " wurde entfernt.");
                } else {
                    npcs.reroll(npc);
                    success(player, npc.title() + " hat ein neues Angebot.");
                }
            }
            default -> {
                info(player, "/smpnpc trader – Wanderhändler an deiner Position");
                info(player, "/smpnpc quest <Typ> – Quest-NPC an deiner Position");
                info(player, "/smpnpc reroll · remove – nächster NPC im Umkreis von 6 Blöcken");
                info(player, "/smpnpc list – alle SMP-NPCs");
            }
        }
    }

    private void list(Player player) {
        List<SmpNpc> all = npcs.all();
        if (all.isEmpty()) {
            info(player, "Es gibt noch keine SMP-NPCs. Quest-NPCs erscheinen, sobald Spieler ihre Bauwerke entdecken.");
            return;
        }
        info(player, all.size() + " SMP-NPCs:");
        for (SmpNpc npc : all) {
            String extra = npc.trader()
                    ? "zieht weiter in " + SmpText.duration(Math.max(0, (npc.expiresAt() - System.currentTimeMillis()) / 60_000) * 60)
                    : npc.offers().size() + " Quests";
            info(player, "• " + npc.title() + " · " + npc.world() + where(npc) + " · " + extra);
        }
    }

    private static Stream<String> givers() {
        return NpcArchetype.all().stream().filter(archetype -> !archetype.trader()).map(NpcArchetype::key);
    }

    private static void info(Player player, String text) {
        Messages.send(player, Messages.Type.INFO, text);
    }

    private static void success(Player player, String text) {
        Messages.send(player, Messages.Type.SUCCESS, text);
    }

    private static void error(Player player, String text) {
        Messages.send(player, Messages.Type.ERROR, text);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("quest")) {
            return args.length == 1 ? filter(Stream.of("cancel"), args[0]) : List.of();
        }
        if (!sender.hasPermission(ADMIN_PERMISSION)) return List.of();
        if (args.length == 1) return filter(Stream.of("trader", "quest", "reroll", "remove", "list"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("quest")) return filter(givers(), args[1]);
        return List.of();
    }

    private static List<String> filter(Stream<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.filter(option -> option.startsWith(lower)).sorted().toList();
    }
}
