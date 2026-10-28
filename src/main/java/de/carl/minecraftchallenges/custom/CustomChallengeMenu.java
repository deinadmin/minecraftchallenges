package de.carl.minecraftchallenges.custom;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.challenge.ChallengeState;
import de.carl.minecraftchallenges.custom.CustomChallengeMenuHolder.View;
import de.carl.minecraftchallenges.lobby.GameLobbyRegistry;
import de.carl.minecraftchallenges.menu.ChallengeMenu;
import de.carl.minecraftchallenges.menu.MenuItems;
import de.carl.minecraftchallenges.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** List of player-made challenges, the creation view and the in-challenge config view. */
public final class CustomChallengeMenu {
    private static final int LIST_FIRST_ROW = 1;
    private static final int LIST_ROWS = 4;
    private static final int ROW_WIDTH = 7;
    private static final int LIST_BACK_SLOT = 48;
    private static final int CREATE_SLOT = 50;
    /** Each rule has its on/off dye directly below it. */
    private static final int[] RULE_SLOTS = {10, 11, 12, 13, 14, 15, 16, 28, 29, 30, 32, 33, 34};
    private static final int TOGGLE_OFFSET = 9;
    private static final int[] SPACER_SLOTS = {31, 40};
    private static final int GOAL_SLOT = 2;
    private static final int DURATION_SLOT = 4;
    private static final int PLAYERS_SLOT = 6;
    private static final int OWNER_SLOT = 47;
    private static final int SETTINGS_BACK_SLOT = 48;
    private static final int CONFIRM_SLOT = 50;
    private static final int START_SLOT = 49;
    private static final int REGENERATE_SLOT = 51;

    private final GameLobbyRegistry registry;
    private final ChallengeMenu mainMenu;
    private final CustomChallengeSlots slots;

