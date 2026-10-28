package de.carl.minecraftchallenges.custom;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public final class CustomChallengeMenuHolder implements InventoryHolder {
    public enum View { LIST, CREATE, CONFIG }

    private final View view;
    private final CustomSettings draft;
    private final CustomChallenge challenge;
    private Inventory inventory;

    CustomChallengeMenuHolder(View view, CustomSettings draft, CustomChallenge challenge) {
        this.view = view;
        this.draft = draft;
        this.challenge = challenge;
    }

    public View view() { return view; }
    CustomSettings draft() { return draft; }
    CustomChallenge challenge() { return challenge; }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
