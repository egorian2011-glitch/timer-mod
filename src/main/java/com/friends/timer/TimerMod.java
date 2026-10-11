package com.friends.timer;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
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

    private static final int TICKS_PER_MINUTE = 20 * 60;

    private static MinecraftServer server;
    private static ServerBossEvent bar;

    private static boolean running = false;
    private static boolean specEnabled = true;
    private static int remainingTicks = 0;
    private static int totalTicks = 0;

    private static final Set<UUID> deadPlayers = new HashSet<>();
    private static final Set<UUID> pendingSpectators = new HashSet<>();

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
                                        .then(Commands.literal("on").executes(ctx -> setSpec(ctx, true)))
                                        .then(Commands.literal("off").executes(ctx -> setSpec(ctx, false)))
                                        .then(Commands.literal("stop").executes(ctx -> specStop(ctx))))
                ));

        ServerTickEvents.END_SERVER_TICK.register(this::onTick);

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (running && entity instanceof ServerPlayer player) {
                deadPlayers.add(player.getUUID());
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            ServerPlayer player = handler.getPlayer();
            if (running) {
                bar.addPlayer(player);
            }
            if (pendingSpectators.remove(player.getUUID())) {
                player.setGameMode(GameType.SPECTATOR);
                player.sendSystemMessage(Component.literal("Ты погиб в прошлом раунде — теперь ты спектатор.")
                        .withStyle(ChatFormatting.GRAY));
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(srv -> {
            bar.removeAllPlayers();
            running = false;
            deadPlayers.clear();
        });
    }

    private void onTick(MinecraftServer srv) {
        server = srv;
        if (!running) return;

        remainingTicks--;
        if (remainingTicks <= 0) {
            finish();
            return;
        }

        bar.setProgress(Math.max(0f, Math.min(1f, (float) remainingTicks / totalTicks)));
        if (remainingTicks % 20 == 0) {
            updateBarText();
        }

        if (srv.getTickCount() % 100 == 0) {
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

    private static int start(CommandContext<CommandSourceStack> ctx, int minutes) {
        if (running) {
            ctx.getSource().sendFailure(Component.literal("Таймер уже запущен. Используй /timer add <минуты> или /timer stop."));
            return 0;
        }
        server = ctx.getSource().getServer();
        running = true;
        totalTicks = minutes * TICKS_PER_MINUTE;
        remainingTicks = totalTicks;
        deadPlayers.clear();

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
            ctx.getSource().sendFailure(Component.literal("Таймер не запущен."));
            return 0;
        }
        running = false;
        bar.removeAllPlayers();
        deadPlayers.clear();
        broadcast(Component.literal("⏹ Таймер остановлен. Игроки в спектаторов не переводятся.")
                .withStyle(ChatFormatting.YELLOW));
        return 1;
    }

    private static int specStop(CommandContext<CommandSourceStack> ctx) {
        specEnabled = false;
        boolean wasRunning = running;
        if (running) {
            running = false;
            bar.removeAllPlayers();
        }
        deadPlayers.clear();
        broadcast(Component.literal((wasRunning ? "⏹ Таймер остановлен. " : "")
                + "Перевод погибших в спектаторы отключён.").withStyle(ChatFormatting.YELLOW));
        return 1;
    }

    private static int setSpec(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        specEnabled = enabled;
        broadcast(Component.literal(enabled
                ? "Перевод погибших в спектаторы после окончания времени: ВКЛ"
                : "Перевод погибших в спектаторы после окончания времени: ВЫКЛ")
                .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED));
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        String timerState = running
                ? "идёт, осталось " + formatTime(remainingTicks)
                : "не запущен";
        String specState = specEnabled ? "включён" : "выключен";
        ctx.getSource().sendSuccess(() -> Component.literal("⏱ Таймер: " + timerState
                + " | Перевод погибших в спектаторы: " + specState)
                .withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    private static void finish() {
        running = false;
        bar.removeAllPlayers();

        if (!specEnabled) {
            deadPlayers.clear();
            broadcast(Component.literal("⏰ Время вышло. Таймер остановлен. Перевод в спектаторы отключён.")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        int moved = 0;
        for (UUID uuid : deadPlayers) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) {
                p.setGameMode(GameType.SPECTATOR);
                moved++;
            } else {
                pendingSpectators.add(uuid);
            }
        }
        deadPlayers.clear();

        broadcast(Component.literal("⏰ Время вышло. Таймер остановлен. Погибшие переведены в спектаторы: "
                + moved + ". Живые продолжают игру.").withStyle(ChatFormatting.RED));
    }

    private static void broadcast(Component text) {
        if (server != null) {
            server.getPlayerList().broadcastSystemMessage(text, false);
        }
    }
}
