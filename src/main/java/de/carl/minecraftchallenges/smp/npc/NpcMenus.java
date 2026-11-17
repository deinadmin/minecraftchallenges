package de.carl.minecraftchallenges.smp.npc;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.menu.MenuItems;
import de.carl.minecraftchallenges.smp.SmpEconomy;
import de.carl.minecraftchallenges.smp.SmpService;
import de.carl.minecraftchallenges.smp.SmpText;
import de.carl.minecraftchallenges.smp.shop.ShopMenu;
import de.carl.minecraftchallenges.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** What opens when a player right-clicks an SMP NPC: a trader's wares or a quest giver's quests. */
public final class NpcMenus implements Listener {
    private static final int[][] STOCK_SLOTS = {
            {13}, {12, 14}, {11, 13, 15}, {10, 12, 14, 16}, {11, 12, 13, 14, 15}, {10, 11, 12, 14, 15, 16}};
    private static final int[] QUEST_SLOTS = {11, 13, 15};
    private static final int TRADER_INFO_SLOT = 22;
    private static final int QUEST_INFO_SLOT = 31;

    private final SmpService smp;
    private final SmpNpcService npcs;
    private final QuestService quests;

    public NpcMenus(MinecraftChallengesPlugin plugin, SmpService smp, SmpNpcService npcs, QuestService quests) {
        this.smp = smp;
        this.npcs = npcs;
        this.quests = quests;
        npcs.onInteract(this::open);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshOpenMenus, 20L, 20L);
    }

    public void open(Player player, SmpNpc npc) {
        NpcHolder holder = new NpcHolder(npc.id());
        holder.inventory = Bukkit.createInventory(holder, npc.trader() ? 27 : 36,
                Component.text(npc.title(), NamedTextColor.DARK_GRAY));
        render(player, holder);
        player.openInventory(holder.inventory);
        // A trader stands still while his wares are open, like a villager.
        npcs.hold(npc.id());
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 0.6f, 1.0f);
    }

    private void refreshOpenMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof NpcHolder holder) {
                npcs.hold(holder.npc);
                render(player, holder);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof NpcHolder holder)) return;
        UUID closed = event.getPlayer().getUniqueId();
        // The closing player still counts as a viewer during this event, so look one tick later.
        Bukkit.getScheduler().runTask(smp.plugin(), () -> {
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (!other.getUniqueId().equals(closed)
                        && other.getOpenInventory().getTopInventory().getHolder() instanceof NpcHolder open
                        && open.npc.equals(holder.npc)) {
                    return;
                }
            }
            npcs.release(holder.npc);
        });
    }

    private void render(Player viewer, NpcHolder holder) {
        SmpNpc npc = npcs.find(holder.npc);
        if (npc == null) {
            // The trader moved on while the menu was open.
            viewer.closeInventory();
            return;
        }
        if (npc.trader()) renderTrader(viewer, npc, holder.inventory);
        else renderQuests(viewer, npc, holder.inventory);
    }

    private static void set(Inventory inventory, int slot, ItemStack item) {
        if (!item.equals(inventory.getItem(slot))) inventory.setItem(slot, item);
    }

    private static void frame(Inventory inventory, List<Integer> used) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (!used.contains(slot)) set(inventory, slot, MenuItems.frame());
        }
    }

    // ---------------------------------------------------------------- trader

    private static int[] stockSlots(SmpNpc npc) {
        return STOCK_SLOTS[Math.max(1, Math.min(STOCK_SLOTS.length, npc.stock().size())) - 1];
    }

    private void renderTrader(Player viewer, SmpNpc npc, Inventory inventory) {
        SmpEconomy economy = smp.economy();
        long balance = economy.balance(viewer.getUniqueId());
        int[] slots = stockSlots(npc);
        List<Integer> used = new ArrayList<>(List.of(TRADER_INFO_SLOT));
        for (int index = 0; index < slots.length && index < npc.stock().size(); index++) {
            SmpNpc.Stock stock = npc.stock().get(index);
            List<Component> lore = new ArrayList<>();
            lore.add(SmpText.gray("Preis: ").append(SmpText.plain(economy.format(stock.price()),
                    balance >= stock.price() ? NamedTextColor.GREEN : NamedTextColor.RED)));
            lore.add(Component.empty());
            lore.add(npc.hasBought(viewer.getUniqueId(), index)
                    ? SmpText.plain("Bereits gekauft", NamedTextColor.RED)
                    : SmpText.plain("Klicken zum Kaufen", NamedTextColor.YELLOW));
            lore.add(SmpText.plain("Nur 1× pro Spieler", NamedTextColor.DARK_GRAY));
            set(inventory, slots[index], SmpText.withLore(stock.item().clone(), lore));
            used.add(slots[index]);
        }
        long seconds = Math.max(0, (npc.expiresAt() - System.currentTimeMillis()) / 1000);
        set(inventory, TRADER_INFO_SLOT, MenuItems.item(Material.GOLD_INGOT,
                SmpText.plain("Kontostand: " + economy.format(balance), NamedTextColor.GOLD),
                List.of(SmpText.gray("Seltene Waren, günstiger als ihr Wert."),
                        SmpText.gray("Zieht weiter in " + SmpText.duration(seconds / 60 * 60)))));
        frame(inventory, used);
    }

    private void buy(Player player, SmpNpc npc, int index) {
        SmpNpc.Stock stock = npc.stock().get(index);
        SmpEconomy economy = smp.economy();
        UUID uuid = player.getUniqueId();
        if (npc.hasBought(uuid, index)) {
            Messages.send(player, Messages.Type.ERROR, "Das hast du bei " + npc.archetype().name() + " schon gekauft.");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }
        ItemStack item = stock.item().clone();
        if (ShopMenu.freeSpace(player, item) < item.getAmount()) {
            Messages.send(player, Messages.Type.ERROR, "Dafür ist in deinem Inventar nicht genug Platz.");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }
        if (!economy.withdraw(uuid, stock.price(), "Händler " + npc.archetype().name() + ": " + item.getType().name())) {
            Messages.send(player, Messages.Type.ERROR, "Dafür reicht dein Kontostand nicht ("
                    + economy.format(economy.balance(uuid)) + ").");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }
        npc.markBought(uuid, index);
        npcs.save();
        player.getInventory().addItem(item).values()
                .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
        Messages.send(player, Messages.Type.SUCCESS, Component.text("Gekauft: "
                + (item.getAmount() > 1 ? item.getAmount() + "× " : "")).append(item.effectiveName())
                .append(Component.text(" für " + economy.format(stock.price()) + ".")));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.8f, 1.0f);
        smp.refreshScoreboard(uuid);
    }

    // ---------------------------------------------------------------- quests

    private void renderQuests(Player viewer, SmpNpc npc, Inventory inventory) {
        UUID uuid = viewer.getUniqueId();
        List<Integer> used = new ArrayList<>(List.of(QUEST_INFO_SLOT));
        for (int index = 0; index < QUEST_SLOTS.length && index < npc.offers().size(); index++) {
            SmpNpc.Offer offer = npc.offers().get(index);
            set(inventory, QUEST_SLOTS[index], questIcon(uuid, npc, offer));
            set(inventory, QUEST_SLOTS[index] + 9, rewardIcon(offer));
            used.add(QUEST_SLOTS[index]);
            used.add(QUEST_SLOTS[index] + 9);
        }
        set(inventory, QUEST_INFO_SLOT, MenuItems.item(Material.BOOK,
                SmpText.plain("So laufen Quests", NamedTextColor.GOLD),
                List.of(SmpText.gray("Oben die Aufgabe, darunter die Belohnung."),
                        SmpText.gray("Es zählt nur, was du nach der Annahme"),
                        SmpText.gray("selbst erledigst. Du hast immer nur"),
                        SmpText.gray("eine Quest gleichzeitig."),
                        Component.empty(),
                        SmpText.gray("Jede Belohnung gibt es einmal pro Spieler."),
                        SmpText.gray("Scheitern oder Abbrechen sperrt nur"),
                        SmpText.gray("diesen Auftraggeber für einen Tag."),
                        Component.empty(),
                        SmpText.plain("Stand und Abbruch: /quest", NamedTextColor.DARK_GRAY))));
        frame(inventory, used);
    }

    private ItemStack questIcon(UUID viewer, SmpNpc npc, SmpNpc.Offer offer) {
        QuestSpec quest = offer.quest();
        List<Component> lore = new ArrayList<>();
        lore.add(SmpText.gray("Zeit: ").append(SmpText.plain(quest.minutes() + " Minuten", NamedTextColor.WHITE)));
        lore.add(SmpText.gray("Schwierigkeit: ").append(SmpText.plain("★".repeat(quest.tier()) + "☆".repeat(3 - quest.tier()),
                quest.tier() == 1 ? NamedTextColor.GREEN : quest.tier() == 2 ? NamedTextColor.YELLOW : NamedTextColor.RED)));
        if (quest.handIn() != null) lore.add(SmpText.gray("Am Ende wirfst du die Items vor mir auf den Boden."));
        lore.add(Component.empty());
        QuestService.Progress progress = quests.progress(viewer);
        switch (quests.status(viewer, npc, quest)) {
            case AVAILABLE -> lore.add(SmpText.plain("Klicken zum Annehmen", NamedTextColor.YELLOW));
            case ACTIVE -> {
                lore.add(SmpText.plain("Läuft: " + progress.count() + "/" + quest.amount() + " · noch "
                        + QuestService.clock(progress.remainingSeconds()), NamedTextColor.AQUA));
                lore.add(SmpText.plain("Shift-Klick: abbrechen", NamedTextColor.DARK_GRAY));
            }
            case READY -> {
                if (quest.handIn() == null) {
                    lore.add(SmpText.plain("Erfüllt! Klicken für die Belohnung", NamedTextColor.GREEN));
                } else {
                    lore.add(SmpText.plain("Erfüllt! Wirf noch " + (quest.amount() - progress.delivered()) + "× ", NamedTextColor.GREEN)
                            .append(SmpText.name(quest.handIn())).append(SmpText.plain(" vor mir auf den Boden.", NamedTextColor.GREEN)));
                    lore.add(SmpText.gray("Was ich schon genommen habe, behalte ich."));
                }
            }
            case DONE -> lore.add(SmpText.plain("✔ Abgeschlossen", NamedTextColor.GREEN));
            case LOCKED -> lore.add(SmpText.plain("Gesperrt für " + SmpText.duration(quests.lockedSeconds(viewer, npc)), NamedTextColor.RED));
            case BUSY -> lore.add(SmpText.plain("Du hast schon eine andere Quest (/quest).", NamedTextColor.RED));
        }
        ItemStack icon = MenuItems.item(quest.icon(), SmpText.plain(quest.text(), NamedTextColor.GOLD), lore);
        icon.editMeta(meta -> meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP));
        return icon;
    }

    private static ItemStack rewardIcon(SmpNpc.Offer offer) {
        return SmpText.withLore(offer.reward().clone(), List.of(SmpText.plain("Belohnung für die Quest darüber", NamedTextColor.YELLOW)));
    }

    private void questClick(Player player, SmpNpc npc, SmpNpc.Offer offer, boolean shift) {
        UUID uuid = player.getUniqueId();
        switch (quests.status(uuid, npc, offer.quest())) {
            case AVAILABLE -> quests.accept(player, npc, offer);
            case READY -> quests.claim(player, npc, offer);
            case ACTIVE -> {
                if (shift && quests.cancel(player)) {
                    Messages.send(player, Messages.Type.INFO, "Quest abgebrochen. " + npc.title()
                            + " gibt dir erst später wieder eine Aufgabe.");
                } else {
                    player.sendActionBar(SmpText.plain("Die Quest läuft. Abbrechen mit Shift-Klick.", NamedTextColor.GRAY));
                }
            }
            case BUSY -> deny(player, "Du hast schon eine andere Quest. Stand und Abbruch: /quest");
            case LOCKED -> deny(player, npc.title() + " gibt dir in " + SmpText.duration(quests.lockedSeconds(uuid, npc))
                    + " wieder eine Aufgabe.");
            case DONE -> deny(player, "Diese Quest hast du schon abgeschlossen.");
        }
    }

    private static void deny(Player player, String text) {
        Messages.send(player, Messages.Type.ERROR, text);
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
    }

    // ---------------------------------------------------------------- clicks

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof NpcHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        SmpNpc npc = npcs.find(holder.npc);
        if (npc == null || !smp.isActive(player) || smp.isEditing(player)) {
            player.closeInventory();
            return;
        }
        int slot = event.getRawSlot();
        if (npc.trader()) {
            int[] slots = stockSlots(npc);
            for (int index = 0; index < slots.length && index < npc.stock().size(); index++) {
                if (slots[index] == slot) buy(player, npc, index);
            }
        } else {
            for (int index = 0; index < QUEST_SLOTS.length && index < npc.offers().size(); index++) {
                if (QUEST_SLOTS[index] == slot) questClick(player, npc, npc.offers().get(index), event.isShiftClick());
            }
        }
        if (player.getOpenInventory().getTopInventory().getHolder() == holder) render(player, holder);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof NpcHolder) event.setCancelled(true);
    }

    private static final class NpcHolder implements InventoryHolder {
        private final UUID npc;
        private Inventory inventory;

        private NpcHolder(UUID npc) {
            this.npc = npc;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
