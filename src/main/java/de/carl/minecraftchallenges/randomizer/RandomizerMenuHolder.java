package de.carl.minecraftchallenges.randomizer;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public final class RandomizerMenuHolder implements InventoryHolder {
    public enum View { ADMIN, TIME }
    private final RandomizerChallenge challenge;
    private final View view;
    private Inventory inventory;

    public RandomizerMenuHolder(RandomizerChallenge challenge, View view) {
        this.challenge = challenge;
        this.view = view;
    }

    public RandomizerChallenge challenge() { return challenge; }
    public View view() { return view; }
    public void setInventory(Inventory inventory) { this.inventory = inventory; }
    @Override public @NotNull Inventory getInventory() { return inventory; }
}
