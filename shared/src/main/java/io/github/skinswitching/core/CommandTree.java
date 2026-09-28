package io.github.skinswitching.core;

import com.mojang.brigadier.*;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.*;

public final class CommandTree {
    private CommandTree() {}
    public static <S> void register(CommandDispatcher<S> dispatcher, Command<S> set, Command<S> reset, Command<S> status) {
        for (String root : new String[]{"skin", "Skin", "skinswitch"}) {
            var node = LiteralArgumentBuilder.<S>literal(root).executes(status);
            for (String verb : new String[]{"switching", "Switching", "set"}) {
                node.then(LiteralArgumentBuilder.<S>literal(verb)
                        .then(RequiredArgumentBuilder.<S, String>argument("username", StringArgumentType.word()).executes(set)));
            }
            for (String verb : new String[]{"reset", "Reset"}) node.then(LiteralArgumentBuilder.<S>literal(verb).executes(reset));
            node.then(LiteralArgumentBuilder.<S>literal("status").executes(status));
            dispatcher.register(node);
        }
    }
}
