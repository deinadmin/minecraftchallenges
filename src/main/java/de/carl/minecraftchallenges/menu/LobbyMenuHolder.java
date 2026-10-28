package de.carl.minecraftchallenges.menu;

import de.carl.minecraftchallenges.lobby.GameType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public final class LobbyMenuHolder implements InventoryHolder {
    private final GameType gameType;
    private Inventory inventory;

    LobbyMenuHolder(GameType gameType) {
        this.gameType = gameType;
    }

    public GameType gameType() {
        return gameType;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
