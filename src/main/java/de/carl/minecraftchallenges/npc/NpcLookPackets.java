package de.carl.minecraftchallenges.npc;

import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Paper 26.3 client-only rotation: never changes the shared entity's position or rotation. */
final class NpcLookPackets {
    private final Constructor<?> rotation = Class.forName("net.minecraft.network.protocol.game.ClientboundMoveEntityPacket$Rot")
            .getConstructor(int.class, byte.class, byte.class, boolean.class);
    private final Constructor<?> headRotation = Class.forName("net.minecraft.network.protocol.game.ClientboundRotateHeadPacket")
            .getConstructor(Class.forName("net.minecraft.world.entity.Entity"), byte.class);
    private final Method entityHandle = Class.forName("org.bukkit.craftbukkit.entity.CraftEntity").getMethod("getHandle");
    private final Method playerHandle = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer").getMethod("getHandle");
    private final Field connection = Class.forName("net.minecraft.server.level.ServerPlayer").getField("connection");
    private final Method send = Class.forName("net.minecraft.server.network.ServerCommonPacketListenerImpl")
            .getMethod("send", Class.forName("net.minecraft.network.protocol.Packet"));

    NpcLookPackets() throws ReflectiveOperationException { }

    Object[] packets(Mannequin npc, NpcLookDirection direction) throws ReflectiveOperationException {
        return new Object[] {
                rotation.newInstance(npc.getEntityId(), direction.yaw(), direction.pitch(), npc.isOnGround()),
                headRotation.newInstance(entityHandle.invoke(npc), direction.yaw())
        };
    }

    void send(Player viewer, Mannequin npc, NpcLookDirection direction) throws ReflectiveOperationException {
        Object listener = connection.get(playerHandle.invoke(viewer));
        for (Object packet : packets(npc, direction)) send.invoke(listener, packet);
    }
}
