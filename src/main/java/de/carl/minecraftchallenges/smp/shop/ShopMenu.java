package de.carl.minecraftchallenges.smp.shop;

import de.carl.minecraftchallenges.MinecraftChallengesPlugin;
import de.carl.minecraftchallenges.menu.MenuItems;
import de.carl.minecraftchallenges.smp.SmpEconomy;
import de.carl.minecraftchallenges.smp.SmpService;
import de.carl.minecraftchallenges.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Buy view, admin editor view and the per-offer editor of the SMP quick shop. */
public final class ShopMenu implements Listener {
    public static final String EDIT_PERMISSION = "minecraftchallenges.command.shop";
    private static final int PAGE_SIZE = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int MODE_SLOT = 47;
    private static final int INFO_SLOT = 49;
    private static final int NEXT_SLOT = 53;
    private static final long DEFAULT_PRICE = 100;

    private static final int PREVIEW_SLOT = 4;
    private static final int PRICE_SLOT = 22;
    private static final int AMOUNT_SLOT = 31;
    private static final int BACK_SLOT = 36;
    private static final int MOVE_EARLIER_SLOT = 38;
    private static final int DELETE_SLOT = 40;
    private static final int MOVE_LATER_SLOT = 42;
    private static final Map<Integer, Long> PRICE_STEPS = Map.of(
            18, -1000L, 19, -100L, 20, -10L, 21, -1L, 23, 1L, 24, 10L, 25, 100L, 26, 1000L);
    private static final Map<Integer, Integer> AMOUNT_STEPS = Map.of(28, -8, 29, -1, 33, 1, 34, 8);

    private final SmpService smp;
    private final ShopService shop;

    public ShopMenu(MinecraftChallengesPlugin plugin, SmpService smp, ShopService shop) {
        this.smp = smp;
        this.shop = shop;
        shop.onChange(() -> plugin.getServer().getScheduler().runTask(plugin, this::refreshOpenMenus));
    }

    // ---------------------------------------------------------------- opening

    public void openShop(Player player, int page) {
        if (!smp.isActive(player) || smp.isEditing(player)) {
            Messages.send(player, Messages.Type.ERROR, smp.isEditing(player)
                    ? "Im Bearbeitungsmodus kannst du nichts kaufen. Für den Shop-Editor: /shop edit"
                    : "Der Shop ist nur im SMP verfügbar.");
            return;
        }
        open(player, new ShopHolder(false, page));
    }

    public void openEditor(Player player, int page) {
        if (!player.hasPermission(EDIT_PERMISSION)) {
            Messages.sendSystem(player, Messages.Type.ERROR, "Dafür hast du keine Rechte.");
            return;
        }
        if (!smp.isActive(player)) {
            Messages.send(player, Messages.Type.ERROR, "Den Shop bearbeitest du im SMP (am besten mit /edit).");
            return;
        }
        if (shop.isReadOnly()) {
            Messages.send(player, Messages.Type.ERROR, "shop.yml ist beschädigt; Änderungen würden nicht gespeichert.");
        }
        open(player, new ShopHolder(true, page));
    }

    private void open(Player player, ShopHolder holder) {
        Component title = Component.text(holder.editor ? "Shop bearbeiten" : "Shop", NamedTextColor.DARK_GRAY);
        Inventory inventory = Bukkit.createInventory(holder, 54, title);
        holder.inventory = inventory;
        render(player, holder);
        player.openInventory(inventory);
    }

    private void openEntryEditor(Player player, UUID id, int returnPage) {
        EntryHolder holder = new EntryHolder(id, returnPage);
        Inventory inventory = Bukkit.createInventory(holder, 45,
                Component.text("Angebot bearbeiten", NamedTextColor.DARK_GRAY));
        holder.inventory = inventory;
        if (!renderEntry(holder)) {
            openEditor(player, returnPage);
            return;
        }
        player.openInventory(inventory);
    }

