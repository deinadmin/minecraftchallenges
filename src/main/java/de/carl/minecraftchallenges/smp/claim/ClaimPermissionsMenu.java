package de.carl.minecraftchallenges.smp.claim;

import de.carl.minecraftchallenges.menu.MenuItems;
import de.carl.minecraftchallenges.smp.SmpText;
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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * /claim permissions: one row per {@link ClaimFlag.Category} with a coloured header pane on the left,
 * the plot rules and bulk buttons in the bottom bar. Owners and claim admins edit, everyone else
 * sees what strangers may do on the plot.
 */
public final class ClaimPermissionsMenu implements Listener {
    private static final int SIZE = 54;
    private static final List<ClaimFlag.Category> ROWS = List.of(ClaimFlag.Category.BUILDING,
            ClaimFlag.Category.REDSTONE, ClaimFlag.Category.CONTAINERS, ClaimFlag.Category.STATIONS,
            ClaimFlag.Category.MOBS);
    private static final int FLAGS_PER_ROW = 7;
    private static final int ALLOW_ALL_SLOT = 46;
    private static final int DEFAULTS_SLOT = 47;
    private static final int DENY_ALL_SLOT = 48;
    private static final int INFO_SLOT = 49;
    private static final int RULES_HEADER_SLOT = 50;
    private static final int FIRST_RULE_SLOT = 51;

    private final ClaimService claims;

    public ClaimPermissionsMenu(ClaimService claims) {
        this.claims = claims;
        claims.onPermissionsChange(this::refresh);
    }

    public void open(Player player, Claim claim) {
        Holder holder = new Holder(claim.id());
        String title = claim.owner().equals(player.getUniqueId()) ? "Grundstück · Rechte"
                : "Rechte · " + claims.name(claim.owner());
        holder.inventory = Bukkit.createInventory(holder, SIZE, Component.text(title, NamedTextColor.DARK_GRAY));
        render(player, holder, claim);
        player.openInventory(holder.inventory);
    }

