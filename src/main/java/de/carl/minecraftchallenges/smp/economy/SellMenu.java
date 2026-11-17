package de.carl.minecraftchallenges.smp.economy;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.menu.MenuItems;
import de.carl.minecraftchallenges.smp.SmpEconomy;
import de.carl.minecraftchallenges.smp.SmpService;
import de.carl.minecraftchallenges.smp.SmpText;
import de.carl.minecraftchallenges.smp.economy.ItemValues.Entry;
import de.carl.minecraftchallenges.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /sell: the player puts items into the upper four rows, sees what the market pays right now and
 * confirms. Everything that is not sold goes back to the player when the menu closes.
 */
public final class SellMenu implements Listener {
    private static final int SIZE = 45;
    private static final int ITEM_SLOTS = 36;
    private static final int CANCEL_SLOT = 38;
    private static final int INFO_SLOT = 40;
    private static final int CONFIRM_SLOT = 42;
    private static final int MAX_INFO_LINES = 12;

    private final MinecraftChallengesPlugin plugin;
    private final SmpService smp;
    private final MarketService market;
    private final NamespacedKey controlKey;

    public SellMenu(MinecraftChallengesPlugin plugin, SmpService smp, MarketService market) {
        this.plugin = plugin;
        this.smp = smp;
        this.market = market;
        this.controlKey = new NamespacedKey(plugin, "sell_control");
    }

    public void open(Player player) {
        if (!smp.isActive(player) || smp.isEditing(player)) {
            Messages.send(player, Messages.Type.ERROR, smp.isEditing(player)
                    ? "Im Bearbeitungsmodus kannst du nichts verkaufen."
                    : "Verkaufen kannst du nur im SMP.");
            return;
        }
        SellHolder holder = new SellHolder();
        Inventory inventory = Bukkit.createInventory(holder, SIZE, Component.text("Verkaufen", NamedTextColor.DARK_GRAY));
        holder.inventory = inventory;
        for (int slot = ITEM_SLOTS; slot < SIZE; slot++) inventory.setItem(slot, control(MenuItems.frame()));
        inventory.setItem(CANCEL_SLOT, control(MenuItems.item(Material.RED_STAINED_GLASS_PANE,
                SmpText.plain("Abbrechen", NamedTextColor.RED),
                List.of(SmpText.gray("Du bekommst alle Items zurück.")))));
        render(player, holder);
        player.openInventory(inventory);
    }

    /** Menu buttons carry a marker, so no player item ever stacks onto them. */
    private ItemStack control(ItemStack item) {
        item.editMeta(meta -> meta.getPersistentDataContainer().set(controlKey, PersistentDataType.BYTE, (byte) 1));
        return item;
    }

    // ---------------------------------------------------------------- contents

    private record Contents(Map<Entry, Double> units, List<Integer> sellableSlots, int unsellableStacks) { }

