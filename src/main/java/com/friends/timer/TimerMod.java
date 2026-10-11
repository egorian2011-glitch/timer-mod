package com.friends.timer;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.GameType;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class TimerMod implements ModInitializer {

    /** Надпись, которая появляется по центру экрана, когда таймер закончился. */
    private static final String HUNT_TITLE = "ПИДАБИР ВЫШЕЛ НА ОХОТУ";

    private static final int TICKS_PER_MINUTE = 20 * 60;

    private static MinecraftServer server;
    private static ServerBossEvent bar;

    /** Идёт обратный отсчёт. */
    private static boolean running = false;
    /** Таймер закончился, охота идёт: все, кто умер, становятся спектаторами. */
    private static boolean hunting = false;
    /** Если выключить, то после окончания таймера охоты не будет. */
    private static boolean specEnabled = true;

    private static int remainingTicks = 0;
    private static int totalTicks = 0;

    /** Игроки, которых мод перевёл в спектаторы во время охоты. */
    private static final Set<UUID> spectated = new HashSet<>();
    /** Спектаторы, которые были офлайн, когда охоту выключили. */
    private static final Set<UUID> reviveOnJoin = new HashSet<>();

    private static boolean isOp(CommandSourceStack s) {
        return s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    @Override
    public void onInitialize() {
        bar = new ServerBossEvent(UUID.randomUUID(), Component.literal(""),
                BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(
                        Commands.literal("timer")
                                .executes(ctx -> status(ctx))
                                .then(Commands.literal("start")
                                        .requires(TimerMod::isOp)
                                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 1440))
                                                .executes(ctx -> start(ctx, IntegerArgumentType.getInteger(ctx, "minutes")))))
                                .then(Commands.literal("add")
                                        .requires(TimerMod::isOp)
                                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 1440))
                                                .executes(ctx -> add(ctx, IntegerArgumentType.getInteger(ctx, "minutes")))))
                                .then(Commands.literal("stop")
                                        .requires(TimerMod::isOp)
                                        .executes(ctx -> stop(ctx)))
                                .then(Commands.literal("spec")
                                        .requires(TimerMod::isOp)
                                        .then(Commands.literal("off").executes(ctx -> specOff(ctx))))
                ));

        ServerTickEvents.END_SERVER_TICK.register(this::onTick);

        // Во время охоты любой, кто умер и возродился, становится спектатором
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (hunting && !alive) {
                newPlayer.setGameMode(GameType.SPECTATOR);
                spectated.add(newPlayer.getUUID());
                newPlayer.sendSystemMessage(Component.literal("☠ Ты погиб на охоте — теперь ты спектатор.")
                        .withStyle(ChatFormatting.GRAY));
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            ServerPlayer player = handler.getPlayer();
            if (running || hunting) {
                bar.addPlayer(player);
            }
            if (reviveOnJoin.remove(player.getUUID())) {
                player.setGameMode(GameType.SURVIVAL);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(srv -> {
            bar.removeAllPlayers();
            running = false;
            hunting = false;
            spectated.clear();
        });
    }

    // ---------------------------------------------------------------- tick

    private void onTick(MinecraftServer srv) {
        server = srv;

        if (running) {
            remainingTicks--;
            if (remainingTicks <= 0) {
                finish();
                return;
            }
            bar.setProgress(Math.max(0f, Math.min(1f, (float) remainingTicks / totalTicks)));
            if (remainingTicks % 20 == 0) {
                updateBarText();
            }
        }

        if ((running || hunting) && srv.getTickCount() % 100 == 0) {
            for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
                bar.addPlayer(p);
            }
        }
    }

    private static void updateBarText() {
        float percent = (float) remainingTicks / totalTicks;
        if (percent > 0.5f) bar.setColor(BossEvent.BossBarColor.GREEN);
        else if (percent > 0.2f) bar.setColor(BossEvent.BossBarColor.YELLOW);
        else bar.setColor(BossEvent.BossBarColor.RED);
        bar.setName(Component.literal("⏳ Осталось: " + formatTime(remainingTicks)));
    }

    private static String formatTime(int ticks) {
        int totalSeconds = (ticks + 19) / 20;
        return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    // ------------------------------------------------------------ commands

    private static int start(CommandContext<CommandSourceStack> ctx, int minutes) {
        if (running) {
            ctx.getSource().sendFailure(Component.literal("Таймер уже запущен. Используй /timer add <минуты> или /timer stop."));
            return 0;
        }
        if (hunting) {
            ctx.getSource().sendFailure(Component.literal("Сейчас идёт охота. Сначала выключи её: /timer spec off"));
            return 0;
        }
        server = ctx.getSource().getServer();
        running = true;
        specEnabled = true;
        totalTicks = minutes * TICKS_PER_MINUTE;
        remainingTicks = totalTicks;

        bar.removeAllPlayers();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            bar.addPlayer(p);
        }
        bar.setProgress(1f);
        updateBarText();

        broadcast(Component.literal("▶ Таймер запущен на " + minutes + " мин.").withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int add(CommandContext<CommandSourceStack> ctx, int minutes) {
        if (!running) {
            ctx.getSource().sendFailure(Component.literal("Таймер не запущен. Используй /timer start <минуты>."));
            return 0;
        }
        remainingTicks += minutes * TICKS_PER_MINUTE;
        totalTicks += minutes * TICKS_PER_MINUTE;
        updateBarText();
        broadcast(Component.literal("➕ Добавлено " + minutes + " мин. Осталось: " + formatTime(remainingTicks))
                .withStyle(ChatFormatting.AQUA));
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        if (!running) {
            if (hunting) {
                ctx.getSource().sendFailure(Component.literal("Сейчас идёт охота, а не таймер. Чтобы её закончить: /timer spec off"));
            } else {
                ctx.getSource().sendFailure(Component.literal("Таймер не запущен."));
            }
            return 0;
        }
        running = false;
        bar.removeAllPlayers();
        broadcast(Component.literal("⏹ Таймер остановлен. Охоты не будет.").withStyle(ChatFormatting.YELLOW));
        return 1;
    }

    private static int specOff(CommandContext<CommandSourceStack> ctx) {
        specEnabled = false;
        if (hunting) {
            endHunt();
        } else if (running) {
            broadcast(Component.literal("Перевод в спектаторы отключён: после окончания таймера охоты не будет.")
                    .withStyle(ChatFormatting.YELLOW));
        } else {
            ctx.getSource().sendSuccess(() -> Component.literal("Перевод в спектаторы отключён.")
                    .withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        String state;
        if (running) state = "идёт, осталось " + formatTime(remainingTicks)
                + (specEnabled ? "" : " (охоты в конце не будет)");
        else if (hunting) state = "ОХОТА идёт, погибшие становятся спектаторами";
        else state = "не запущен";
        ctx.getSource().sendSuccess(() -> Component.literal("⏱ Таймер: " + state)
                .withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    // -------------------------------------------------------- end / hunt

    private static void finish() {
        running = false;
        bar.removeAllPlayers();

        if (!specEnabled) {
            broadcast(Component.literal("⏰ Время вышло. Таймер остановлен.").withStyle(ChatFormatting.RED));
            return;
        }
        startHunt();
    }

    private static void startHunt() {
        hunting = true;
        spectated.clear();

        // Затемнение экрана + звук + надпись по центру на секунду
        runCmd("effect give @a minecraft:blindness 4 0 true");
        runCmd("execute as @a at @s run playsound minecraft:entity.wither.spawn master @s ~ ~ ~ 1 0.8");
        runCmd("title @a times 5 20 10");
        runCmd("title @a title {\"text\":\"" + HUNT_TITLE + "\",\"color\":\"dark_red\",\"bold\":true}");

        // Красная полоска на время охоты
        bar.setColor(BossEvent.BossBarColor.RED);
        bar.setProgress(1f);
        bar.setName(Component.literal("☠ ОХОТА ИДЁТ — погибшие становятся спектаторами"));
        bar.removeAllPlayers();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            bar.addPlayer(p);
        }

        broadcast(Component.literal("⏰ Время вышло! Теперь каждый, кто умрёт, станет спектатором.")
                .withStyle(ChatFormatting.DARK_RED));
    }

    private static void endHunt() {
        hunting = false;
        bar.removeAllPlayers();

        int revived = 0;
        for (UUID uuid : spectated) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) {
                p.setGameMode(GameType.SURVIVAL);
                revived++;
            } else {
                reviveOnJoin.add(uuid);
            }
        }
        spectated.clear();

        broadcast(Component.literal("✔ Охота закончена. Возвращено в игру: " + revived + ".")
                .withStyle(ChatFormatting.GREEN));
    }

    // -------------------------------------------------------------- utils

    private static void runCmd(String command) {
        if (server == null) return;
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), command);
    }

    private static void broadcast(Component text) {
        if (server != null) {
            server.getPlayerList().broadcastSystemMessage(text, false);
        }
    }
}
