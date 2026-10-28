package de.carl.minecraftchallenges.smp;

import de.carl.minecraftchallenges.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Portable ender chests. While a player is in SMP survival their real ender chest is authoritative and
 * opened directly; otherwise (offline, outside the SMP, in edit mode) the copy in their profile is shown
 * and written back. {@link #release(UUID)} must run before the SMP captures or applies a player's state,
 * so an open view can never duplicate or overwrite items.
 */
public final class EnderChestService implements Listener {
    private static final int SIZE = 27;

    private final Plugin plugin;
    private final SmpService smp;
    private final SmpProfileStore profiles;
    private final Map<UUID, View> views = new HashMap<>();
    private final Map<UUID, Inventory> stored = new HashMap<>();
    private final Set<UUID> pendingSaves = new HashSet<>();

    private record View(UUID target, boolean live, boolean readOnly) {}

    private static final class StoredChest implements InventoryHolder {
        private final SmpProfile profile;
        private Inventory inventory;

        private StoredChest(SmpProfile profile) {
            this.profile = profile;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    EnderChestService(Plugin plugin, SmpService smp, SmpProfileStore profiles) {
        this.plugin = plugin;
        this.smp = smp;
        this.profiles = profiles;
    }

    /** /ec: the player's own ender chest, only in SMP survival. */
    public void openOwn(Player player) {
        if (!smp.isActive(player)) {
            Messages.send(player, Messages.Type.ERROR, "Deine Endertruhe kannst du nur im SMP öffnen.");
            return;
        }
        if (smp.isEditing(player)) {
            Messages.send(player, Messages.Type.ERROR, "Die Endertruhe ist im Bearbeitungsmodus gesperrt.");
            return;
        }
        if (player.isDead()) return;
        if (player.openInventory(player.getEnderChest()) != null) {
            player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.6f, 1.0f);
        }
    }

    /** /ec <player>: live while the target is in SMP survival, otherwise their stored ender chest. */
    public void openOther(Player viewer, UUID target) {
        if (!smp.isAvailable()) {
            Messages.send(viewer, Messages.Type.ERROR, "Das SMP wird gerade gewartet.");
            return;
        }
        closeView(viewer);
        viewer.closeInventory();
        // Items may only move between survival inventories of the SMP; everywhere else the view is read-only.
        boolean readOnly = !smp.isActive(viewer) || smp.isEditing(viewer) || viewer.isDead();
        Player online = Bukkit.getPlayer(target);
        Inventory inventory;
        String name;
        boolean live = online != null && smp.isActive(online) && !smp.isEditing(online);
        if (live) {
            inventory = online.getEnderChest();
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
                Messages.send(viewer, Messages.Type.ERROR, profile.name() + " hat noch keine SMP-Endertruhe.");
                return;
            }
            inventory = stored.computeIfAbsent(target, uuid -> createStored(profile));
            name = profile.name();
        }

        views.put(viewer.getUniqueId(), new View(target, live, readOnly));
        if (viewer.openInventory(inventory) == null) {
            views.remove(viewer.getUniqueId());
            discardIfUnused(target);
            return;
        }
        viewer.playSound(viewer.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.6f, 1.0f);
        String state = live ? "live" : "gespeichert";
        Messages.send(viewer, Messages.Type.INFO, "Endertruhe von " + name + " (" + state + ")"
                + (readOnly ? " – nur ansehen; bearbeiten kannst du sie im SMP-Survival." : "."));
    }

    public boolean isViewingOther(Player player) {
        return views.containsKey(player.getUniqueId());
    }

    /** Closes every view on the target's ender chest and writes the stored copy back to the profile. */
    public void release(UUID target) {
        for (Map.Entry<UUID, View> entry : List.copyOf(views.entrySet())) {
            if (!entry.getValue().target().equals(target)) continue;
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer != null) viewer.closeInventory();
            views.remove(entry.getKey());
        }
        Inventory inventory = stored.remove(target);
        if (inventory != null) writeBack(inventory);
        pendingSaves.remove(target);
    }

    public void releaseAll() {
        Set<UUID> targets = new HashSet<>(stored.keySet());
        views.values().forEach(view -> targets.add(view.target()));
        targets.forEach(this::release);
    }

    private Inventory createStored(SmpProfile profile) {
        StoredChest holder = new StoredChest(profile);
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                Component.text("Endertruhe von " + profile.name(), NamedTextColor.DARK_PURPLE));
        inventory.setContents(SmpPlayerState.fit(profile.survival().enderChest(), SIZE));
        holder.inventory = inventory;
        return inventory;
    }

    private void writeBack(Inventory inventory) {
        if (!(inventory.getHolder() instanceof StoredChest holder)) return;
        SmpProfile profile = holder.profile;
        SmpPlayerState survival = profile.survival();
        if (survival == null) return;
        profile.survival(survival.withEnderChest(SmpPlayerState.copy(inventory.getContents())));
        profiles.save(profile);
    }

    private void closeView(Player viewer) {
        View view = views.remove(viewer.getUniqueId());
        if (view == null) return;
        viewer.closeInventory();
        discardIfUnused(view.target());
    }

    private void discardIfUnused(UUID target) {
        boolean viewed = views.values().stream().anyMatch(view -> view.target().equals(target));
        if (viewed) return;
        Inventory inventory = stored.remove(target);
        if (inventory != null) writeBack(inventory);
        pendingSaves.remove(target);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(InventoryClickEvent event) {
        guard(event.getWhoClicked(), event);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrag(InventoryDragEvent event) {
        guard(event.getWhoClicked(), event);
    }

    private void guard(HumanEntity who, org.bukkit.event.Cancellable event) {
        View view = views.get(who.getUniqueId());
        if (view == null) return;
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
