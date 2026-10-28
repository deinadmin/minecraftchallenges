package de.carl.minecraftchallenges.randomizer;

import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.inventory.CraftingRecipe;
import org.bukkit.inventory.Recipe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Per-round random mappings shared by the randomizer games and custom challenges. */
public final class RandomMappings {
    private static final List<Material> ITEM_POOL = Arrays.stream(Material.values())
            .filter(material -> material.isItem() && !material.isAir())
            .toList();

    private final Map<String, Material> recipeOutcomes = new HashMap<>();
    private final List<Material> craftableOutcomes = new ArrayList<>();
    private final Map<Material, Material> blockDrops = new HashMap<>();
    private final Map<Material, Material> itemSwaps = new HashMap<>();

    public static Material randomItem() {
        return ITEM_POOL.get(ThreadLocalRandom.current().nextInt(ITEM_POOL.size()));
    }

    /** Gives every crafting recipe a different result than it normally has. */
    public void shuffleRecipes() {
        recipeOutcomes.clear();
        craftableOutcomes.clear();
        List<String> keys = new ArrayList<>();
        List<Material> originals = new ArrayList<>();
        Iterator<Recipe> recipes = Bukkit.recipeIterator();
        while (recipes.hasNext()) {
            Recipe recipe = recipes.next();
            if (!(recipe instanceof CraftingRecipe) || !(recipe instanceof Keyed keyed)) continue;
            Material result = recipe.getResult().getType();
            if (!result.isItem() || result.isAir()) continue;
            keys.add(keyed.getKey().toString());
            originals.add(result);
        }
        List<Material> shuffled = new ArrayList<>(originals);
        boolean deranged = false;
        for (int attempt = 0; attempt < 250 && !deranged; attempt++) {
            Collections.shuffle(shuffled);
            deranged = true;
            for (int index = 0; index < shuffled.size(); index++) {
                if (shuffled.get(index) == originals.get(index)) { deranged = false; break; }
            }
        }
        for (int index = 0; index < keys.size(); index++) {
            Material outcome = shuffled.get(index);
            while (outcome == originals.get(index)) outcome = randomItem();
            recipeOutcomes.put(keys.get(index), outcome);
            craftableOutcomes.add(outcome);
        }
    }

    public Material recipeOutcome(Recipe recipe) {
        return recipe instanceof Keyed keyed ? recipeOutcomes.get(keyed.getKey().toString()) : null;
    }

    public List<Material> craftableOutcomes() {
        return craftableOutcomes;
    }

    public void shuffleBlockDrops() {
        blockDrops.clear();
        for (Material block : Material.values()) {
            if (block.isBlock() && !block.isAir()) blockDrops.put(block, randomItem());
        }
    }

    public Material blockDrop(Material block) {
        return blockDrops.computeIfAbsent(block, ignored -> randomItem());
    }

    /** Stable random replacement for any item, used for mob drops. */
    public Material itemSwap(Material item) {
        return itemSwaps.computeIfAbsent(item, ignored -> randomItem());
    }

    public void clear() {
        recipeOutcomes.clear();
        craftableOutcomes.clear();
        blockDrops.clear();
        itemSwaps.clear();
    }
}
