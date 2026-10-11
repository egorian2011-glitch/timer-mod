package com.friends.timer;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class TimerMod implements ModInitializer {

    /** Надпись по центру экрана, когда таймер закончился. */
    private static final String PVP_TITLE = "ПВП НАЧИНАЕТСЯ";
    private static final String PVP_SUBTITLE = "Кто умрёт — станет спектатором";

    private static final int TICKS_PER_MINUTE = 20 * 60;

    private static MinecraftServer server;
    private static ServerBossEvent bar;

    /** Идёт обратный отсчёт. */
    private static boolean running = false;
    /** Таймер закончился, ПВП идёт: все, кто умер, становятся спектаторами. */
    private static boolean pvp = false;
    /** Если выключить, то после окончания таймера ПВП не начнётся. */
    private static boolean specEnabled = true;

    private static int remainingTicks = 0;
    private static int totalTicks = 0;

    /** Игроки, которые умерли во время ПВП. */
    private static final Set<UUID> fallen = new HashSet<>();
    /** Спектаторы, которые были офлайн, когда ПВП выключили. */
    private static final Set<UUID> reviveOnJoin = new HashSet<>();
    /** Счётчик убийств в ПВП (ник -> убийства). */
    private static final Map<String, Integer> kills = new HashMap<>();

    private static int pvpStartPlayers = 0;
    private static boolean winnerAnnounced = false;
    private static List<String> winnerNames = new ArrayList<>();
    private static int winnerFxTicks = 0;

    /** true, если мод сейчас отключил сохранение инвентаря и должен его вернуть. */
    private static boolean keepInvDisabled = false;

    private static boolean isOp(CommandSourceStack s) {
        return s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    /** Сколько победителей: от 4 игроков — двое, иначе один. */
    private static int winnersNeeded() {
        return pvpStartPlayers >= 4 ? 2 : 1;
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

        // Смерть во время ПВП: сообщение, звук, проверка победителей
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!pvp) return;
            if (!(entity instanceof ServerPlayer victim)) return;
            if (!fallen.add(victim.getUUID())) return;

            String victimName = victim.getName().getString();
            Component msg;
            if (source.getEntity() instanceof ServerPlayer killer && killer != victim) {
                String killerName = killer.getName().getString();
                kills.merge(killerName, 1, Integer::sum);
                msg = Component.literal("☠ " + victimName + " убит игроком " + killerName)
                        .withStyle(ChatFormatting.RED);
            } else {
                msg = Component.literal("☠ " + victimName + " погиб")
                        .withStyle(ChatFormatting.RED);
            }
            broadcast(msg);
            soundAll("minecraft:entity.lightning_bolt.thunder", "0.8 1");

            updatePvpBar();
            if (!winnerAnnounced && pvpStartPlayers > winnersNeeded()) {
                int alive = aliveCount();
                if (alive >= 1 && alive <= winnersNeeded()) {
                    announceWinners();
                }
            }
        });

        // Во время ПВП возродившийся после смерти становится спектатором
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (pvp && !alive) {
                newPlayer.setGameMode(GameType.SPECTATOR);
                fallen.add(newPlayer.getUUID());
                newPlayer.sendSystemMessage(Component.literal("☠ Ты погиб в ПВП — теперь ты спектатор.")
                        .withStyle(ChatFormatting.GRAY));
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
            ServerPlayer player = handler.getPlayer();
            if (running || pvp) {
                bar.addPlayer(player);
            }
            if (reviveOnJoin.remove(player.getUUID())) {
                player.setGameMode(GameType.SURVIVAL);
            }
            if (pvp && fallen.contains(player.getUUID())) {
                player.setGameMode(GameType.SPECTATOR);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(srv -> {
            server = srv;
            if (keepInvDisabled) {
                setKeepInventory(true);
            }
            bar.removeAllPlayers();
            running = false;
            pvp = false;
            fallen.clear();
            kills.clear();
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
                countdown(remainingTicks / 20);
            }
        }

        if (pvp) {
            if (srv.getTickCount() % 20 == 0) {
                updatePvpBar();
            }
            // Во время ПВП всегда день и ясная погода
            if (srv.getTickCount() % 100 == 0) {
                runCmd("time set day");
                runCmd("weather clear");
            }
        }

        if ((running || pvp) && srv.getTickCount() % 100 == 0) {
            for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
                bar.addPlayer(p);
            }
        }

        // Частицы вокруг победителей
        if (winnerFxTicks > 0) {
            winnerFxTicks--;
            if (winnerFxTicks % 10 == 0) {
                for (String name : winnerNames) {
                    runCmd("execute as @a[name=" + name + ",limit=1] at @s run "
                            + "particle minecraft:totem_of_undying ~ ~1 ~ 0.6 1 0.6 0.4 60");
                }
            }
        }
    }

    /** Предупреждения и обратный отсчёт. sec = сколько секунд осталось. */
    private static void countdown(int sec) {
        if (sec == 300 || sec == 60 || sec == 30) {
            String text = sec == 300 ? "5 минут" : sec == 60 ? "1 минута" : "30 секунд";
            broadcast(Component.literal("⏳ До начала ПВП осталось: " + text).withStyle(ChatFormatting.GOLD));
            soundAll("minecraft:block.note_block.pling", "1 1");
        } else if (sec >= 1 && sec <= 10) {
            String color = sec <= 3 ? "dark_red" : "red";
            titleAll(String.valueOf(sec), color, "", "gray", "0 15 5");
            soundAll("minecraft:block.note_block.hat", sec <= 3 ? "1 1.6" : "1 1");
        }
    }

    private static void updateBarText() {
        float percent = (float) remainingTicks / totalTicks;
        if (percent > 0.5f) bar.setColor(BossEvent.BossBarColor.GREEN);
        else if (percent > 0.2f) bar.setColor(BossEvent.BossBarColor.YELLOW);
        else bar.setColor(BossEvent.BossBarColor.RED);
        bar.setName(Component.literal("⏳ Осталось: " + formatTime(remainingTicks)));
    }

    private static void updatePvpBar() {
        if (server == null) return;
        int alive = aliveCount();
        bar.setColor(BossEvent.BossBarColor.RED);
        bar.setName(Component.literal("⚔ ПВП ИДЁТ — живых: " + alive));
        float progress = pvpStartPlayers <= 0 ? 1f : (float) alive / pvpStartPlayers;
        bar.setProgress(Math.max(0f, Math.min(1f, progress)));
    }

    private static String formatTime(int ticks) {
        int totalSeconds = (ticks + 19) / 20;
        return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private static int aliveCount() {
        int n = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!p.isSpectator() && !fallen.contains(p.getUUID())) n++;
        }
        return n;
    }

    // ------------------------------------------------------------ commands

    private static int start(CommandContext<CommandSourceStack> ctx, int minutes) {
        if (running) {
            ctx.getSource().sendFailure(Component.literal("Таймер уже запущен. Используй /timer add <минуты> или /timer stop."));
            return 0;
        }
        if (pvp) {
            ctx.getSource().sendFailure(Component.literal("Сейчас идёт ПВП. Сначала выключи его: /timer spec off"));
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

        titleAll(minutes + " МИН", "green", "Таймер запущен", "gray", "5 30 10");
        soundAll("minecraft:block.beacon.activate", "1 1");
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
        soundAll("minecraft:block.note_block.chime", "1 1.2");
        broadcast(Component.literal("➕ Добавлено " + minutes + " мин. Осталось: " + formatTime(remainingTicks))
                .withStyle(ChatFormatting.AQUA));
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        if (!running) {
            if (pvp) {
                ctx.getSource().sendFailure(Component.literal("Сейчас идёт ПВП, а не таймер. Чтобы закончить его: /timer spec off"));
            } else {
                ctx.getSource().sendFailure(Component.literal("Таймер не запущен."));
            }
            return 0;
        }
        running = false;
        bar.removeAllPlayers();
        soundAll("minecraft:block.note_block.bass", "1 0.7");
        broadcast(Component.literal("⏹ Таймер остановлен. ПВП не начнётся.").withStyle(ChatFormatting.YELLOW));
        return 1;
    }

    private static int specOff(CommandContext<CommandSourceStack> ctx) {
        specEnabled = false;
        server = ctx.getSource().getServer();
        if (pvp) {
            endPvp();
        } else if (running) {
            running = false;
            bar.removeAllPlayers();
            broadcast(Component.literal("⏹ Таймер остановлен, перевод в спектаторы отключён. Все возрождаются как обычно.")
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
                + (specEnabled ? "" : " (ПВП в конце не будет)");
        else if (pvp) state = "ПВП идёт, живых: " + aliveCount();
        else state = "не запущен";
        ctx.getSource().sendSuccess(() -> Component.literal("⏱ Таймер: " + state)
                .withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    // ------------------------------------------------------------ pvp

    private static void finish() {
        running = false;
        bar.removeAllPlayers();

        if (!specEnabled) {
            broadcast(Component.literal("⏰ Время вышло. Таймер остановлен.").withStyle(ChatFormatting.RED));
            return;
        }
        startPvp();
    }

    private static void startPvp() {
        pvp = true;
        fallen.clear();
        kills.clear();
        winnerAnnounced = false;
        winnerNames = new ArrayList<>();
        winnerFxTicks = 0;
        pvpStartPlayers = aliveCount();

        // Затемнение, звуки, надпись по центру
        runCmd("effect give @a minecraft:blindness 4 0 true");
        soundAll("minecraft:entity.wither.spawn", "1 0.8");
        soundAll("minecraft:entity.lightning_bolt.thunder", "1 0.6");
        titleAll(PVP_TITLE, "dark_red", PVP_SUBTITLE, "gray", "5 25 10");

        // Свечение на 10 секунд, чтобы все видели друг друга
        runCmd("effect give @a minecraft:glowing 10 0 true");

        // Во время ПВП: день, ясная погода, инвентарь после смерти НЕ сохраняется
        runCmd("time set day");
        runCmd("weather clear");
        setKeepInventory(false);

        bar.removeAllPlayers();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            bar.addPlayer(p);
        }
        updatePvpBar();

        broadcast(Component.literal("⚔ ПВП началось! Теперь каждый, кто умрёт, станет спектатором. "
                + "Сохранение инвентаря отключено!").withStyle(ChatFormatting.DARK_RED));
    }

    private static void announceWinners() {
        List<String> names = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!p.isSpectator() && !fallen.contains(p.getUUID())) {
                names.add(p.getName().getString());
            }
        }
        if (names.isEmpty()) return;

        winnerAnnounced = true;
        winnerNames = names;
        winnerFxTicks = 100;

        String joined = String.join(" и ", names);
        boolean many = names.size() > 1;

        titleAll(joined, "gold", many ? "ПОБЕДИТЕЛИ ПВП" : "ПОБЕДИТЕЛЬ ПВП", "yellow", "10 80 20");
        soundAll("minecraft:ui.toast.challenge_complete", "1 1");
        broadcast(Component.literal("★ " + (many ? "ПОБЕДИТЕЛИ: " : "ПОБЕДИТЕЛЬ: ") + joined + " ★")
                .withStyle(ChatFormatting.GOLD));

        String topName = null;
        int topKills = 0;
        for (Map.Entry<String, Integer> e : kills.entrySet()) {
            if (e.getValue() > topKills) {
                topKills = e.getValue();
                topName = e.getKey();
            }
        }
        if (topName != null) {
            broadcast(Component.literal("🗡 Больше всего убийств: " + topName + " (" + topKills + ")")
                    .withStyle(ChatFormatting.YELLOW));
        }

        // После выигрыша сохранение инвентаря возвращается
        setKeepInventory(true);
        broadcast(Component.literal("🔒 Сохранение инвентаря снова включено.").withStyle(ChatFormatting.GREEN));
    }

    private static void endPvp() {
        pvp = false;
        bar.removeAllPlayers();
        winnerFxTicks = 0;

        int revived = 0;
        for (UUID uuid : fallen) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) {
                p.setGameMode(GameType.SURVIVAL);
                revived++;
            } else {
                reviveOnJoin.add(uuid);
            }
        }
        fallen.clear();
        kills.clear();

        if (keepInvDisabled) {
            setKeepInventory(true);
        }

        broadcast(Component.literal("✔ ПВП закончено. Возвращено в игру: " + revived + ".")
                .withStyle(ChatFormatting.GREEN));
    }

    // -------------------------------------------------------------- utils

    private static void setKeepInventory(boolean value) {
        runCmd("gamerule keep_inventory " + value);
        keepInvDisabled = !value;
    }

    private static void titleAll(String title, String color, String subtitle, String subColor, String times) {
        runCmd("title @a times " + times);
        runCmd("title @a subtitle {\"text\":\"" + subtitle + "\",\"color\":\"" + subColor + "\"}");
        runCmd("title @a title {\"text\":\"" + title + "\",\"color\":\"" + color + "\",\"bold\":true}");
    }

    /** Звук слышен всем игрокам независимо от расстояния. volPitch = "громкость высота". */
    private static void soundAll(String sound, String volPitch) {
        runCmd("execute as @a at @s run playsound " + sound + " master @s ~ ~ ~ " + volPitch);
    }

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
