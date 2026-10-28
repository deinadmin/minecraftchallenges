package de.carl.minecraftchallenges.randomizer;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Recipe;

/** A game whose worlds can randomize block drops and crafting results while a round runs. */
public interface RandomizedWorld {
    boolean randomizesBlockDrops();

    Material randomDrop(Material block);

    /** The randomized result for a recipe, or {@code null} if crafting is not randomized right now. */
    Material outcomeFor(Recipe recipe);

    boolean isParticipant(Player player);
}