    private void refreshOpenMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
            if (holder instanceof ShopHolder shopHolder) {
                render(player, shopHolder);
            } else if (holder instanceof EntryHolder entryHolder && !renderEntry(entryHolder)) {
                openEditor(player, entryHolder.returnPage);
            }
        }
    }

    // ---------------------------------------------------------------- rendering

    private int pageCount(boolean editor) {
        int size = shop.entries().size() + (editor ? 1 : 0);
        return Math.max(1, (size + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private void render(Player viewer, ShopHolder holder) {
        Inventory inventory = holder.inventory;
        holder.page = Math.max(0, Math.min(pageCount(holder.editor) - 1, holder.page));
        holder.slots.clear();
        inventory.clear();
        List<ShopEntry> entries = shop.entries();
        long balance = smp.economy().balance(viewer.getUniqueId());
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            int index = holder.page * PAGE_SIZE + slot;
            if (index >= entries.size()) break;
            ShopEntry entry = entries.get(index);
            holder.slots.put(slot, entry.id());
            inventory.setItem(slot, holder.editor ? editorIcon(entry) : buyIcon(entry, balance));
        }
        for (int slot = PAGE_SIZE; slot < 54; slot++) inventory.setItem(slot, MenuItems.frame());
        int pages = pageCount(holder.editor);
        if (holder.page > 0) {
            inventory.setItem(PREVIOUS_SLOT, MenuItems.item(Material.ARROW,
                    Component.text("Vorherige Seite", NamedTextColor.YELLOW), List.of()));
        }
        if (holder.page < pages - 1) {
            inventory.setItem(NEXT_SLOT, MenuItems.item(Material.ARROW,
                    Component.text("Nächste Seite", NamedTextColor.YELLOW), List.of()));
        }
        if (holder.editor) {
            inventory.setItem(MODE_SLOT, MenuItems.item(Material.EMERALD,
                    Component.text("Zur Kaufansicht", NamedTextColor.GREEN), List.of()));
            inventory.setItem(INFO_SLOT, MenuItems.item(Material.BOOK,
                    Component.text("Shop-Editor · Seite " + (holder.page + 1) + "/" + pages, NamedTextColor.GOLD),
                    List.of(Component.text("Item auf einen leeren Platz legen: neues Angebot", NamedTextColor.GRAY),
                            Component.text("Shift-Klick im eigenen Inventar: ans Ende anfügen", NamedTextColor.GRAY),
                            Component.text("Klick auf ein Angebot: Preis & Menge bearbeiten", NamedTextColor.GRAY),
                            Component.text("Mit Item auf ein Angebot klicken: Item ersetzen", NamedTextColor.GRAY),
                            Component.text("Die Stapelgröße des Items ist die Verkaufsmenge.", NamedTextColor.DARK_GRAY))));
        } else {
            if (viewer.hasPermission(EDIT_PERMISSION)) {
                inventory.setItem(MODE_SLOT, MenuItems.item(Material.WRITABLE_BOOK,
                        Component.text("Shop bearbeiten", NamedTextColor.LIGHT_PURPLE), List.of()));
            }
            inventory.setItem(INFO_SLOT, MenuItems.item(Material.GOLD_INGOT,
                    Component.text("Kontostand: " + smp.economy().format(balance), NamedTextColor.GOLD),
                    List.of(Component.text("Seite " + (holder.page + 1) + "/" + pages, NamedTextColor.GRAY),
                            Component.text("Linksklick: 1× kaufen", NamedTextColor.GRAY),
                            Component.text("Rechtsklick: einen ganzen Stapel kaufen", NamedTextColor.GRAY))));
        }
    }

    private ItemStack buyIcon(ShopEntry entry, long balance) {
        ItemStack icon = entry.item();
        int bundles = stackBundles(entry);
        boolean affordable = balance >= entry.price();
        List<Component> lore = new ArrayList<>(existingLore(icon));
        if (!lore.isEmpty()) lore.add(Component.empty());
        lore.add(plain("Preis: ", NamedTextColor.GRAY).append(plain(smp.economy().format(entry.price())
                + (entry.amount() > 1 ? " für " + entry.amount() + " Stück" : ""),
                affordable ? NamedTextColor.GREEN : NamedTextColor.RED)));
        lore.add(Component.empty());
        lore.add(plain("Linksklick: ", NamedTextColor.YELLOW).append(plain(entry.amount() + " Stück für "
                + smp.economy().format(entry.price()), NamedTextColor.GRAY)));
        if (bundles > 1) {
            lore.add(plain("Rechtsklick: ", NamedTextColor.YELLOW).append(plain(entry.amount() * bundles
                    + " Stück für " + smp.economy().format(entry.price() * bundles), NamedTextColor.GRAY)));
        }
        return withLore(icon, lore);
    }

    private ItemStack editorIcon(ShopEntry entry) {
        ItemStack icon = entry.item();
        List<Component> lore = new ArrayList<>(existingLore(icon));
        if (!lore.isEmpty()) lore.add(Component.empty());
        lore.add(plain("Preis: ", NamedTextColor.GRAY).append(plain(smp.economy().format(entry.price()), NamedTextColor.GOLD)));
        lore.add(plain("Menge: ", NamedTextColor.GRAY).append(plain(String.valueOf(entry.amount()), NamedTextColor.WHITE)));
        lore.add(Component.empty());
        lore.add(plain("Klicken zum Bearbeiten", NamedTextColor.YELLOW));
        lore.add(plain("Mit Item klicken: Item ersetzen", NamedTextColor.DARK_GRAY));
        return withLore(icon, lore);
    }

    /** Returns false if the offer no longer exists. */
    private boolean renderEntry(EntryHolder holder) {
        ShopEntry entry = shop.find(holder.id).orElse(null);
        if (entry == null) return false;
        Inventory inventory = holder.inventory;
        MenuItems.fill(inventory);
        List<Component> previewLore = new ArrayList<>(existingLore(entry.item()));
        if (!previewLore.isEmpty()) previewLore.add(Component.empty());
        previewLore.add(plain("Platz " + (shop.indexOf(holder.id) + 1) + " von " + shop.entries().size(), NamedTextColor.GRAY));
        previewLore.add(plain("Mit Item klicken: Item ersetzen", NamedTextColor.DARK_GRAY));
        inventory.setItem(PREVIEW_SLOT, withLore(entry.item(), previewLore));

        PRICE_STEPS.forEach((slot, step) -> inventory.setItem(slot, MenuItems.item(
                step < 0 ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE,
                plain((step < 0 ? "" : "+") + step + " " + smp.economy().currency(),
                        step < 0 ? NamedTextColor.RED : NamedTextColor.GREEN),
                List.of(plain("Shift-Klick: ×10", NamedTextColor.GRAY)))));
        inventory.setItem(PRICE_SLOT, MenuItems.item(Material.GOLD_NUGGET,
                plain("Preis: " + smp.economy().format(entry.price()), NamedTextColor.GOLD),
                List.of(plain("pro Kauf von " + entry.amount() + " Stück", NamedTextColor.GRAY))));

        AMOUNT_STEPS.forEach((slot, step) -> inventory.setItem(slot, MenuItems.item(
                step < 0 ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE,
                plain((step < 0 ? "" : "+") + step + " Stück", step < 0 ? NamedTextColor.RED : NamedTextColor.GREEN),
                List.of())));
        ItemStack amountIcon = MenuItems.item(entry.item().getType().isItem() ? entry.item().getType() : Material.CHEST,
                plain("Menge pro Kauf: " + entry.amount(), NamedTextColor.WHITE),
                List.of(plain("Maximal " + entry.item().getMaxStackSize(), NamedTextColor.GRAY)));
        amountIcon.setAmount(entry.amount());
        inventory.setItem(AMOUNT_SLOT, amountIcon);

        inventory.setItem(BACK_SLOT, MenuItems.item(Material.ARROW, plain("Zurück", NamedTextColor.YELLOW), List.of()));
        inventory.setItem(MOVE_EARLIER_SLOT, MenuItems.item(Material.SPECTRAL_ARROW,
                plain("Nach vorne verschieben", NamedTextColor.AQUA), List.of(plain("Shift-Klick: 9 Plätze", NamedTextColor.GRAY))));
        inventory.setItem(MOVE_LATER_SLOT, MenuItems.item(Material.SPECTRAL_ARROW,
                plain("Nach hinten verschieben", NamedTextColor.AQUA), List.of(plain("Shift-Klick: 9 Plätze", NamedTextColor.GRAY))));
        inventory.setItem(DELETE_SLOT, MenuItems.item(Material.BARRIER, plain("Angebot löschen", NamedTextColor.RED),
                List.of(plain("Shift-Klick zum Löschen", NamedTextColor.GRAY))));
        return true;
    }

    // ---------------------------------------------------------------- clicks

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof ShopHolder shopHolder) {
            if (shopHolder.editor) handleEditorClick(event, player, shopHolder);
            else handleBuyClick(event, player, shopHolder);
        } else if (holder instanceof EntryHolder entryHolder) {
            handleEntryClick(event, player, entryHolder);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof ShopHolder) && !(holder instanceof EntryHolder)) return;
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) event.setCancelled(true);
    }

    private void handleBuyClick(InventoryClickEvent event, Player player, ShopHolder holder) {
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) return;
        if (navigate(player, holder, slot)) return;
        if (slot == MODE_SLOT && player.hasPermission(EDIT_PERMISSION)) {
            openEditor(player, holder.page);
            return;
        }
        UUID id = holder.slots.get(slot);
        if (id == null) return;
        ShopEntry entry = shop.find(id).orElse(null);
        if (entry == null) return;
        int bundles = event.isRightClick() ? stackBundles(entry) : 1;
        purchase(player, entry, bundles);
        render(player, holder);
    }

    private boolean navigate(Player player, ShopHolder holder, int slot) {
        if (slot == PREVIOUS_SLOT && holder.page > 0) {
            holder.page--;
            render(player, holder);
            return true;
        }
        if (slot == NEXT_SLOT && holder.page < pageCount(holder.editor) - 1) {
            holder.page++;
            render(player, holder);
            return true;
        }
        return false;
    }

    private void handleEditorClick(InventoryClickEvent event, Player player, ShopHolder holder) {
        if (!player.hasPermission(EDIT_PERMISSION)) {
            event.setCancelled(true);
            player.closeInventory();
            return;
        }
        int slot = event.getRawSlot();
        boolean top = slot >= 0 && slot < 54;
        if (!top) {
            // The admin's own inventory stays usable; shift-click adds the item instead of moving it.
            if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
                event.setCancelled(true);
            } else if (event.isShiftClick()) {
                event.setCancelled(true);
                ItemStack clicked = event.getCurrentItem();
                if (clicked != null && !clicked.isEmpty()) {
                    ShopEntry entry = shop.add(Integer.MAX_VALUE, clicked, DEFAULT_PRICE);
                    openEntryEditor(player, entry.id(), shop.indexOf(entry.id()) / PAGE_SIZE);
                }
            }
            return;
        }
        event.setCancelled(true);
        if (navigate(player, holder, slot)) return;
        if (slot == MODE_SLOT) {
            open(player, new ShopHolder(false, holder.page));
            return;
        }
        if (slot >= PAGE_SIZE) return;
        ItemStack cursor = event.getCursor();
        UUID id = holder.slots.get(slot);
        if (cursor != null && !cursor.isEmpty()) {
            // The cursor item is only copied as a template; the admin keeps it.
            if (id != null) {
                shop.find(id).ifPresent(entry -> {
                    entry.item(cursor);
                    shop.changed();
                });
                Messages.send(player, Messages.Type.SUCCESS, "Item des Angebots ersetzt.");
            } else {
                ShopEntry entry = shop.add(holder.page * PAGE_SIZE + slot, cursor, DEFAULT_PRICE);
                openEntryEditor(player, entry.id(), holder.page);
            }
            return;
        }
        if (id != null) openEntryEditor(player, id, holder.page);
    }

    private void handleEntryClick(InventoryClickEvent event, Player player, EntryHolder holder) {
        int slot = event.getRawSlot();
        boolean top = slot >= 0 && slot < 45;
        if (!player.hasPermission(EDIT_PERMISSION)) {
            event.setCancelled(true);
            player.closeInventory();
            return;
        }
        if (!top) {
            if (event.isShiftClick() || event.getAction() == InventoryAction.COLLECT_TO_CURSOR) event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        ShopEntry entry = shop.find(holder.id).orElse(null);
        if (entry == null) {
            openEditor(player, holder.returnPage);
            return;
        }
        Long priceStep = PRICE_STEPS.get(slot);
        Integer amountStep = AMOUNT_STEPS.get(slot);
        if (priceStep != null) {
            entry.price(entry.price() + priceStep * (event.isShiftClick() ? 10 : 1));
            shop.changed();
        } else if (amountStep != null) {
            entry.amount(entry.amount() + amountStep);
            shop.changed();
        } else if (slot == PREVIEW_SLOT) {
            ItemStack cursor = event.getCursor();
            if (cursor != null && !cursor.isEmpty()) {
                entry.item(cursor);
                shop.changed();
            }
        } else if (slot == MOVE_EARLIER_SLOT || slot == MOVE_LATER_SLOT) {
            int distance = event.isShiftClick() ? 9 : 1;
            shop.move(entry.id(), slot == MOVE_EARLIER_SLOT ? -distance : distance);
            holder.returnPage = shop.indexOf(entry.id()) / PAGE_SIZE;
        } else if (slot == DELETE_SLOT) {
            if (!event.isShiftClick()) {
                player.sendActionBar(plain("Zum Löschen Shift-Klick verwenden.", NamedTextColor.RED));
                return;
            }
            shop.remove(entry.id());
            Messages.send(player, Messages.Type.SUCCESS, "Angebot gelöscht.");
            openEditor(player, holder.returnPage);
        } else if (slot == BACK_SLOT) {
            openEditor(player, holder.returnPage);
        }
    }

    // ---------------------------------------------------------------- buying

    private void purchase(Player player, ShopEntry entry, int bundles) {
        if (!smp.isActive(player) || smp.isEditing(player)) {
            player.closeInventory();
            return;
        }
        SmpEconomy economy = smp.economy();
        ItemStack template = entry.item();
        int count = entry.amount() * bundles;
        long total = entry.price() * bundles;
        Component name = template.effectiveName();
        if (freeSpace(player, template) < count) {
            Messages.send(player, Messages.Type.ERROR, "Dafür ist in deinem Inventar nicht genug Platz.");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }
        if (!economy.withdraw(player.getUniqueId(), total, "Shop: " + count + "× " + template.getType().name())) {
            Messages.send(player, Messages.Type.ERROR, "Dafür reicht dein Kontostand nicht ("
                    + economy.format(economy.balance(player.getUniqueId())) + ").");
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }
        int remaining = count;
        while (remaining > 0) {
            ItemStack stack = template.clone();
            stack.setAmount(Math.min(remaining, template.getMaxStackSize()));
            remaining -= stack.getAmount();
            player.getInventory().addItem(stack).values()
                    .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        }
        Messages.send(player, Messages.Type.SUCCESS, Component.text("Gekauft: " + count + "× ")
                .append(name).append(Component.text(" für " + economy.format(total) + ".")));
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        smp.refreshScoreboard(player.getUniqueId());
    }

    private static int freeSpace(Player player, ItemStack template) {
        int space = 0;
        int max = template.getMaxStackSize();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.isEmpty()) space += max;
            else if (item.isSimilar(template)) space += Math.max(0, max - item.getAmount());
        }
        return space;
    }

    /** How many bundles make up one full stack (right click). */
    private static int stackBundles(ShopEntry entry) {
        return Math.max(1, entry.item().getMaxStackSize() / entry.amount());
    }

    // ---------------------------------------------------------------- helpers

    private static List<Component> existingLore(ItemStack item) {
        List<Component> lore = item.hasItemMeta() ? item.getItemMeta().lore() : null;
        return lore == null ? List.of() : lore;
    }

    private static ItemStack withLore(ItemStack item, List<Component> lore) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static final class ShopHolder implements InventoryHolder {
        private final boolean editor;
        private final Map<Integer, UUID> slots = new HashMap<>();
        private int page;
        private Inventory inventory;

        private ShopHolder(boolean editor, int page) {
            this.editor = editor;
            this.page = page;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private static final class EntryHolder implements InventoryHolder {
        private final UUID id;
        private int returnPage;
        private Inventory inventory;

        private EntryHolder(UUID id, int returnPage) {
            this.id = id;
            this.returnPage = returnPage;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
