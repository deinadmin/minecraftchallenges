package de.carl.minecraftchallenges.smp.shop;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** One offer: the item template (its amount is the bundle size) and the price per bundle. */
public final class ShopEntry {
    public static final long MAX_PRICE = 1_000_000_000L;

    private final UUID id;
    private ItemStack item;
    private long price;

    public ShopEntry(UUID id, ItemStack item, long price) {
        this.id = id;
        item(item);
        price(price);
    }

    public UUID id() { return id; }

    public ItemStack item() { return item.clone(); }

    public void item(ItemStack item) {
        ItemStack copy = item.clone();
        copy.setAmount(Math.max(1, Math.min(copy.getMaxStackSize(), copy.getAmount())));
        this.item = copy;
    }

    public int amount() { return item.getAmount(); }

    public void amount(int amount) {
        item.setAmount(Math.max(1, Math.min(item.getMaxStackSize(), amount)));
    }

    public long price() { return price; }

    public void price(long price) {
        this.price = Math.max(1, Math.min(MAX_PRICE, price));
    }
}