    public CustomChallengeMenu(MinecraftChallengesPlugin plugin, GameLobbyRegistry registry, ChallengeMenu mainMenu,
                               CustomChallengeSlots slots) {
        this.registry = registry;
        this.mainMenu = mainMenu;
        this.slots = slots;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshOpenMenus, 20L, 20L);
    }

    // ---------------------------------------------------------------- opening

    public void openList(Player player) {
        CustomChallengeMenuHolder holder = new CustomChallengeMenuHolder(View.LIST, null, null);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Component.text("Eigene Challenges", NamedTextColor.DARK_GRAY));
        holder.setInventory(inventory);
        MenuItems.drawBorder(inventory);
        inventory.setItem(LIST_BACK_SLOT, backItem("Zur Spielauswahl"));
        render(player, holder);
        player.openInventory(inventory);
    }

    public void openCreate(Player player) {
        if (slots.isQueued(player)) {
            Messages.send(player, Messages.Type.ERROR, "Du bist bereits in der Warteschlange.");
            return;
        }
        CustomChallengeMenuHolder holder = new CustomChallengeMenuHolder(View.CREATE, new CustomSettings(), null);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Component.text("Challenge erstellen", NamedTextColor.DARK_GRAY));
        holder.setInventory(inventory);
        MenuItems.drawBorder(inventory);
        inventory.setItem(SETTINGS_BACK_SLOT, backItem("Zur Challenge-Liste"));
        render(player, holder);
        player.openInventory(inventory);
    }

    public void openConfig(Player player, CustomChallenge challenge) {
        if (!challenge.isActive()) return;
        CustomChallengeMenuHolder holder = new CustomChallengeMenuHolder(View.CONFIG, null, challenge);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Component.text("Challenge-Einstellungen", NamedTextColor.DARK_GRAY));
        holder.setInventory(inventory);
        MenuItems.drawBorder(inventory);
        render(player, holder);
        player.openInventory(inventory);
    }

    // ---------------------------------------------------------------- clicks

    public void handleClick(Player player, CustomChallengeMenuHolder holder, int rawSlot, ClickType click) {
        switch (holder.view()) {
            case LIST -> handleListClick(player, rawSlot);
            case CREATE -> handleCreateClick(player, holder, rawSlot, click);
            case CONFIG -> handleConfigClick(player, holder, rawSlot, click);
        }
    }

    private void handleListClick(Player player, int rawSlot) {
        if (rawSlot == LIST_BACK_SLOT) {
            mainMenu.open(player);
            return;
        }
        if (rawSlot == CREATE_SLOT) {
            openCreate(player);
            return;
        }
        CustomChallenge challenge = challengeAt(rawSlot);
        if (challenge == null) return;
        if (!challenge.isActive()) {
            openCreate(player);
            return;
        }
        player.closeInventory();
        registry.join(player, challenge);
    }

    private void handleCreateClick(Player player, CustomChallengeMenuHolder holder, int rawSlot, ClickType click) {
        CustomSettings draft = holder.draft();
        if (rawSlot == SETTINGS_BACK_SLOT) {
            openList(player);
            return;
        }
        if (rawSlot == CONFIRM_SLOT) {
            slots.submit(player, draft);
            return;
        }
        CustomRule rule = ruleAt(rawSlot);
        if (rule != null) draft.toggle(rule);
        else if (rawSlot == GOAL_SLOT) draft.cycleGoal(!click.isRightClick());
        else if (rawSlot == DURATION_SLOT) draft.cycleDuration(!click.isRightClick());
        else if (rawSlot == PLAYERS_SLOT) draft.changeMaxPlayers(playerDelta(click), 1);
        else return;
        render(player, holder);
    }

    private void handleConfigClick(Player player, CustomChallengeMenuHolder holder, int rawSlot, ClickType click) {
        CustomChallenge challenge = holder.challenge();
        if (!challenge.isActive()) {
            player.closeInventory();
            return;
        }
        if (rawSlot == REGENERATE_SLOT && challenge.canEdit(player)) {
            player.closeInventory();
            challenge.regenerate(player);
            return;
        }
        if (rawSlot == START_SLOT && challenge.canManage(player)) {
            if (challenge.isRunning()) {
                if (!click.isShiftClick()) {
                    Messages.send(player, Messages.Type.INFO, "Shift-Klick, um die Runde wirklich zu stoppen.");
                    return;
                }
                challenge.stop();
            } else {
                player.closeInventory();
                challenge.toggleStart(player);
                return;
            }
        } else {
            boolean editTarget = ruleAt(rawSlot) != null || rawSlot == GOAL_SLOT
                    || rawSlot == DURATION_SLOT || rawSlot == PLAYERS_SLOT;
            if (!editTarget) return;
            if (!challenge.canEdit(player)) {
                Messages.send(player, Messages.Type.ERROR, challenge.canManage(player)
                        ? "Einstellungen können nur vor dem Start geändert werden."
                        : "Nur die Challenge-Leitung und das Team können Einstellungen ändern.");
                return;
            }
            CustomRule rule = ruleAt(rawSlot);
            if (rule != null) challenge.toggleRule(player, rule);
            else if (rawSlot == GOAL_SLOT) challenge.cycleGoal(player, !click.isRightClick());
            else if (rawSlot == DURATION_SLOT) challenge.cycleDuration(player, !click.isRightClick());
            else challenge.changeMaxPlayers(player, playerDelta(click));
        }
        refreshConfigViews(challenge);
    }

    private static int playerDelta(ClickType click) {
        int step = click.isShiftClick() ? 5 : 1;
        return click.isRightClick() ? -step : step;
    }

    // ---------------------------------------------------------------- rendering

    private void refreshOpenMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof CustomChallengeMenuHolder holder)) {
                continue;
            }
            if (holder.view() == View.CONFIG && !holder.challenge().isActive()) player.closeInventory();
            else render(player, holder);
        }
    }

    private void refreshConfigViews(CustomChallenge challenge) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof CustomChallengeMenuHolder holder
                    && holder.view() == View.CONFIG && holder.challenge() == challenge) {
                render(player, holder);
            }
        }
    }

    private void render(Player viewer, CustomChallengeMenuHolder holder) {
        Inventory inventory = holder.getInventory();
        switch (holder.view()) {
            case LIST -> {
                Map<Integer, CustomChallenge> layout = listLayout();
                for (int row = LIST_FIRST_ROW; row < LIST_FIRST_ROW + LIST_ROWS; row++) {
                    for (int column = 1; column <= ROW_WIDTH; column++) {
                        int slot = row * 9 + column;
                        CustomChallenge challenge = layout.get(slot);
                        if (challenge != null) set(inventory, slot, listItem(viewer, challenge));
                        else inventory.setItem(slot, null);
                    }
                }
                set(inventory, CREATE_SLOT, createItem(viewer));
            }
            case CREATE -> {
                renderSettings(inventory, holder.draft(), true);
                set(inventory, CONFIRM_SLOT, confirmItem(viewer));
            }
            case CONFIG -> {
                CustomChallenge challenge = holder.challenge();
                if (!challenge.isActive()) return;
                renderSettings(inventory, challenge.settings(), challenge.canEdit(viewer));
                set(inventory, OWNER_SLOT, ownerItem(challenge));
                set(inventory, START_SLOT, challenge.canManage(viewer) ? startItem(challenge) : infoItem(challenge));
                set(inventory, REGENERATE_SLOT, challenge.canEdit(viewer) ? regenerateItem() : MenuItems.frame());
            }
        }
    }

    private void renderSettings(Inventory inventory, CustomSettings settings, boolean editable) {
        CustomRule[] rules = CustomRule.values();
        for (int index = 0; index < RULE_SLOTS.length && index < rules.length; index++) {
            boolean enabled = settings.has(rules[index]);
            set(inventory, RULE_SLOTS[index], ruleItem(rules[index], enabled, editable));
            set(inventory, RULE_SLOTS[index] + TOGGLE_OFFSET, toggleItem(rules[index], enabled, editable));
        }
        for (int slot : SPACER_SLOTS) set(inventory, slot, MenuItems.frame());
        set(inventory, GOAL_SLOT, goalItem(settings, editable));
        set(inventory, DURATION_SLOT, durationItem(settings, editable));
        set(inventory, PLAYERS_SLOT, playersItem(settings, editable));
    }

    private ItemStack ruleItem(CustomRule rule, boolean enabled, boolean editable) {
        List<Component> lore = new ArrayList<>();
        rule.description().forEach(line -> lore.add(Component.text(line, NamedTextColor.GRAY)));
        lore.add(Component.empty());
        lore.add(enabled ? Component.text("✔ Aktiv", NamedTextColor.GREEN)
                : Component.text("✘ Inaktiv", NamedTextColor.RED));
        if (editable) {
            lore.add(Component.text("» Klicken zum " + (enabled ? "Deaktivieren" : "Aktivieren"),
                    NamedTextColor.YELLOW));
        }
        ItemStack item = MenuItems.item(rule.icon(),
                Component.text(rule.displayName(), enabled ? NamedTextColor.GREEN : NamedTextColor.WHITE,
                        TextDecoration.BOLD), lore);
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(enabled);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack toggleItem(CustomRule rule, boolean enabled, boolean editable) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(rule.displayName(), NamedTextColor.GRAY));
        if (editable) {
            lore.add(Component.empty());
            lore.add(Component.text("» Klicken zum " + (enabled ? "Deaktivieren" : "Aktivieren"),
                    NamedTextColor.YELLOW));
        }
        return MenuItems.item(enabled ? Material.LIME_DYE : Material.RED_DYE,
                Component.text(enabled ? "✔ Aktiv" : "✘ Inaktiv", enabled ? NamedTextColor.GREEN : NamedTextColor.RED,
                        TextDecoration.BOLD), lore);
    }

    private ItemStack goalItem(CustomSettings settings, boolean editable) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(settings.goal().description(), NamedTextColor.GRAY));
        lore.add(Component.empty());
        for (CustomGoal goal : CustomGoal.values()) {
            boolean selected = goal == settings.goal();
            lore.add(Component.text((selected ? "▶ " : "  ") + goal.displayName(),
                    selected ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY));
        }
        if (editable) {
            lore.add(Component.empty());
            lore.add(Component.text("» Links-/Rechtsklick zum Wechseln", NamedTextColor.YELLOW));
        }
        return MenuItems.item(settings.goal().icon(),
                Component.text("Ziel: " + settings.goal().displayName(), NamedTextColor.GOLD, TextDecoration.BOLD),
                lore);
    }

    private ItemStack durationItem(CustomSettings settings, boolean editable) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(settings.goal() == CustomGoal.SURVIVE
                ? "Überleben braucht ein Zeitlimit."
                : "Ohne Ziel bis Ablauf ist die Runde verloren.", NamedTextColor.GRAY));
        if (editable) {
            lore.add(Component.empty());
            lore.add(Component.text("» Links-/Rechtsklick zum Ändern", NamedTextColor.YELLOW));
        }
        return MenuItems.item(Material.CLOCK,
                Component.text("Zeitlimit: " + settings.durationName(), NamedTextColor.AQUA, TextDecoration.BOLD),
                lore);
    }

    private ItemStack playersItem(CustomSettings settings, boolean editable) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Höchstens " + settings.maxPlayers() + " Spieler", NamedTextColor.GRAY));
        if (editable) {
            lore.add(Component.empty());
            lore.add(Component.text("» Links +1 · Rechts −1", NamedTextColor.YELLOW));
            lore.add(Component.text("» Shift: ±5", NamedTextColor.YELLOW));
        }
        ItemStack item = MenuItems.item(Material.PLAYER_HEAD,
                Component.text("Max. Spieler: " + settings.maxPlayers(), NamedTextColor.LIGHT_PURPLE,
                        TextDecoration.BOLD), lore);
        item.setAmount(settings.maxPlayers());
        return item;
    }

    private ItemStack ownerItem(CustomChallenge challenge) {
        ItemStack item = MenuItems.item(Material.PLAYER_HEAD,
                Component.text(challenge.displayName(), NamedTextColor.GOLD, TextDecoration.BOLD),
                List.of(Component.text("Status: ", NamedTextColor.GRAY)
                                .append(Component.text(status(challenge), NamedTextColor.WHITE)),
                        Component.text("Spieler: ", NamedTextColor.GRAY)
                                .append(Component.text(challenge.onlineParticipantCount() + "/"
                                        + challenge.maxPlayers(), NamedTextColor.WHITE))));
        if (challenge.ownerId() != null && item.getItemMeta() instanceof SkullMeta skull) {
            skull.setOwningPlayer(Bukkit.getOfflinePlayer(challenge.ownerId()));
            item.setItemMeta(skull);
        }
        return item;
    }

    private ItemStack startItem(CustomChallenge challenge) {
        return switch (challenge.state()) {
            case COUNTDOWN -> MenuItems.item(Material.BARRIER,
                    Component.text("Start abbrechen", NamedTextColor.RED, TextDecoration.BOLD),
                    List.of(Component.text("Der Countdown läuft.", NamedTextColor.GRAY)));
            case RUNNING -> MenuItems.item(Material.REDSTONE_BLOCK,
                    Component.text("Runde stoppen", NamedTextColor.RED, TextDecoration.BOLD),
                    List.of(Component.text("Beendet die laufende Runde.", NamedTextColor.GRAY),
                            Component.text("» Shift-Klick zum Stoppen", NamedTextColor.YELLOW)));
            default -> MenuItems.item(Material.EMERALD_BLOCK,
                    Component.text("Challenge starten", NamedTextColor.GREEN, TextDecoration.BOLD),
                    List.of(Component.text("Startet nach 10 Sekunden Countdown.", NamedTextColor.GRAY),
                            Component.text("Danach sind die Einstellungen gesperrt.", NamedTextColor.GRAY),
                            Component.empty(),
                            Component.text("» Klicken zum Starten", NamedTextColor.YELLOW, TextDecoration.BOLD)));
        };
    }

    private ItemStack regenerateItem() {
        return MenuItems.item(Material.GRASS_BLOCK,
                Component.text("Welt neu generieren", NamedTextColor.AQUA, TextDecoration.BOLD),
                List.of(Component.text("Erstellt Oberwelt, Nether und End", NamedTextColor.GRAY),
                        Component.text("mit einem neuen Zufalls-Seed.", NamedTextColor.GRAY),
                        Component.text("Regeln und Einstellungen bleiben erhalten.", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("» Klicken zum Neugenerieren", NamedTextColor.YELLOW, TextDecoration.BOLD)));
    }

    private ItemStack infoItem(CustomChallenge challenge) {
        return MenuItems.item(Material.BOOK,
                Component.text(status(challenge), NamedTextColor.YELLOW, TextDecoration.BOLD),
                List.of(Component.text("Nur die Challenge-Leitung und das", NamedTextColor.GRAY),
                        Component.text("Team können Regeln ändern und starten.", NamedTextColor.GRAY)));
    }

    private ItemStack listItem(Player viewer, CustomChallenge challenge) {
        if (!challenge.isActive()) {
            return MenuItems.item(Material.GRAY_DYE,
                    Component.text("Freier Platz", NamedTextColor.GRAY),
                    List.of(Component.text("Hier ist noch keine Challenge.", NamedTextColor.DARK_GRAY),
                            Component.text("» Klicken, um eine zu erstellen", NamedTextColor.YELLOW)));
        }
        CustomSettings settings = challenge.settings();
        int participants = challenge.onlineParticipantCount();
        boolean inside = challenge.presentPlayers().contains(viewer);
        boolean returning = challenge.isRoundActive() && challenge.isParticipant(viewer);
        boolean full = participants >= challenge.maxPlayers();

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Status: ", NamedTextColor.GRAY)
                .append(Component.text(status(challenge), NamedTextColor.WHITE)));
        lore.add(Component.text("Ziel: ", NamedTextColor.GRAY)
                .append(Component.text(settings.goal().displayName(), NamedTextColor.WHITE)));
        lore.add(Component.text("Spieler: ", NamedTextColor.GRAY)
                .append(Component.text(participants + "/" + challenge.maxPlayers(), NamedTextColor.WHITE)));
        lore.add(Component.text("Zeitlimit: ", NamedTextColor.GRAY)
                .append(Component.text(settings.durationName(), NamedTextColor.WHITE)));
        lore.add(Component.text("Regeln:", NamedTextColor.GRAY));
        if (settings.rules().isEmpty()) lore.add(Component.text(" • keine", NamedTextColor.DARK_GRAY));
        settings.rules().forEach(rule -> lore.add(Component.text(" • " + rule.displayName(), NamedTextColor.GREEN)));
        lore.add(Component.empty());
        if (inside) {
            lore.add(Component.text("Du bist in dieser Challenge", NamedTextColor.GREEN));
        } else if (challenge.state() == ChallengeState.REGENERATING || challenge.isCreating()) {
            lore.add(Component.text("Bitte einen Moment warten", NamedTextColor.GRAY));
        } else if (challenge.isRoundActive()) {
            lore.add(Component.text(returning ? "» Klicken, um wieder beizutreten" : "» Klicken zum Zuschauen",
                    NamedTextColor.YELLOW, TextDecoration.BOLD));
        } else if (full) {
            lore.add(Component.text("Diese Challenge ist voll", NamedTextColor.RED));
        } else {
            lore.add(Component.text("» Klicken zum Beitreten", NamedTextColor.YELLOW, TextDecoration.BOLD));
        }

        NamedTextColor color = challenge.isRoundActive() ? NamedTextColor.GOLD
                : full ? NamedTextColor.RED : NamedTextColor.GREEN;
        ItemStack item = MenuItems.item(settings.goal().icon(),
                Component.text(challenge.displayName(), color, TextDecoration.BOLD), lore);
        if (inside || returning) {
            ItemMeta meta = item.getItemMeta();
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createItem(Player viewer) {
        boolean queued = slots.wouldQueue(viewer);
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Wähle Regeln, Ziel, Zeitlimit", NamedTextColor.GRAY));
        lore.add(Component.text("und maximale Spielerzahl.", NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.addAll(availabilityLore(viewer));
        lore.add(Component.empty());
        lore.add(Component.text("» Klicken zum Erstellen", NamedTextColor.YELLOW, TextDecoration.BOLD));
        return MenuItems.item(queued ? Material.ORANGE_DYE : Material.LIME_DYE,
                Component.text("+ Neue Challenge", queued ? NamedTextColor.GOLD : NamedTextColor.GREEN,
                        TextDecoration.BOLD), lore);
    }

    private ItemStack confirmItem(Player viewer) {
        boolean queued = slots.wouldQueue(viewer);
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Erstellt eine neue Zufallswelt", NamedTextColor.GRAY));
        lore.add(Component.text("mit deinen Einstellungen.", NamedTextColor.GRAY));
        lore.add(Component.empty());
        lore.addAll(availabilityLore(viewer));
        lore.add(Component.empty());
        lore.add(Component.text(queued ? "» Klicken zum Anstellen" : "» Klicken zum Erstellen",
                NamedTextColor.YELLOW, TextDecoration.BOLD));
        return MenuItems.item(queued ? Material.CLOCK : Material.EMERALD_BLOCK,
                Component.text(queued ? "In die Warteschlange" : "Challenge erstellen",
                        queued ? NamedTextColor.GOLD : NamedTextColor.GREEN, TextDecoration.BOLD), lore);
    }

    private List<Component> availabilityLore(Player viewer) {
        if (slots.usesExtraSlot(viewer)) {
            return List.of(Component.text("Alle " + CustomChallenge.SLOT_COUNT + " Plätze sind belegt.", NamedTextColor.GRAY),
                    Component.text("Als Admin bekommst du einen Zusatzplatz.", NamedTextColor.AQUA));
        }
        if (!slots.wouldQueue(viewer)) {
            return List.of(Component.text("Ein Platz ist frei.", NamedTextColor.GREEN));
        }
        return List.of(Component.text("Alle " + CustomChallenge.SLOT_COUNT + " Plätze sind belegt.", NamedTextColor.GRAY),
                Component.text("Du wärst Platz " + (slots.queueSize() + 1) + " der Warteschlange.", NamedTextColor.GOLD),
                Component.text("Deine Challenge startet automatisch,", NamedTextColor.GRAY),
                Component.text("sobald ein Platz frei wird.", NamedTextColor.GRAY));
    }

    private static String status(CustomChallenge challenge) {
        if (challenge.isCreating()) return "Wird erstellt …";
        return switch (challenge.state()) {
            case WAITING -> "Wartet auf Start";
            case COUNTDOWN -> "Startet gleich";
            case RUNNING -> "Runde läuft";
            case FINISHED -> "Runde beendet";
            case REGENERATING -> "Welt wird neu generiert";
            case IDLE -> "Frei";
        };
    }

    private static ItemStack backItem(String target) {
        return MenuItems.item(Material.ARROW, Component.text("Zurück", NamedTextColor.YELLOW),
                List.of(Component.text(target, NamedTextColor.GRAY)));
    }

    private static void set(Inventory inventory, int slot, ItemStack item) {
        if (!item.equals(inventory.getItem(slot))) inventory.setItem(slot, item);
    }

    private CustomChallenge challengeAt(int rawSlot) {
        return listLayout().get(rawSlot);
    }

    /**
     * Regular slots form the first row, admin slots (only while a challenge lives in them) the rows
     * below. Every row is centered horizontally and the whole block vertically.
     */
    private Map<Integer, CustomChallenge> listLayout() {
        List<List<CustomChallenge>> rows = new ArrayList<>();
        rows.add(slots.regularSlots());
        List<CustomChallenge> extras = slots.extraSlots().stream().filter(CustomChallenge::isActive).toList();
        for (int start = 0; start < extras.size() && rows.size() < LIST_ROWS; start += ROW_WIDTH) {
            rows.add(extras.subList(start, Math.min(extras.size(), start + ROW_WIDTH)));
        }
        Map<Integer, CustomChallenge> layout = new HashMap<>();
        int firstRow = LIST_FIRST_ROW + (LIST_ROWS - rows.size()) / 2;
        for (int row = 0; row < rows.size(); row++) {
            List<CustomChallenge> entries = rows.get(row);
            int[] columns = centeredColumns(Math.min(entries.size(), ROW_WIDTH));
            for (int index = 0; index < columns.length; index++) {
                layout.put((firstRow + row) * 9 + columns[index], entries.get(index));
            }
        }
        return layout;
    }

    /** Columns 1–7; even counts leave the middle column free so the row stays symmetric. */
    private static int[] centeredColumns(int count) {
        int[] columns = new int[count];
        int center = 1 + ROW_WIDTH / 2;
        int first = center - count / 2;
        for (int index = 0; index < count; index++) {
            int column = first + index;
            if (count % 2 == 0 && column >= center) column++;
            columns[index] = column;
        }
        return columns;
    }

    private static CustomRule ruleAt(int rawSlot) {
        CustomRule[] rules = CustomRule.values();
        for (int index = 0; index < RULE_SLOTS.length && index < rules.length; index++) {
            if (RULE_SLOTS[index] == rawSlot || RULE_SLOTS[index] + TOGGLE_OFFSET == rawSlot) return rules[index];
        }
        return null;
    }
}
