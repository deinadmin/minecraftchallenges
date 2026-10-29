package de.carl.minecraftchallenges.chat;

import de.carl.minecraftchallenges.util.Messages;
import io.netty.channel.Channel;
import net.kyori.adventure.text.Component;

import java.lang.reflect.*;
import java.util.*;

/** Mojang-mapped Paper 26.3 access, isolated from the formatting policy and lifecycle. */
final class ChatPacketBridge {
    private final Class<?> system = Class.forName("net.minecraft.network.protocol.game.ClientboundSystemChatPacket");
    private final Class<?> disguised = Class.forName("net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket");
    private final Class<?> signed = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerChatPacket");
    private final Class<?> vanillaComponent = Class.forName("net.minecraft.network.chat.Component");
    private final Class<?> bound = Class.forName("net.minecraft.network.chat.ChatType$Bound");
    private final Class<?> holder = Class.forName("net.minecraft.core.Holder");
    private final Class<?> chatType = Class.forName("net.minecraft.network.chat.ChatType");
    private final Class<?> decoration = Class.forName("net.minecraft.network.chat.ChatTypeDecoration");
    private final Class<?> style = Class.forName("net.minecraft.network.chat.Style");
    private final Method asAdventure = Class.forName("io.papermc.paper.adventure.PaperAdventure").getMethod("asAdventure", vanillaComponent);
    private final Method asVanilla = Class.forName("io.papermc.paper.adventure.PaperAdventure").getMethod("asVanilla", Component.class);
    private final Constructor<?> systemConstructor = system.getConstructor(Component.class, boolean.class);
    private final Method content = system.getMethod("content");
    private final Method overlay = system.getMethod("overlay");
    private final Method disguisedMessage = disguised.getMethod("message");
    private final Method disguisedType = disguised.getMethod("chatType");
    private final Method decorate = bound.getMethod("decorate", vanillaComponent);
    private final Method signedType = signed.getMethod("chatType");
    private final Method unsignedContent = signed.getMethod("unsignedContent");
    private final Method signedBody = signed.getMethod("body");
    private final Method bodyContent = Class.forName("net.minecraft.network.chat.SignedMessageBody$Packed").getMethod("content");
    private final Method filter = signed.getMethod("filterMask");
    private final Method filterEmpty = Class.forName("net.minecraft.network.chat.FilterMask").getMethod("isEmpty");
    private final RecordComponent[] signedFields = signed.getRecordComponents();
    private final Constructor<?> signedConstructor = signed.getConstructor(Arrays.stream(signedFields).map(RecordComponent::getType).toArray(Class<?>[]::new));
    private final Object rawType;
    private final Method getHandle = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer").getMethod("getHandle");
    private final Field listener = Class.forName("net.minecraft.server.level.ServerPlayer").getField("connection");
    private final Field connection = Class.forName("net.minecraft.server.network.ServerCommonPacketListenerImpl").getField("connection");
    private final Field channel = Class.forName("net.minecraft.network.Connection").getField("channel");

    @SuppressWarnings({"unchecked", "rawtypes"})
    ChatPacketBridge() throws ReflectiveOperationException {
        Object parameter = Enum.valueOf((Class<? extends Enum>) Class.forName("net.minecraft.network.chat.ChatTypeDecoration$Parameter"), "CONTENT");
        Object rawDecoration = decoration.getConstructor(String.class, List.class, style)
                .newInstance("%s", List.of(parameter), style.getField("EMPTY").get(null));
        Object type = chatType.getConstructor(decoration, decoration).newInstance(rawDecoration, rawDecoration);
        rawType = holder.getMethod("direct", Object.class).invoke(null, type);
    }

    Channel channel(org.bukkit.entity.Player player) throws ReflectiveOperationException {
        return (Channel) channel.get(connection.get(listener.get(getHandle.invoke(player))));
    }

    Object format(Object packet, Messages.Topic topic, Map<String, Component> names) throws ReflectiveOperationException {
        if (system.isInstance(packet)) {
            if ((boolean) overlay.invoke(packet)) return packet;
            Component original = adventure(content.invoke(packet));
            Component formatted = ChatFormat.format(original, topic, names);
            return formatted == original ? packet : systemConstructor.newInstance(formatted, false);
        }
        if (disguised.isInstance(packet)) {
            Object type = disguisedType.invoke(packet);
            Component message = adventure(decorate.invoke(type, disguisedMessage.invoke(packet)));
            return systemConstructor.newInstance(ChatFormat.format(message, topic, names), false);
        }
        if (signed.isInstance(packet)) {
            // Leave filtered chat to the client's original filtering path. Preserve signatures,
            // indices, acknowledgements and signed body when replacing command-chat display text.
            if (!(boolean) filterEmpty.invoke(filter.invoke(packet))) return packet;
            Object type = signedType.invoke(packet);
            Object value = holder.getMethod("value").invoke(bound.getMethod("chatType").invoke(type));
            String key = (String) decoration.getMethod("translationKey").invoke(chatType.getMethod("chat").invoke(value));
            if (!Set.of("chat.type.announcement", "chat.type.emote", "commands.message.display.incoming",
                    "commands.message.display.outgoing", "chat.type.team.text", "chat.type.team.sent").contains(key)) return packet;
            Optional<?> unsigned = (Optional<?>) unsignedContent.invoke(packet);
            Object text = unsigned.isPresent() ? unsigned.get()
                    : asVanilla.invoke(null, Component.text((String) bodyContent.invoke(signedBody.invoke(packet))));
            Component formatted = ChatFormat.format(adventure(decorate.invoke(type, text)), topic, names);
            Object rawBound = bound.getConstructor(holder, vanillaComponent, Optional.class)
                    .newInstance(rawType, bound.getMethod("name").invoke(type), Optional.empty());
            Object[] values = new Object[signedFields.length];
            for (int i = 0; i < signedFields.length; i++) {
                values[i] = switch (signedFields[i].getName()) {
                    case "unsignedContent" -> Optional.of(asVanilla.invoke(null, formatted));
                    case "chatType" -> rawBound;
                    default -> signedFields[i].getAccessor().invoke(packet);
                };
            }
            return signedConstructor.newInstance(values);
        }
        return packet;
    }

    private Component adventure(Object value) throws ReflectiveOperationException {
        return (Component) asAdventure.invoke(null, value);
    }
}
