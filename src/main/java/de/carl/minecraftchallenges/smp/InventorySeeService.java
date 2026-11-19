package de.carl.minecraftchallenges.smp;

import de.carl.minecraftchallenges.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * /invsee: an admin view of a player's SMP inventory. While the target is in SMP survival the view mirrors their
 * real inventory both ways every tick; otherwise (offline, outside the SMP, in edit mode) the copy in their profile is
 * shown and written back. Like {@link EnderChestService}, {@link #release(UUID)} must run before the SMP captures or
 * applies a player's state, and items can only be moved by an admin who is in SMP survival themselves.
 *
 * <p>Layout (45 slots): rows 1–3 main inventory, row 4 hotbar, row 5 helmet, chestplate, leggings, boots, offhand.
 */
public final class InventorySeeService implements Listener {
    private static final int SIZE = 45;
    private static final int PLAYER_SLOTS = 41;
    /** GUI slot → player inventory slot; -1 marks a locked filler slot. */
    private static final int[] MAPPING = new int[SIZE];

    static {
        for (int slot = 0; slot < 27; slot++) MAPPING[slot] = slot + 9;
        for (int slot = 0; slot < 9; slot++) MAPPING[27 + slot] = slot;
        MAPPING[36] = 39; // helmet
        MAPPING[37] = 38; // chestplate
        MAPPING[38] = 37; // leggings
        MAPPING[39] = 36; // boots
        MAPPING[40] = 40; // offhand
        for (int slot = 41; slot < SIZE; slot++) MAPPING[slot] = -1;
    }

    private final Plugin plugin;
    private final SmpService smp;
    private final SmpProfileStore profiles;
    private final Map<UUID, View> views = new HashMap<>();
    /** One shared live view per target, so several admins see the same state. */
    private final Map<UUID, LiveSee> live = new HashMap<>();
    private final Map<UUID, Inventory> stored = new HashMap<>();
    private final Set<UUID> pendingSaves = new HashSet<>();
    private BukkitTask syncTask;

    private record View(UUID target, boolean live, boolean readOnly) {}

    private static final class LiveSee implements InventoryHolder {
        private final UUID target;
        private final ItemStack[] synced = new ItemStack[SIZE];
        private Inventory inventory;

        private LiveSee(UUID target) {
            this.target = target;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private static final class StoredSee implements InventoryHolder {
        private final SmpProfile profile;
        private Inventory inventory;

        private StoredSee(SmpProfile profile) {
            this.profile = profile;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    InventorySeeService(Plugin plugin, SmpService smp, SmpProfileStore profiles) {
        this.plugin = plugin;
        this.smp = smp;
        this.profiles = profiles;
    }

    /** Whether the target's real inventory is their SMP survival inventory right now. */
    private boolean isLive(Player target) {
        return target != null && target.isOnline() && smp.isActive(target) && !smp.isEditing(target)
                && !smp.isHoldingState(target);
    }

    /** /invsee <player>: live while the target is in SMP survival, otherwise their stored inventory. */
    public void open(Player viewer, UUID target) {
        if (!smp.isAvailable()) {
            Messages.send(viewer, Messages.Type.ERROR, "Das SMP wird gerade gewartet.");
            return;
        }
        if (viewer.getUniqueId().equals(target)) {
            Messages.send(viewer, Messages.Type.ERROR, "Dein eigenes Inventar öffnest du mit E.");
            return;
        }
        Player online = Bukkit.getPlayer(target);
        if (online != null && smp.isActive(online) && smp.isHoldingState(online)) {
            Messages.send(viewer, Messages.Type.ERROR, online.getName()
                    + " schaut gerade unsichtbar zu; das Survival-Inventar ist so lange weggelegt.");
            return;
        }
        closeView(viewer);
        viewer.closeInventory();
        boolean readOnly = !smp.isActive(viewer) || smp.isEditing(viewer) || smp.isHoldingState(viewer)
                || viewer.isDead();
        boolean isLive = isLive(online);
        Inventory inventory;
        String name;
        if (isLive) {
            LiveSee see = live.computeIfAbsent(target, uuid -> createLive(online));
            inventory = see.inventory;
            name = online.getName();
        } else {
            SmpProfile profile;
            try {
                profile = profiles.load(target, online != null ? online.getName() : smp.economy().name(target));
            } catch (IOException exception) {
                Messages.send(viewer, Messages.Type.ERROR, "Der SMP-Spielstand dieses Spielers ist beschädigt.");
                return;
            }
            if (profile.survival() == null) {
                Messages.send(viewer, Messages.Type.ERROR, profile.name() + " hat noch kein SMP-Inventar.");
                return;
            }
            inventory = stored.computeIfAbsent(target, uuid -> createStored(profile));
            name = profile.name();
        }

        views.put(viewer.getUniqueId(), new View(target, isLive, readOnly));
        if (viewer.openInventory(inventory) == null) {
            views.remove(viewer.getUniqueId());
            discardIfUnused(target);
            return;
        }
        if (isLive) startSync();
        String state = isLive ? "live" : "gespeichert";
        Messages.send(viewer, Messages.Type.INFO, "Inventar von " + name + " (" + state + ")"
                + (readOnly ? " – nur ansehen; bearbeiten kannst du es im SMP-Survival." : "."));
    }

    /** Closes every view on the target's inventory and writes the stored copy back to the profile. */
    public void release(UUID target) {
        LiveSee see = live.get(target);
        if (see != null) pull(see);
        for (Map.Entry<UUID, View> entry : List.copyOf(views.entrySet())) {
            if (!entry.getValue().target().equals(target)) continue;
            views.remove(entry.getKey());
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer != null) viewer.closeInventory();
        }
        live.remove(target);
        Inventory inventory = stored.remove(target);
        if (inventory != null) writeBack(inventory);
        pendingSaves.remove(target);
        stopSyncIfIdle();
    }

    public void releaseAll() {
        Set<UUID> targets = new HashSet<>(stored.keySet());
        targets.addAll(live.keySet());
        views.values().forEach(view -> targets.add(view.target()));
        targets.forEach(this::release);
    }

    // ---------------------------------------------------------------- inventories

    private static Inventory create(InventoryHolder holder, String name) {
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                Component.text("Inventar von " + name, NamedTextColor.DARK_GREEN));
        for (int slot = 0; slot < SIZE; slot++) {
            if (MAPPING[slot] < 0) inventory.setItem(slot, filler());
        }
        return inventory;
    }

    private static ItemStack filler() {
        ItemStack item = ItemStack.of(Material.GRAY_STAINED_GLASS_PANE);
        item.editMeta(meta -> meta.displayName(Component.text("Rüstung · Zweithand ←", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        return item;
    }

    private LiveSee createLive(Player target) {
        LiveSee see = new LiveSee(target.getUniqueId());
        see.inventory = create(see, target.getName());
        PlayerInventory source = target.getInventory();
        for (int slot = 0; slot < SIZE; slot++) {
            if (MAPPING[slot] < 0) continue;
            ItemStack item = copy(source.getItem(MAPPING[slot]));
            see.inventory.setItem(slot, item);
            see.synced[slot] = copy(item);
        }
        return see;
    }

    private Inventory createStored(SmpProfile profile) {
        StoredSee holder = new StoredSee(profile);
        Inventory inventory = create(holder, profile.name());
        ItemStack[] contents = SmpPlayerState.fit(profile.survival().inventory(), PLAYER_SLOTS);
        for (int slot = 0; slot < SIZE; slot++) {
            if (MAPPING[slot] >= 0) inventory.setItem(slot, contents[MAPPING[slot]]);
        }
        holder.inventory = inventory;
        return inventory;
    }

    private void writeBack(Inventory inventory) {
        if (!(inventory.getHolder() instanceof StoredSee holder)) return;
        SmpProfile profile = holder.profile;
        SmpPlayerState survival = profile.survival();
        if (survival == null) return;
        ItemStack[] contents = SmpPlayerState.fit(survival.inventory(), PLAYER_SLOTS);
        for (int slot = 0; slot < SIZE; slot++) {
            if (MAPPING[slot] >= 0) contents[MAPPING[slot]] = copy(inventory.getItem(slot));
        }
        profile.survival(survival.withInventory(contents));
        profiles.save(profile);
    }

    // ---------------------------------------------------------------- live sync

    private void startSync() {
        if (syncTask == null) syncTask = Bukkit.getScheduler().runTaskTimer(plugin, this::syncAll, 1L, 1L);
    }

    private void stopSyncIfIdle() {
        if (live.isEmpty() && syncTask != null) {
            syncTask.cancel();
            syncTask = null;
        }
    }

    private void syncAll() {
        for (LiveSee see : List.copyOf(live.values())) {
            Player target = Bukkit.getPlayer(see.target);
            if (!isLive(target)) {
                // Spectating started or the state changed without passing through release(): the inventory is no
                // longer the survival one, so nothing is pushed into it.
                live.remove(see.target);
                release(see.target);
                continue;
            }
            sync(see, target.getInventory());
        }
        stopSyncIfIdle();
    }

    /**
     * Per slot: what the admin changed since the last sync goes to the player, otherwise the player's slot is copied
     * into the view. Only slots that changed are written, so the two sides never overwrite each other wholesale.
     */
    private static void sync(LiveSee see, PlayerInventory target) {
        for (int slot = 0; slot < SIZE; slot++) {
            int playerSlot = MAPPING[slot];
            if (playerSlot < 0) continue;
            ItemStack shown = see.inventory.getItem(slot);
            if (!same(shown, see.synced[slot])) {
                target.setItem(playerSlot, copy(shown));
                see.synced[slot] = copy(shown);
                continue;
            }
            ItemStack actual = target.getItem(playerSlot);
            if (!same(actual, see.synced[slot])) {
                see.inventory.setItem(slot, copy(actual));
                see.synced[slot] = copy(actual);
            }
        }
    }

    /** Pushes the admin's last changes before the target's state is captured. */
    private static void pull(LiveSee see) {
        Player target = Bukkit.getPlayer(see.target);
        if (target != null && target.isOnline()) sync(see, target.getInventory());
    }

    private static boolean same(ItemStack first, ItemStack second) {
        boolean firstEmpty = first == null || first.isEmpty();
        boolean secondEmpty = second == null || second.isEmpty();
        if (firstEmpty || secondEmpty) return firstEmpty == secondEmpty;
        return first.getAmount() == second.getAmount() && first.isSimilar(second);
    }

    private static ItemStack copy(ItemStack item) {
        return item == null || item.isEmpty() ? null : item.clone();
    }

    // ---------------------------------------------------------------- views

    private void closeView(Player viewer) {
        View view = views.remove(viewer.getUniqueId());
        if (view == null) return;
        viewer.closeInventory();
        discardIfUnused(view.target());
    }

    private void discardIfUnused(UUID target) {
        boolean viewed = views.values().stream().anyMatch(view -> view.target().equals(target));
        if (viewed) return;
        LiveSee see = live.remove(target);
        if (see != null) pull(see);
        Inventory inventory = stored.remove(target);
        if (inventory != null) writeBack(inventory);
        pendingSaves.remove(target);
        stopSyncIfIdle();
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(InventoryClickEvent event) {
        View view = views.get(event.getWhoClicked().getUniqueId());
        if (view == null) return;
        int top = event.getView().getTopInventory().getSize();
        int raw = event.getRawSlot();
        boolean filler = raw >= 0 && raw < top && MAPPING[raw] < 0;
        // Double-click collecting could pull filler panes onto the cursor.
        boolean collectsFiller = event.getAction() == InventoryAction.COLLECT_TO_CURSOR
                && event.getCursor().getType() == Material.GRAY_STAINED_GLASS_PANE;
        if (filler || collectsFiller) {
            event.setCancelled(true);
            return;
        }
        guard(view, event);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrag(InventoryDragEvent event) {
        View view = views.get(event.getWhoClicked().getUniqueId());
        if (view == null) return;
        int top = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(raw -> raw < top && MAPPING[raw] < 0)) {
            event.setCancelled(true);
            return;
        }
        guard(view, event);
    }

    private void guard(View view, Cancellable event) {
        if (view.readOnly()) {
            event.setCancelled(true);
            return;
        }
        if (!view.live() && pendingSaves.add(view.target())) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!pendingSaves.remove(view.target())) return;
                Inventory inventory = stored.get(view.target());
                if (inventory != null) writeBack(inventory);
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        View view = views.remove(event.getPlayer().getUniqueId());
        if (view != null) discardIfUnused(view.target());
    }
}
