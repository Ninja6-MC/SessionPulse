package com.ninja6.sessionpulse.notify;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A command reply reaches a player and the console alike, prefixed, and nothing while closed. */
class NotifierSendTest {

    private static <T> T untouchable(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    throw new AssertionError("the sender itself was touched: " + method.getName());
                }));
    }

    @Test
    @DisplayName("a player and the console each get one prefixed chat line through their audience")
    void playerAndConsoleGetAPrefixedChatLine() {
        List<CommandSender> asked = new ArrayList<>();
        TestNotifiers.RecordingAudience audience = new TestNotifiers.RecordingAudience();
        Notifier notifier = new Notifier(() -> NotifyFixture.parse(""), sender -> {
            asked.add(sender);
            return audience;
        });
        Player player = untouchable(Player.class);
        CommandSender console = untouchable(ConsoleCommandSender.class);

        notifier.send(player, "<red>hi <player></red>", Placeholders.none().player("<b>Ada"));
        notifier.send(console, "<red>hi</red>", Placeholders.none());
        notifier.send(console, null, Placeholders.none());

        assertEquals(List.of(player, console), asked);
        assertEquals(List.of("chat:§7[§bSessionPulse§7]§r §chi <b>Ada",
                "chat:§7[§bSessionPulse§7]§r §chi"), audience.calls());
    }

    @Test
    @DisplayName("a never-opened notifier sends nothing to either kind of sender")
    void closedSendsNothing() {
        Notifier notifier = NotifyFixture.notifier("");

        assertDoesNotThrow(() -> notifier.send(untouchable(Player.class), "<red>hi</red>",
                Placeholders.none()));
        assertDoesNotThrow(() -> notifier.send(untouchable(ConsoleCommandSender.class),
                "<red>hi</red>", Placeholders.none()));
    }
}
