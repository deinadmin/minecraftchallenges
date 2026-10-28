package de.carl.minecraftchallenges.menu;

import de.carl.minecraftchallenges.challenge.ChallengeManager;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public final class AdminMenuHolder implements InventoryHolder {
    private final ChallengeManager manager;
    private Inventory inventory;

    AdminMenuHolder(ChallengeManager manager) {
        this.manager = manager;
    }

    public ChallengeManager manager() {
        return manager;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
