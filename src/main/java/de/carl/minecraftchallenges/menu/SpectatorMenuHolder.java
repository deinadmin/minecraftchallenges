package de.carl.minecraftchallenges.menu;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SpectatorMenuHolder implements InventoryHolder {
    private final Map<Integer, UUID> targets = new HashMap<>();
    private Inventory inventory;

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    public void setTarget(int slot, UUID target) {
        targets.put(slot, target);
    }

    public UUID getTarget(int slot) {
        return targets.get(slot);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
