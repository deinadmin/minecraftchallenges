package de.carl.minecraftchallenges.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.function.Predicate;

public final class Messages {
    public enum Topic {
        SYSTEM("System", NamedTextColor.DARK_AQUA),
        CHALLENGE("Challenge", NamedTextColor.GOLD),
        LOBBY("Lobby", NamedTextColor.BLUE),
        SMP("SMP", NamedTextColor.GREEN);

        private final String label;
        private final NamedTextColor color;

        Topic(String label, NamedTextColor color) {
            this.label = label;
            this.color = color;
        }
    }

    public enum Type {
        USUAL(NamedTextColor.GRAY),
        INFO(NamedTextColor.GRAY),
        SUCCESS(NamedTextColor.GREEN),
        ERROR(NamedTextColor.RED);

        private final NamedTextColor color;

        Type(NamedTextColor color) {
            this.color = color;
        }
    }

    private static Predicate<Player> inChallenge = player -> false;
    private static Predicate<Player> inSmp = player -> false;

    private Messages() { }

    public static void setChallengeLocator(Predicate<Player> locator) {
        inChallenge = Objects.requireNonNull(locator);
    }

    public static void setSmpLocator(Predicate<Player> locator) {
        inSmp = Objects.requireNonNull(locator);
    }

    /** The prefix follows the recipient: Challenge inside a game lobby, SMP in the SMP, Lobby everywhere else, System for the console. */
    public static Topic topicFor(CommandSender recipient) {
        if (!(recipient instanceof Player player)) return Topic.SYSTEM;
        if (inChallenge.test(player)) return Topic.CHALLENGE;
        return inSmp.test(player) ? Topic.SMP : Topic.LOBBY;
    }

    public static Component smp(Type type, String text) {
        return message(Topic.SMP, type, text);
    }

    public static void send(CommandSender recipient, Type type, String text) {
        recipient.sendMessage(message(topicFor(recipient), type, text));
    }

    public static void send(CommandSender recipient, Type type, Component content) {
        recipient.sendMessage(message(topicFor(recipient), type, content));
    }

    /** For messages that are not tied to a place, such as permission or usage errors. */
    public static void sendSystem(CommandSender recipient, Type type, String text) {
        recipient.sendMessage(system(type, text));
    }

    public static Component system(Type type, String text) {
        return message(Topic.SYSTEM, type, text);
    }

    public static Component system(Type type, Component content) {
        return message(Topic.SYSTEM, type, content);
    }

    public static Component message(Topic topic, Type type, String text) {
        return message(topic, type, Component.text(text));
    }

    /** Keeps colours the content already carries (e.g. advancement names); only uncoloured parts get the type colour. */
    public static Component message(Topic topic, Type type, Component content) {
        return Component.text()
                .append(prefix(topic))
                .append(Component.text().color(type.color).append(content))
                .build();
    }

    /** Same layout as player chat: bold sender name, dark-grey separator, message. */
    public static Component prefix(Topic topic) {
        return Component.text()
                .append(Component.text(topic.label, topic.color, TextDecoration.BOLD))
                .append(Component.text(" » ", NamedTextColor.DARK_GRAY))
                .build();
    }

    public static Component challenge(Type type, String text) {
        return message(Topic.CHALLENGE, type, text);
    }

    public static Component lobby(Type type, String text) {
        return message(Topic.LOBBY, type, text);
    }

    public static Component challenge(Type type, Component content) {
        return message(Topic.CHALLENGE, type, content);
    }

    public static Component lobby(Type type, Component content) {
        return message(Topic.LOBBY, type, content);
    }
}
