package de.carl.minecraftchallenges.menu;

import de.carl.minecraftchallenges.challenge.ChallengeManager;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public final class TimeMenuHolder implements InventoryHolder {
    private final ChallengeManager manager;
    private Inventory inventory;

    TimeMenuHolder(ChallengeManager manager) {
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
