package de.carl.minecraftchallenges.chat;

import de.carl.minecraftchallenges.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Pure component formatting; safe on the network thread. Never flattens message content. */
public final class ChatFormat {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Pattern PREFIX = Pattern.compile("^(?:(?:Lobby|SMP|Challenge|System)|(?:(?:Admin|Mod) \\| )?[A-Za-z0-9_]{1,16}) » .*", Pattern.DOTALL);

    private ChatFormat() { }

    public static Component format(Component content, Messages.Topic topic, Map<String, Component> names) {
        // Paper uses a raw %s chat type for already-rendered unsigned player chat.
        // Unwrap only this transparent decoration so its existing prefix can be recognized.
        if (content instanceof TranslatableComponent raw && raw.key().equals("%s")
                && raw.arguments().size() == 1 && raw.arguments().getFirst().value() instanceof Component body) {
            Component unwrapped = Component.empty().style(raw.style()).append(body).children(
                    java.util.stream.Stream.concat(java.util.stream.Stream.of(body), raw.children().stream()).toList());
            return format(unwrapped, topic, names);
        }
        String plain = PLAIN.serialize(content);
        if (plain.isBlank() || PREFIX.matcher(plain).matches()) return content;
        if (content instanceof TranslatableComponent translated && translated.arguments().size() == 2
                && (translated.key().equals("chat.type.admin") || translated.key().equals("chat.type.announcement")
                || translated.key().equals("chat.type.emote"))) {
            Object sender = translated.arguments().getFirst().value();
            Object body = translated.arguments().get(1).value();
            if (sender instanceof Component name && body instanceof Component text) {
                Component ranked = names.get(PLAIN.serialize(name).toLowerCase(Locale.ROOT));
                if (ranked != null) {
                    Component message = Component.text().color(NamedTextColor.GRAY)
                            .append(translated.key().equals("chat.type.emote") ? Component.text("* ") : Component.empty())
                            .append(text).build();
                    return ranked.append(Component.text(" » ", NamedTextColor.DARK_GRAY)).append(message)
                            .append(Component.empty().children(content.children()));
                }
            }
        }
        return Messages.message(topic, Messages.Type.INFO, content);
    }
}
