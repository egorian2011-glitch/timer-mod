package com.friends.timer;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class TimerMod implements ModInitializer {

    private static final int TICKS_PER_MINUTE = 20 * 60;

    private static MinecraftServer server;
    private static ServerBossBar bar;

    private static boolean running = false;
    private static boolean specEnabled = true;
    private static int remainingTicks = 0;
    private static int totalTicks = 0;

    private static final Set<UUID> deadPlayers = new HashSet<>();
    private static final Set<UUID> pendingSpectators = new HashSet<>();

    @Override
    public void onInitialize() {
        bar = new ServerBossBar(Text.literal(""), BossBar.Color.GREEN, BossBar.Style.PROGRESS);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(
                        literal("timer")
                                .executes(ctx -> status(ctx))
                                .then(literal("start")
                                        .requires(s -> s.hasPermissionLevel(2))
                                        .then(argument("minutes", IntegerArgumentType.integer(1, 1440))
                                                .executes(ctx -> start(ctx, IntegerArgumentType.getInteger(ctx, "minutes")))))
                                .then(literal("add")
                                        .requires(s -> s.hasPermissionLevel(2))
                                        .then(argument("minutes", IntegerArgumentType.integer(1, 1440))
                                                .executes(ctx -> add(ctx, IntegerArgumentType.getInteger(ctx, "minutes")))))
                                .then(literal("stop")
                                        .requires(s -> s.hasPermissionLevel(2))
                                        .executes(ctx -> stop(ctx)))
                                .then(literal("spec")
                                        .requires(s -> s.hasPermissionLevel(2))
                                        .then(literal("on").executes(ctx -> setSpec(ctx, true)))
                                        .then(literal("off").executes(ctx -> setSpec(ctx, false)))
                                        .then(literal("stop").executes(ctx -> specStop(ctx))))
                ));

        ServerTickEvents.END_SERVER_TICK.register(this::onTick);

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (running && entity instanceof ServerPlayerEntity player) {
                deadPlayers.add(player.getUuid());
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            ServerPlayerEntity player = handler.getPlayer();
            if (running) {
                bar.addPlayer(player);
            }
            if (pendingSpectators.remove(player.getUuid())) {
                player.changeGameMode(GameMode.SPECTATOR);
                player.sendMessage(Text.literal("Ты погиб в прошлом раунде — теперь ты спектатор.")
                        .formatted(Formatting.GRAY), false);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(srv -> {
            bar.clearPlayers();
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

        bar.setPercent(Math.max(0f, Math.min(1f, (float) remainingTicks / totalTicks)));
        if (remainingTicks % 20 == 0) {
            updateBarText();
        }

        if (srv.getTicks() % 100 == 0) {
            for (ServerPlayerEntity p : srv.getPlayerManager().getPlayerList()) {
                bar.addPlayer(p);
            }
        }
    }

    private static void updateBarText() {
        float percent = (float) remainingTicks / totalTicks;
        if (percent > 0.5f) bar.setColor(BossBar.Color.GREEN);
        else if (percent > 0.2f) bar.setColor(BossBar.Color.YELLOW);
        else bar.setColor(BossBar.Color.RED);
        bar.setName(Text.literal("⏳ Осталось: " + formatTime(remainingTicks)));
    }

    private static String formatTime(int ticks) {
        int totalSeconds = (ticks + 19) / 20;
        return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private static int start(CommandContext<ServerCommandSource> ctx, int minutes) {
        if (running) {
            ctx.getSource().sendError(Text.literal("Таймер уже запущен. Используй /timer add <минуты> или /timer stop."));
            return 0;
        }
        server = ctx.getSource().getServer();
        running = true;
        totalTicks = minutes * TICKS_PER_MINUTE;
        remainingTicks = totalTicks;
        deadPlayers.clear();

        bar.clearPlayers();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            bar.addPlayer(p);
        }
        bar.setPercent(1f);
        updateBarText();

        broadcast(Text.literal("▶ Таймер запущен на " + minutes + " мин.").formatted(Formatting.GREEN));
        return 1;
    }

    private static int add(CommandContext<ServerCommandSource> ctx, int minutes) {
        if (!running) {
            ctx.getSource().sendError(Text.literal("Таймер не запущен. Используй /timer start <минуты>."));
            return 0;
        }
        remainingTicks += minutes * TICKS_PER_MINUTE;
        totalTicks += minutes * TICKS_PER_MINUTE;
        updateBarText();
        broadcast(Text.literal("➕ Добавлено " + minutes + " мин. Осталось: " + formatTime(remainingTicks))
                .formatted(Formatting.AQUA));
        return 1;
    }

    private static int stop(CommandContext<ServerCommandSource> ctx) {
        if (!running) {
            ctx.getSource().sendError(Text.literal("Таймер не запущен."));
            return 0;
        }
        running = false;
        bar.clearPlayers();
        deadPlayers.clear();
        broadcast(Text.literal("⏹ Таймер остановлен. Игроки в спектаторов не переводятся.")
                .formatted(Formatting.YELLOW));
        return 1;
    }

    private static int specStop(CommandContext<ServerCommandSource> ctx) {
        specEnabled = false;
        boolean wasRunning = running;
        if (running) {
            running = false;
            bar.clearPlayers();
        }
        deadPlayers.clear();
        broadcast(Text.literal((wasRunning ? "⏹ Таймер остановлен. " : "")
                + "Перевод погибших в спектаторы отключён.").formatted(Formatting.YELLOW));
        return 1;
    }

    private static int setSpec(CommandContext<ServerCommandSource> ctx, boolean enabled) {
        specEnabled = enabled;
        broadcast(Text.literal(enabled
                ? "Перевод погибших в спектаторы после окончания времени: ВКЛ"
                : "Перевод погибших в спектаторы после окончания времени: ВЫКЛ")
                .formatted(enabled ? Formatting.GREEN : Formatting.RED));
        return 1;
    }

    private static int status(CommandContext<ServerCommandSource> ctx) {
        String timerState = running
                ? "идёт, осталось " + formatTime(remainingTicks)
                : "не запущен";
        String specState = specEnabled ? "включён" : "выключен";
        ctx.getSource().sendFeedback(() -> Text.literal("⏱ Таймер: " + timerState
                + " | Перевод погибших в спектаторы: " + specState)
                .formatted(Formatting.GOLD), false);
        return 1;
    }

    private static void finish() {
        running = false;
        bar.clearPlayers();

        if (!specEnabled) {
            deadPlayers.clear();
            broadcast(Text.literal("⏰ Время вышло. Таймер остановлен. Перевод в спектаторы отключён.")
                    .formatted(Formatting.RED));
            return;
        }

        int moved = 0;
        for (UUID uuid : deadPlayers) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            if (p != null) {
                p.changeGameMode(GameMode.SPECTATOR);
                moved++;
            } else {
                pendingSpectators.add(uuid);
            }
        }
        deadPlayers.clear();

        broadcast(Text.literal("⏰ Время вышло. Таймер остановлен. Погибшие переведены в спектаторы: "
                + moved + ". Живые продолжают игру.").formatted(Formatting.RED));
    }

    private static void broadcast(Text text) {
        if (server != null) {
            server.getPlayerManager().broadcast(text, false);
        }
    }
}