    /** Someone changed this plot: everyone looking at it sees the new state. */
    private void refresh(Claim claim) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getOpenInventory().getTopInventory().getHolder() instanceof Holder holder
                    && holder.claim.equals(claim.id())) {
                render(viewer, holder, claim);
            }
        }
    }

    // ---------------------------------------------------------------- rendering

    private void render(Player viewer, Holder holder, Claim claim) {
        Inventory inventory = holder.inventory;
        boolean editable = claims.mayEditPermissions(viewer, claim);
        for (int row = 0; row < ROWS.size(); row++) {
            ClaimFlag.Category category = ROWS.get(row);
            List<ClaimFlag> flags = category.flags();
            set(inventory, row * 9, header(category, claim, editable));
            for (int column = 0; column < FLAGS_PER_ROW; column++) {
                set(inventory, row * 9 + 1 + column, column < flags.size()
                        ? flagItem(flags.get(column), claim, editable) : MenuItems.frame());
            }
            set(inventory, row * 9 + 8, MenuItems.frame());
        }
        set(inventory, 45, MenuItems.frame());
        set(inventory, ALLOW_ALL_SLOT, editable ? button(Material.LIME_DYE, "Fremden alles erlauben", NamedTextColor.GREEN,
                List.of(SmpText.gray("Fremde dürfen dann auch bauen"), SmpText.gray("und Truhen öffnen."),
                        Component.empty(), SmpText.plain("» Shift-Klick zum Bestätigen", NamedTextColor.YELLOW)))
                : MenuItems.frame());
        set(inventory, DEFAULTS_SLOT, editable ? button(Material.LIGHT_GRAY_DYE, "Standard wiederherstellen", NamedTextColor.WHITE,
                List.of(SmpText.gray("Werkbank, Endertruhe, Fahrzeuge,"), SmpText.gray("Items aufheben, Perlen, PvP und"),
                        SmpText.gray("Monster erlaubt, alles andere nicht."),
                        Component.empty(), SmpText.plain("» Klicken zum Zurücksetzen", NamedTextColor.YELLOW)))
                : MenuItems.frame());
        set(inventory, DENY_ALL_SLOT, editable ? button(Material.RED_DYE, "Fremden alles verbieten", NamedTextColor.RED,
                List.of(SmpText.gray("Fremde können nur noch herumlaufen"), SmpText.gray("und Monster bekämpfen."),
                        Component.empty(), SmpText.plain("» Klicken zum Verbieten", NamedTextColor.YELLOW)))
                : MenuItems.frame());
        set(inventory, INFO_SLOT, info(viewer, claim, editable));
        set(inventory, RULES_HEADER_SLOT, header(ClaimFlag.Category.RULES, claim, false));
        List<ClaimFlag> rules = ClaimFlag.Category.RULES.flags();
        for (int slot = FIRST_RULE_SLOT; slot < SIZE; slot++) {
            int index = slot - FIRST_RULE_SLOT;
            set(inventory, slot, index < rules.size() ? flagItem(rules.get(index), claim, editable) : MenuItems.frame());
        }
    }

    private ItemStack header(ClaimFlag.Category category, Claim claim, boolean editable) {
        List<ClaimFlag> flags = category.flags();
        long allowed = flags.stream().filter(claim::allows).count();
        List<Component> lore = new ArrayList<>();
        if (category == ClaimFlag.Category.RULES) {
            lore.add(SmpText.gray("Gelten für alle auf dem Grundstück,"));
            lore.add(SmpText.gray("auch für dich und Vertraute."));
        } else {
            lore.add(SmpText.gray(allowed + " von " + flags.size() + " für Fremde erlaubt"));
            if (editable) {
                boolean all = allowed == flags.size();
                lore.add(Component.empty());
                lore.add(SmpText.plain("» Klicken: ganze Reihe " + (all ? "verbieten" : "erlauben"), NamedTextColor.YELLOW));
            }
        }
        return MenuItems.item(category.pane(),
                Component.text(category.title(), category.color(), TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false),
                lore);
    }

    private static ItemStack flagItem(ClaimFlag flag, Claim claim, boolean editable) {
        boolean allowed = claim.allows(flag);
        List<Component> lore = new ArrayList<>();
        flag.description().forEach(line -> lore.add(SmpText.gray(line)));
        lore.add(Component.empty());
        lore.add(allowed ? SmpText.plain(flag.isRule() ? "✔ An" : "✔ Erlaubt", NamedTextColor.GREEN)
                : SmpText.plain(flag.isRule() ? "✘ Aus" : "✘ Verboten", NamedTextColor.RED));
        if (allowed != flag.allowedByDefault()) {
            lore.add(SmpText.plain("Standard: " + (flag.allowedByDefault() ? (flag.isRule() ? "an" : "erlaubt")
                    : (flag.isRule() ? "aus" : "verboten")), NamedTextColor.DARK_GRAY));
        }
        if (editable) {
            String action = flag.isRule() ? (allowed ? "Ausschalten" : "Einschalten") : (allowed ? "Verbieten" : "Erlauben");
            lore.add(SmpText.plain("» Klicken zum " + action, NamedTextColor.YELLOW));
        }
        ItemStack item = MenuItems.item(flag.icon(),
                Component.text(flag.title(), allowed ? NamedTextColor.GREEN : NamedTextColor.RED, TextDecoration.BOLD)
                        .decoration(TextDecoration.ITALIC, false), lore);
        item.editMeta(meta -> {
            meta.setEnchantmentGlintOverride(allowed);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        });
        return item;
    }

    private ItemStack info(Player viewer, Claim claim, boolean editable) {
        boolean own = claim.owner().equals(viewer.getUniqueId());
        List<Component> lore = new ArrayList<>();
        lore.add(SmpText.gray("Mitte " + claim.x() + ", " + claim.z() + " · " + claim.side() + "×" + claim.side() + " Blöcke"));
        lore.add(SmpText.gray(claim.trusted().isEmpty() ? "Niemand ist vertraut."
                : claim.trusted().size() + " Spieler vertraut"));
        lore.add(Component.empty());
        lore.add(SmpText.gray("Diese Rechte gelten für Fremde."));
        lore.add(SmpText.gray("Vertraute dürfen immer alles"));
        lore.add(SmpText.plain("(/claim trust <Spieler>).", NamedTextColor.DARK_GRAY));
        lore.add(SmpText.gray("Monster darf jeder bekämpfen."));
        if (!editable) {
            lore.add(Component.empty());
            lore.add(SmpText.plain("Nur der Besitzer kann das ändern.", NamedTextColor.RED));
        } else if (!own) {
            lore.add(Component.empty());
            lore.add(SmpText.plain("Du bearbeitest als Admin.", NamedTextColor.GOLD));
        }
        return MenuItems.item(Material.FILLED_MAP,
                SmpText.plain(own ? "Dein Grundstück" : "Grundstück von " + claims.name(claim.owner()), NamedTextColor.GOLD)
                        .decoration(TextDecoration.BOLD, true), lore);
    }

    private static ItemStack button(Material material, String name, NamedTextColor color, List<Component> lore) {
        return MenuItems.item(material, SmpText.plain(name, color).decoration(TextDecoration.BOLD, true), lore);
    }

    /** Leaves unchanged slots alone, so a refresh does not flicker. */
    private static void set(Inventory inventory, int slot, ItemStack item) {
        if (!item.equals(inventory.getItem(slot))) inventory.setItem(slot, item);
    }

    // ---------------------------------------------------------------- clicks

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;
        Claim claim = claims.find(holder.claim);
        if (claim == null) {
            player.closeInventory();
            Messages.send(player, Messages.Type.ERROR, "Dieses Grundstück gibt es nicht mehr.");
            return;
        }
        Action action = action(slot, event.isShiftClick());
        if (action == null) return;
        if (!claims.mayEditPermissions(player, claim)) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1.0f);
            render(player, holder, claim);
            return;
        }
        if (claims.isReadOnly()) {
            Messages.send(player, Messages.Type.ERROR, "Grundstücke sind gerade deaktiviert. Bitte melde dich bei einem Admin.");
            return;
        }
        action.apply(claims, claim);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        // Viewers are refreshed by the change listener; a click that changed nothing still redraws this one.
        render(player, holder, claim);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }

    private interface Action {
        void apply(ClaimService claims, Claim claim);
    }

    /** What a click on this slot does, or null for frames and the info item. */
    private static Action action(int slot, boolean shift) {
        int row = slot / 9;
        int column = slot % 9;
        if (row < ROWS.size()) {
            List<ClaimFlag> flags = ROWS.get(row).flags();
            if (column == 0) {
                return (claims, claim) -> claims.setAllowed(claim, flags, !flags.stream().allMatch(claim::allows));
            }
            int index = column - 1;
            if (column == 8 || index >= flags.size()) return null;
            ClaimFlag flag = flags.get(index);
            return (claims, claim) -> claims.setAllowed(claim, flag, !claim.allows(flag));
        }
        List<ClaimFlag> rules = ClaimFlag.Category.RULES.flags();
        int rule = slot - FIRST_RULE_SLOT;
        if (rule >= 0 && rule < rules.size()) {
            ClaimFlag flag = rules.get(rule);
            return (claims, claim) -> claims.setAllowed(claim, flag, !claim.allows(flag));
        }
        return switch (slot) {
            case ALLOW_ALL_SLOT -> shift ? (claims, claim) -> claims.setAllowed(claim, ClaimFlag.visitorFlags(), true) : null;
            case DEFAULTS_SLOT -> ClaimService::resetPermissions;
            case DENY_ALL_SLOT -> (claims, claim) -> claims.setAllowed(claim, ClaimFlag.visitorFlags(), false);
            default -> null;
        };
    }

    private static final class Holder implements InventoryHolder {
        private final UUID claim;
        private Inventory inventory;

        private Holder(UUID claim) {
            this.claim = claim;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
