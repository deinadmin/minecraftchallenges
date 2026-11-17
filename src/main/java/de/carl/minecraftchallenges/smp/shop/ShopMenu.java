package de.carl.minecraftchallenges.smp.shop;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.menu.MenuItems;
import de.carl.minecraftchallenges.smp.SmpEconomy;
import de.carl.minecraftchallenges.smp.SmpService;
import de.carl.minecraftchallenges.smp.SmpText;
import de.carl.minecraftchallenges.smp.economy.ItemValues;
import de.carl.minecraftchallenges.smp.economy.ItemValues.Pool;
import de.carl.minecraftchallenges.smp.shop.DailyShop.Offer;
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
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** /shop: today's nine offers. */
public final class ShopMenu implements Listener {
    public static final String SHUFFLE_PERMISSION = "minecraftchallenges.command.shop";
    private static final int HEADER_ROW = 9;
    private static final int OFFER_ROW = 18;
    private static final int INFO_SLOT = 31;
    private static final int SHUFFLE_SLOT = 35;
    private static final int BULK_BUNDLES = 9;

    private final SmpService smp;
    private final DailyShop shop;

    public ShopMenu(MinecraftChallengesPlugin plugin, SmpService smp, DailyShop shop) {
        this.smp = smp;
        this.shop = shop;
        shop.onChange(() -> plugin.getServer().getScheduler().runTask(plugin, this::refreshOpenMenus));
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshOpenMenus, 20L, 20L);
    }

    public void open(Player player) {
        if (!smp.isActive(player) || smp.isEditing(player)) {
            Messages.send(player, Messages.Type.ERROR, smp.isEditing(player)
                    ? "Im Bearbeitungsmodus kannst du nichts kaufen."
                    : "Der Shop ist nur im SMP verfügbar.");
            return;
        }
        ShopHolder holder = new ShopHolder();
        holder.inventory = Bukkit.createInventory(holder, 36, Component.text("Shop · Tagesangebote", NamedTextColor.DARK_GRAY));
        render(player, holder);
        player.openInventory(holder.inventory);
    }

    public void shuffle(Player admin) {
        if (!admin.hasPermission(SHUFFLE_PERMISSION)) {
            Messages.sendSystem(admin, Messages.Type.ERROR, "Dafür hast du keine Rechte.");
            return;
        }
        shop.shuffle();
        smp.broadcast(Messages.smp(Messages.Type.INFO, "Der Shop wurde neu gemischt. Schau mit /shop vorbei!"));
    }

    private void refreshOpenMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof ShopHolder holder) render(player, holder);
        }
    }

    // ---------------------------------------------------------------- rendering

    private void render(Player viewer, ShopHolder holder) {
        Inventory inventory = holder.inventory;
        SmpEconomy economy = smp.economy();
        long balance = economy.balance(viewer.getUniqueId());
        for (int slot = 0; slot < 36; slot++) {
            if (slot < OFFER_ROW || slot >= OFFER_ROW + 9) set(inventory, slot, MenuItems.frame());
        }
        int column = 0;
        for (Map.Entry<Pool, Integer> group : DailyShop.LAYOUT.entrySet()) {
            for (int count = 0; count < group.getValue() && column < 9; count++, column++) {
                set(inventory, HEADER_ROW + column, header(group.getKey()));
            }
        }
        List<Offer> offers = shop.offers();
        for (int index = 0; index < 9; index++) {
            set(inventory, OFFER_ROW + index, index < offers.size() ? icon(viewer, offers.get(index), balance) : MenuItems.frame());
        }
        set(inventory, INFO_SLOT, MenuItems.item(Material.GOLD_INGOT,
                SmpText.plain("Kontostand: " + economy.format(balance), NamedTextColor.GOLD),
                List.of(SmpText.gray("Neue Angebote in " + SmpText.duration(DailyShop.secondsUntilReset())),
                        SmpText.gray("(jeden Tag um 0 Uhr)"),
                        Component.empty(),
                        SmpText.plain("Items verkaufen: /sell", NamedTextColor.DARK_GRAY))));
        if (viewer.hasPermission(SHUFFLE_PERMISSION)) {
            set(inventory, SHUFFLE_SLOT, MenuItems.item(Material.ENDER_EYE,
                    SmpText.plain("Neu mischen", NamedTextColor.LIGHT_PURPLE),
                    List.of(SmpText.gray("Zieht sofort neue Angebote für alle."),
                            SmpText.gray("Der Wechsel um 0 Uhr bleibt."),
                            SmpText.plain("Shift-Klick zum Mischen", NamedTextColor.YELLOW))));
        }
    }

    /** Leaves unchanged slots alone, so the once-a-second refresh does not flicker. */
    private static void set(Inventory inventory, int slot, ItemStack item) {
        if (!item.equals(inventory.getItem(slot))) inventory.setItem(slot, item);
    }

    private static ItemStack header(Pool pool) {
        return switch (pool) {
            case BUILD -> MenuItems.item(Material.LIME_STAINED_GLASS_PANE,
                    SmpText.plain("Baublöcke", NamedTextColor.GREEN), List.of(SmpText.gray("Unbegrenzt kaufbar")));
            case USEFUL -> MenuItems.item(Material.LIGHT_BLUE_STAINED_GLASS_PANE,
                    SmpText.plain("Nützliches", NamedTextColor.AQUA), List.of(SmpText.gray("1× pro Spieler und Tag")));
            case VALUABLE -> MenuItems.item(Material.PURPLE_STAINED_GLASS_PANE,
                    SmpText.plain("Wertvolles", NamedTextColor.LIGHT_PURPLE), List.of(SmpText.gray("1× pro Spieler und Tag")));
            default -> MenuItems.item(Material.ORANGE_STAINED_GLASS_PANE,
                    SmpText.plain("Rarität des Tages", NamedTextColor.GOLD), List.of(SmpText.gray("1× pro Spieler und Tag")));
        };
    }

    private ItemStack icon(Player viewer, Offer offer, long balance) {
        SmpEconomy economy = smp.economy();
        int amount = offer.entry().bundle();
        ItemStack item = ItemValues.createItem(offer.entry(), amount);
        boolean affordable = balance >= offer.price();
        List<Component> lore = new ArrayList<>();
        lore.add(SmpText.gray("Preis: ").append(SmpText.plain(economy.format(offer.price())
                + (amount > 1 ? " für " + amount + " Stück" : ""), affordable ? NamedTextColor.GREEN : NamedTextColor.RED)));
        lore.add(Component.empty());
        if (offer.unlimited()) {
            lore.add(SmpText.plain("Linksklick: ", NamedTextColor.YELLOW).append(SmpText.gray(amount + " Stück kaufen")));
            lore.add(SmpText.plain("Shift-Klick: ", NamedTextColor.YELLOW).append(SmpText.gray(amount * BULK_BUNDLES
                    + " Stück für " + economy.format(offer.price() * BULK_BUNDLES))));
            lore.add(SmpText.plain("Unbegrenzt kaufbar", NamedTextColor.DARK_GRAY));
        } else if (shop.hasBought(viewer.getUniqueId(), offer)) {
            lore.add(SmpText.plain("Heute schon gekauft", NamedTextColor.RED));
        } else {
            lore.add(SmpText.plain("Klicken zum Kaufen", NamedTextColor.YELLOW));
            lore.add(SmpText.plain("Nur 1× pro Tag", NamedTextColor.DARK_GRAY));
        }
        return SmpText.withLore(item, lore);
    }

    // ---------------------------------------------------------------- clicks

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ShopHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot == SHUFFLE_SLOT && player.hasPermission(SHUFFLE_PERMISSION)) {
            if (event.isShiftClick()) shuffle(player);
            else player.sendActionBar(SmpText.plain("Zum Mischen Shift-Klick verwenden.", NamedTextColor.RED));
            return;
        }
        int index = slot - OFFER_ROW;
        List<Offer> offers = shop.offers();
        if (index < 0 || index >= 9 || index >= offers.size()) return;
        Offer offer = offers.get(index);
        purchase(player, offer, offer.unlimited() && event.isShiftClick() ? BULK_BUNDLES : 1);
        render(player, holder);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ShopHolder) event.setCancelled(true);
    }

    private void purchase(Player player, Offer offer, int bundles) {
        if (!smp.isActive(player) || smp.isEditing(player)) {
            player.closeInventory();
            return;
        }
        if (!offer.unlimited() && shop.hasBought(player.getUniqueId(), offer)) {
            Messages.send(player, Messages.Type.ERROR, "Dieses Angebot hast du heute schon gekauft.");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }
        SmpEconomy economy = smp.economy();
        ItemStack template = ItemValues.createItem(offer.entry(), 1);
        int count = offer.entry().bundle() * bundles;
        long total = offer.price() * bundles;
        if (freeSpace(player, template) < count) {
            Messages.send(player, Messages.Type.ERROR, "Dafür ist in deinem Inventar nicht genug Platz.");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }
        if (!economy.withdraw(player.getUniqueId(), total, "Shop: " + count + "× " + offer.entry().id())) {
            Messages.send(player, Messages.Type.ERROR, "Dafür reicht dein Kontostand nicht ("
                    + economy.format(economy.balance(player.getUniqueId())) + ").");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }
        if (!offer.unlimited()) shop.markBought(player.getUniqueId(), offer);
        give(player, template, count);
        Messages.send(player, Messages.Type.SUCCESS, Component.text("Gekauft: " + count + "× ")
                .append(template.effectiveName()).append(Component.text(" für " + economy.format(total) + ".")));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        smp.refreshScoreboard(player.getUniqueId());
    }

    /** Hands out {@code count} items; whatever does not fit drops at the player's feet. */
    public static void give(Player player, ItemStack template, int count) {
        int remaining = count;
        while (remaining > 0) {
            ItemStack stack = template.clone();
            stack.setAmount(Math.min(remaining, template.getMaxStackSize()));
            remaining -= stack.getAmount();
            player.getInventory().addItem(stack).values()
                    .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        }
    }

    public static int freeSpace(Player player, ItemStack template) {
        int space = 0;
        int max = template.getMaxStackSize();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.isEmpty()) space += max;
            else if (item.isSimilar(template)) space += Math.max(0, max - item.getAmount());
        }
        return space;
    }

    private static final class ShopHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