    private Contents contents(Inventory inventory) {
        Map<Entry, Double> units = new LinkedHashMap<>();
        List<Integer> slots = new ArrayList<>();
        int unsellable = 0;
        for (int slot = 0; slot < ITEM_SLOTS; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty()) continue;
            // Renamed, enchanted or damaged items are never bought.
            ItemValues.Sale sale = item.hasItemMeta() ? null : market.values().sale(item.getType());
            if (sale == null) {
                unsellable++;
                continue;
            }
            units.merge(sale.base(), sale.units() * item.getAmount(), Double::sum);
            slots.add(slot);
        }
        return new Contents(units, slots, unsellable);
    }

    private void render(Player player, SellHolder holder) {
        Inventory inventory = holder.inventory;
        Contents contents = contents(inventory);
        MarketService.Quote quote = market.quote(player.getUniqueId(), contents.units());
        SmpEconomy economy = smp.economy();

        List<Component> info = new ArrayList<>();
        if (quote.lines().isEmpty()) {
            info.add(SmpText.gray("Lege Items in die oberen Felder."));
            info.add(SmpText.gray("Du kannst sie jederzeit wieder herausnehmen."));
            info.add(Component.empty());
            info.add(SmpText.plain("Kurse und gesuchte Items: /prices", NamedTextColor.DARK_GRAY));
        } else {
            int shown = 0;
            for (MarketService.Line line : quote.lines()) {
                if (shown++ == MAX_INFO_LINES) {
                    info.add(SmpText.gray("… und " + (quote.lines().size() - MAX_INFO_LINES) + " weitere"));
                    break;
                }
                Component text = SmpText.gray(SmpText.decimal(line.units()) + "× ")
                        .append(SmpText.name(line.base().material()).color(NamedTextColor.WHITE))
                        .append(SmpText.gray(" → "))
                        .append(SmpText.plain(SmpText.decimal(line.coins()), NamedTextColor.GOLD));
                if (line.bonusUnits() > 0) text = text.append(SmpText.plain(" ★", NamedTextColor.YELLOW));
                info.add(text);
            }
            info.add(Component.empty());
            info.add(SmpText.plain("★ = heute gesucht, doppelter Preis", NamedTextColor.DARK_GRAY));
            info.add(SmpText.plain("Je mehr verkauft wird, desto tiefer der Kurs.", NamedTextColor.DARK_GRAY));
        }
        if (contents.unsellableStacks() > 0) {
            info.add(Component.empty());
            info.add(SmpText.plain(contents.unsellableStacks() + (contents.unsellableStacks() == 1
                    ? " Stapel ist" : " Stapel sind") + " nicht verkäuflich und bleiben liegen.", NamedTextColor.RED));
        }
        inventory.setItem(INFO_SLOT, control(MenuItems.item(Material.GOLD_INGOT,
                SmpText.plain("Erlös: " + economy.format(quote.total()), NamedTextColor.GOLD), info)));

        boolean sellable = quote.total() > 0;
        inventory.setItem(CONFIRM_SLOT, control(MenuItems.item(
                sellable ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE,
                SmpText.plain(sellable ? "Verkaufen für " + economy.format(quote.total()) : "Nichts zu verkaufen",
                        sellable ? NamedTextColor.GREEN : NamedTextColor.GRAY),
                sellable ? List.of(SmpText.gray("Klicken zum Bestätigen")) : List.of())));
    }

    private void renderLater(Player player, SellHolder holder) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.getOpenInventory().getTopInventory().getHolder() == holder) render(player, holder);
        });
    }

    // ---------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof SellHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < ITEM_SLOTS || slot >= SIZE) {
            // Own items move freely; the quote follows once the click is applied.
            renderLater(player, holder);
            return;
        }
        event.setCancelled(true);
        if (slot == CANCEL_SLOT) player.closeInventory();
        else if (slot == CONFIRM_SLOT) confirm(player, holder);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof SellHolder holder)) return;
        if (event.getRawSlots().stream().anyMatch(slot -> slot >= ITEM_SLOTS && slot < SIZE)) {
            event.setCancelled(true);
            return;
        }
        if (event.getWhoClicked() instanceof Player player) renderLater(player, holder);
    }

    private void confirm(Player player, SellHolder holder) {
        if (!smp.isActive(player) || smp.isEditing(player)) {
            player.closeInventory();
            return;
        }
        Contents contents = contents(holder.inventory);
        if (market.quote(player.getUniqueId(), contents.units()).total() <= 0) {
            if (!contents.units().isEmpty()) {
                Messages.send(player, Messages.Type.ERROR, "Dafür zahlt der Markt gerade nichts. Lege mehr dazu.");
            }
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }
        MarketService.Quote quote = market.sell(player.getUniqueId(), contents.units());
        for (int slot : contents.sellableSlots()) holder.inventory.setItem(slot, null);
        SmpEconomy economy = smp.economy();
        economy.deposit(player.getUniqueId(), quote.total(), "Verkauf von " + quote.lines().size() + " Item-Arten");
        Messages.send(player, Messages.Type.SUCCESS, "Verkauft für " + economy.format(quote.total())
                + ". Neuer Kontostand: " + economy.format(economy.balance(player.getUniqueId())) + ".");
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        smp.refreshScoreboard(player.getUniqueId());
        render(player, holder);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof SellHolder holder)) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        for (int slot = 0; slot < ITEM_SLOTS; slot++) {
            ItemStack item = holder.inventory.getItem(slot);
            if (item == null || item.isEmpty()) continue;
            holder.inventory.setItem(slot, null);
            if (player.isDead()) {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
            } else {
                player.getInventory().addItem(item).values()
                        .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
            }
        }
    }

    /** Items still lying in the sell menu die with the player like the rest of the inventory. */
    @EventHandler(priority = EventPriority.LOW)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof SellHolder holder)) return;
        for (int slot = 0; slot < ITEM_SLOTS; slot++) {
            ItemStack item = holder.inventory.getItem(slot);
            if (item == null || item.isEmpty()) continue;
            holder.inventory.setItem(slot, null);
            if (event.getKeepInventory()) player.getInventory().addItem(item);
            else event.getDrops().add(item);
        }
    }

    private static final class SellHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
