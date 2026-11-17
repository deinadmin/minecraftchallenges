package de.carl.minecraftchallenges.smp.economy;

import de.carl.minecraftchallenges.menu.MenuItems;
import de.carl.minecraftchallenges.smp.SmpService;
import de.carl.minecraftchallenges.smp.SmpText;
import de.carl.minecraftchallenges.smp.economy.ItemValues.Entry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** /prices: the current sell price of every item with a market, wanted items first. */
public final class PricesMenu implements Listener {
    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int INFO_SLOT = 49;
    private static final int NEXT_SLOT = 53;

    private final SmpService smp;
    private final MarketService market;

    public PricesMenu(SmpService smp, MarketService market) {
        this.smp = smp;
        this.market = market;
    }

    public void open(Player player) {
        PricesHolder holder = new PricesHolder();
        holder.inventory = Bukkit.createInventory(holder, 54, Component.text("Marktpreise", NamedTextColor.DARK_GRAY));
        render(player, holder);
        player.openInventory(holder.inventory);
    }

    private List<Entry> sorted() {
        List<Entry> entries = new ArrayList<>(market.values().markets());
        entries.sort(Comparator.comparing((Entry entry) -> !market.isWanted(entry))
                .thenComparing(Comparator.comparingDouble(Entry::value).reversed()));
        return entries;
    }

    private void render(Player viewer, PricesHolder holder) {
        Inventory inventory = holder.inventory;
        List<Entry> entries = sorted();
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        holder.page = Math.max(0, Math.min(pages - 1, holder.page));
        inventory.clear();
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            int index = holder.page * PAGE_SIZE + slot;
            if (index >= entries.size()) break;
            inventory.setItem(slot, icon(viewer, entries.get(index)));
        }
        for (int slot = PAGE_SIZE; slot < 54; slot++) inventory.setItem(slot, MenuItems.frame());
        if (holder.page > 0) {
            inventory.setItem(PREVIOUS_SLOT, MenuItems.item(Material.ARROW,
                    SmpText.plain("Vorherige Seite", NamedTextColor.YELLOW), List.of()));
        }
        if (holder.page < pages - 1) {
            inventory.setItem(NEXT_SLOT, MenuItems.item(Material.ARROW,
                    SmpText.plain("Nächste Seite", NamedTextColor.YELLOW), List.of()));
        }
        inventory.setItem(INFO_SLOT, MenuItems.item(Material.BOOK,
                SmpText.plain("Marktpreise · Seite " + (holder.page + 1) + "/" + pages, NamedTextColor.GOLD),
                List.of(SmpText.gray("Verkaufen: /sell"),
                        SmpText.gray("Jeder Verkauf drückt den Kurs für alle."),
                        SmpText.gray("Der Kurs erholt sich von selbst wieder."),
                        Component.empty(),
                        SmpText.plain("★ Heute gesucht: doppelter Preis", NamedTextColor.YELLOW),
                        SmpText.plain("Blöcke, Roherze und Nuggets zählen", NamedTextColor.DARK_GRAY),
                        SmpText.plain("zum jeweiligen Rohstoff.", NamedTextColor.DARK_GRAY))));
    }

    private ItemStack icon(Player viewer, Entry entry) {
        double factor = market.factor(entry);
        int percent = (int) Math.round(factor * 100);
        NamedTextColor color = percent >= 85 ? NamedTextColor.GREEN : percent >= 50 ? NamedTextColor.YELLOW : NamedTextColor.RED;
        int trend = market.trend(entry);
        String arrow = trend > 0 ? " ▲ steigt" : trend < 0 ? " ▼ fällt" : "";
        int stack = entry.material().getMaxStackSize();
        long stackCoins = market.quote(viewer.getUniqueId(), Map.of(entry, (double) stack)).total();

        List<Component> lore = new ArrayList<>();
        lore.add(SmpText.gray("Verkaufspreis: ").append(SmpText.plain(
                SmpText.decimal(market.unitPrice(entry)) + " " + smp.economy().currency() + " pro Stück", NamedTextColor.GOLD)));
        lore.add(SmpText.gray("Kurs: ").append(SmpText.plain(percent + " % vom Normalpreis" + arrow, color)));
        lore.add(SmpText.gray(stack + " Stück bringen dir: ").append(SmpText.plain(
                smp.economy().format(stackCoins), NamedTextColor.WHITE)));
        if (market.isWanted(entry)) {
            double left = market.wantedLeft(viewer.getUniqueId(), entry);
            lore.add(Component.empty());
            lore.add(SmpText.plain("★ Heute gesucht", NamedTextColor.YELLOW));
            lore.add(left > 0
                    ? SmpText.gray("Doppelter Preis für deine nächsten " + SmpText.decimal(left) + " Stück")
                    : SmpText.gray("Deinen Bonus hast du heute schon genutzt."));
        }
        ItemStack item = new ItemStack(entry.material());
        if (market.isWanted(entry)) item.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
        return SmpText.withLore(item, lore);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof PricesHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getRawSlot() == PREVIOUS_SLOT) holder.page--;
        else if (event.getRawSlot() == NEXT_SLOT) holder.page++;
        else return;
        render(player, holder);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof PricesHolder) event.setCancelled(true);
    }

    private static final class PricesHolder implements InventoryHolder {
        private int page;
        private Inventory inventory;

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
