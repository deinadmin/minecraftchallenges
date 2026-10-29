package de.carl.minecraftchallenges.chat;

import de.carl.minecraftchallenges.util.Messages;
import net.kyori.adventure.text.*;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import java.util.Map;

public final class ChatFormatTest {
    private static int checks;
    private static final Map<String, Component> NAMES = Map.of("carl", Component.text("Admin | Carl", NamedTextColor.RED));
    public static void main(String[] args) {
        for (Messages.Topic topic : Messages.Topic.values()) {
            Component original = Component.translatable("commands.time.set", Component.text("1000"));
            Component formatted = ChatFormat.format(original, topic, NAMES);
            check(formatted.equals(Messages.message(topic, Messages.Type.INFO, original)), "Context prefix " + topic);
            check(ChatFormat.format(formatted, topic, NAMES) == formatted, "No double prefix " + topic);
        }
        Component interactive = Component.text("Click to teleport", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.suggestCommand("/tp Carl"))
                .hoverEvent(HoverEvent.showText(Component.text("Teleport")));
        Component formatted = ChatFormat.format(interactive, Messages.Topic.SMP, NAMES);
        check(contains(formatted, interactive), "Preserve original click/hover events and color");
        for (String key : new String[]{"chat.type.admin", "chat.type.announcement", "chat.type.emote"}) {
            Component result = ChatFormat.format(Component.translatable(key, Component.text("Carl"), interactive), Messages.Topic.LOBBY, NAMES);
            check(PlainTextComponentSerializer.plainText().serialize(result).startsWith("Admin | Carl » "), "Rank prefix " + key);
            check(contains(result, interactive), "Preserve announcement content " + key);
        }
        Component existing = Component.text("Admin | Carl").append(Component.text(" » Hello"));
        check(ChatFormat.format(existing, Messages.Topic.LOBBY, NAMES) == existing, "Preserve existing ranked messages");
        Component raw = Component.translatable("%s", existing);
        check(PlainTextComponentSerializer.plainText().serialize(ChatFormat.format(raw, Messages.Topic.SMP, NAMES))
                .equals("Admin | Carl » Hello"), "Already-rendered unsigned chat has no second prefix");
        check(ChatFormat.format(Component.empty(), Messages.Topic.SMP, NAMES).equals(Component.empty()), "Preserve blank separators");
        Component console = Component.translatable("chat.type.announcement", Component.text("Server"), Component.text("Hello"));
        check(ChatFormat.format(console, Messages.Topic.SMP, NAMES).equals(Messages.message(Messages.Topic.SMP, Messages.Type.INFO, console)),
                "Unknown sender uses context prefix");
        Component whisper = Component.translatable("commands.message.display.incoming", Component.text("Carl"), interactive);
        check(contains(ChatFormat.format(whisper, Messages.Topic.SMP, NAMES), whisper), "Preserve translated private message direction");
        System.out.println(checks + " chat formatting regression checks passed.");
    }
    private static boolean contains(Component tree, Component expected) {
        return tree.equals(expected) || tree.children().stream().anyMatch(child -> contains(child, expected));
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
