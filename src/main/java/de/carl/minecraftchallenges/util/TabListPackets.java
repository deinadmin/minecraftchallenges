package de.carl.minecraftchallenges.util;

import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

/**
 * Paper 26.3: hiding a player also removes them from the viewer's tab list. For an admin who spectates
 * invisibly the entry must stay, so it is sent again on its own, without the entity.
 */
public final class TabListPackets {
    private final Method playerHandle = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer").getMethod("getHandle");
    private final Field connection = Class.forName("net.minecraft.server.level.ServerPlayer").getField("connection");
    private final Method send = Class.forName("net.minecraft.server.network.ServerCommonPacketListenerImpl")
            .getMethod("send", Class.forName("net.minecraft.network.protocol.Packet"));
    private final Method initializing = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket")
            .getMethod("createPlayerInitializing", Collection.class);

    public TabListPackets() throws ReflectiveOperationException { }

    /** Adds {@code listed} to the viewer's tab list again, exactly as on a normal join. */
    public void relist(Player viewer, Player listed) throws ReflectiveOperationException {
        Object listener = connection.get(playerHandle.invoke(viewer));
        if (listener == null) return;
        Object packet = initializing.invoke(null, List.of(playerHandle.invoke(listed)));
        send.invoke(listener, packet);
    }
}
