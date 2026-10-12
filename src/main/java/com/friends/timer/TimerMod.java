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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

public class TimerMod implements ModInitializer {

    /** Надпись по центру экрана, когда таймер закончился. */
    private static final String PVP_TITLE = "ПВП НАЧИНАЕТСЯ";
    private static final String PVP_SUBTITLE = "Погибшие становятся спектаторами";

    private static final int TICKS_PER_MINUTE = 20 * 60;

    /** Голосование за продление. */
    private static final int MAX_EXTENSIONS = 3;
    private static final int EXTENSION_MINUTES = 2;
    private static final int VOTE_DURATION_TICKS = 20 * 20;

    /** Через сколько тиков ПВП все начинают светиться (5 минут). */
    private static final int GLOW_AFTER_TICKS = 5 * TICKS_PER_MINUTE;

    private static final Random RANDOM = new Random();

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

    // --- голосование ---
    private static boolean voteActive = false;
    private static boolean voteDone = false;
    private static int voteTicksLeft = 0;
    private static int extensionsUsed = 0;
    private static final Map<UUID, Boolean> votes = new HashMap<>();

    // --- ПВП ---
    private static final Set<UUID> fallen = new HashSet<>();
    private static final Set<UUID> reviveOnJoin = new HashSet<>();
    private static final Map<String, Integer> kills = new HashMap<>();

    private static int pvpStartKeys = 0;
    private static int pvpStartPlayers = 0;
    private static int pvpTicks = 0;
    private static boolean glowAnnounced = false;

    private static boolean winnerAnnounced = false;
    private static String winnerSelector = "";
    private static int winnerFxTicks = 0;

    /** true, если мод сейчас отключил сохранение инвентаря и должен его вернуть. */
    private static boolean keepInvDisabled = false;

    private static boolean isOp(CommandSourceStack s) {
        return s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    @Override
    public void onInitialize() {
        bar = new ServerBossEvent(UUID.randomUUID(), Component.literal(""),
                BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
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
                            // голосовать может любой игрок
                            .then(Commands.literal("vote")
                                    .then(Commands.literal("yes").executes(ctx -> vote(ctx, true)))
                                    .then(Commands.literal("no").executes(ctx -> vote(ctx, false))))
            );

            dispatcher.register(
                    Commands.literal("randomteam")
                            .requires(TimerMod::isOp)
                            .executes(ctx -> randomTeam(ctx))
            );
        });

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
            checkWinners();
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
            voteActive = false;
            fallen.clear();
            kills.clear();
        });
    }

    // ---------------------------------------------------------------- tick

    private void onTick(MinecraftServer srv) {
        server = srv;
        int tick = srv.getTickCount();

        if (running) {
            remainingTicks--;
            if (remainingTicks <= 0) {
                voteActive = false;
                finish();
                return;
            }
            bar.setProgress(Math.max(0f, Math.min(1f, (float) remainingTicks / totalTicks)));
            if (remainingTicks % 20 == 0) {
                updateBarText();
                countdown(remainingTicks / 20);
            }

            // За минуту до конца — голосование за продление
            if (!voteActive && !voteDone
                    && extensionsUsed < MAX_EXTENSIONS
                    && totalTicks > TICKS_PER_MINUTE
                    && remainingTicks <= TICKS_PER_MINUTE) {
                startVote();
            }

            if (voteActive) {
                voteTicksLeft--;
                if (voteTicksLeft <= 0) {
                    resolveVote();
                }
            }
        }

        if (pvp) {
            pvpTicks++;

            if (tick % 20 == 0) {
                updatePvpBar();
                checkWinners();
            }

            // Во время ПВП всегда день и ясная погода
            if (tick % 100 == 0) {
                runCmd("time set day");
                runCmd("weather clear");
            }

            // После 5 минут ПВП все подсвечиваются цветом своей команды
            if (!winnerAnnounced && pvpTicks >= GLOW_AFTER_TICKS) {
                if (!glowAnnounced) {
                    glowAnnounced = true;
                    soundAll("minecraft:entity.elder_guardian.curse", "1 1");
                    broadcast(Component.literal("👁 ПВП идёт уже 5 минут — все подсвечены, прятаться бесполезно!")
                            .withStyle(ChatFormatting.GOLD));
                }
                if (tick % 100 == 0) {
                    runCmd("effect give @a[gamemode=!spectator] minecraft:glowing 30 0 true");
                }
            }
        }

        if ((running || pvp) && tick % 100 == 0) {
            for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
                bar.addPlayer(p);
            }
        }

        // Эффекты вокруг победителей
        if (winnerFxTicks > 0) {
            winnerFxTicks--;
            if (winnerFxTicks % 10 == 0 && !winnerSelector.isEmpty()) {
                runCmd("execute as @a[" + winnerSelector + "] at @s run "
                        + "particle minecraft:electric_spark ~ ~1 ~ 0.7 1 0.7 0.4 50");
                runCmd("execute as @a[" + winnerSelector + "] at @s run "
                        + "particle minecraft:totem_of_undying ~ ~1 ~ 0.6 1 0.6 0.4 40");
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

        // Сколько живых в каждой команде
        Map<String, Integer> perTeam = new TreeMap<>();
        int solo = 0;
        int alive = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (isAlive(p)) {
                alive++;
                var t = p.getTeam();
                if (t != null) perTeam.merge(t.getName(), 1, Integer::sum);
                else solo++;
            }
        }

        StringBuilder sb = new StringBuilder("⚔ ПВП ИДЁТ — ");
        if (perTeam.isEmpty()) {
            sb.append("живых: ").append(alive);
        } else {
            boolean first = true;
            for (Map.Entry<String, Integer> e : perTeam.entrySet()) {
                if (!first) sb.append(" | ");
                sb.append(e.getKey()).append(": ").append(e.getValue());
                first = false;
            }
            if (solo > 0) sb.append(" | без команды: ").append(solo);
        }

        bar.setColor(BossEvent.BossBarColor.RED);
        bar.setName(Component.literal(sb.toString()));
        float progress = pvpStartPlayers <= 0 ? 1f : (float) alive / pvpStartPlayers;
        bar.setProgress(Math.max(0f, Math.min(1f, progress)));
    }

    private static String formatTime(int ticks) {
        int totalSeconds = (ticks + 19) / 20;
        return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private static boolean isAlive(ServerPlayer p) {
        return !p.isSpectator() && !fallen.contains(p.getUUID());
    }

    /** Ключ игрока: его команда или он сам, если команды нет. */
    private static String keyOf(ServerPlayer p) {
        var t = p.getTeam();
        return t != null ? "team:" + t.getName() : "solo:" + p.getUUID();
    }

    private static Set<String> aliveKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (isAlive(p)) keys.add(keyOf(p));
        }
        return keys;
    }

    private static int aliveCount() {
        int n = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (isAlive(p)) n++;
        }
        return n;
    }

    private static String teamColorName(String team) {
        if (team.equals("team1")) return "blue";
        if (team.equals("team2")) return "red";
        return "gold";
    }

    private static ChatFormatting teamFormat(String team) {
        if (team.equals("team1")) return ChatFormatting.BLUE;
        if (team.equals("team2")) return ChatFormatting.RED;
        return ChatFormatting.GOLD;
    }

    // ------------------------------------------------------------ voting

    private static void startVote() {
        voteActive = true;
        voteTicksLeft = VOTE_DURATION_TICKS;
        votes.clear();

        titleAll("ГОЛОСОВАНИЕ", "gold", "Продлить на " + EXTENSION_MINUTES + " мин?", "yellow", "5 50 10");
        soundAll("minecraft:block.note_block.bell", "1 1");
        broadcast(Component.literal("🗳 Голосование за продление на " + EXTENSION_MINUTES + " мин ("
                + (extensionsUsed + 1) + "/" + MAX_EXTENSIONS + "). Время на голосование: 20 секунд.")
                .withStyle(ChatFormatting.GOLD));
        runCmd("tellraw @a [\"\",{\"text\":\"   [ ДА ]\",\"color\":\"green\",\"bold\":true,"
                + "\"click_event\":{\"action\":\"run_command\",\"command\":\"/timer vote yes\"}},"
                + "{\"text\":\"     \"},"
                + "{\"text\":\"[ НЕТ ]\",\"color\":\"red\",\"bold\":true,"
                + "\"click_event\":{\"action\":\"run_command\",\"command\":\"/timer vote no\"}}]");
        broadcast(Component.literal("Или напиши: /timer vote yes   либо   /timer vote no")
                .withStyle(ChatFormatting.GRAY));
    }

    private static int vote(CommandContext<CommandSourceStack> ctx, boolean yes) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("Голосовать могут только игроки."));
            return 0;
        }
        if (!voteActive) {
            ctx.getSource().sendFailure(Component.literal("Сейчас нет голосования."));
            return 0;
        }
        votes.put(player.getUUID(), yes);
        ctx.getSource().sendSuccess(() -> Component.literal(yes
                ? "✔ Ты проголосовал: ДА"
                : "✔ Ты проголосовал: НЕТ")
                .withStyle(yes ? ChatFormatting.GREEN : ChatFormatting.RED), false);
        return 1;
    }

    private static void resolveVote() {
        voteActive = false;

        int yes = 0, no = 0;
        for (boolean v : votes.values()) {
            if (v) yes++;
            else no++;
        }
        votes.clear();

        boolean extend;
        String note = "";
        if (yes + no == 0) {
            extend = false;
            note = " Никто не проголосовал.";
        } else if (yes > no) {
            extend = true;
        } else if (no > yes) {
            extend = false;
        } else {
            extend = RANDOM.nextBoolean();
            note = " Ничья — рандом решил: " + (extend ? "ДА" : "НЕТ") + ".";
        }

        String stats = "";
        if (yes + no > 0) {
            int pYes = Math.round(100f * yes / (yes + no));
            stats = "ДА: " + yes + " (" + pYes + "%) | НЕТ: " + no + " (" + (100 - pYes) + "%).";
        }
        broadcast(Component.literal("🗳 Итоги: " + stats + note).withStyle(ChatFormatting.GOLD));

        if (extend) {
            extensionsUsed++;
            remainingTicks += EXTENSION_MINUTES * TICKS_PER_MINUTE;
            totalTicks += EXTENSION_MINUTES * TICKS_PER_MINUTE;
            voteDone = false;
            updateBarText();
            soundAll("minecraft:entity.player.levelup", "1 1");
            int left = MAX_EXTENSIONS - extensionsUsed;
            broadcast(Component.literal("➕ Таймер продлён на " + EXTENSION_MINUTES + " мин. Осталось: "
                    + formatTime(remainingTicks) + ". Продлений ещё можно: " + left + ".")
                    .withStyle(ChatFormatting.GREEN));
        } else {
            voteDone = true;
            soundAll("minecraft:block.note_block.bass", "1 0.7");
            broadcast(Component.literal("⏳ Таймер не продлён.").withStyle(ChatFormatting.YELLOW));
        }
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

        extensionsUsed = 0;
        voteActive = false;
        voteDone = false;
        votes.clear();

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
        if (remainingTicks > TICKS_PER_MINUTE && !voteActive) {
            voteDone = false; // перед концом снова можно голосовать
        }
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
        voteActive = false;
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
            voteActive = false;
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
                + " | продлений: " + extensionsUsed + "/" + MAX_EXTENSIONS
                + (specEnabled ? "" : " (ПВП в конце не будет)");
        else if (pvp) state = "ПВП идёт, живых: " + aliveCount();
        else state = "не запущен";
        ctx.getSource().sendSuccess(() -> Component.literal("⏱ Таймер: " + state)
                .withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    /** Случайно делит игроков онлайн на team1 и team2. */
    private static int randomTeam(CommandContext<CommandSourceStack> ctx) {
        server = ctx.getSource().getServer();
        if (pvp) {
            ctx.getSource().sendFailure(Component.literal("Нельзя менять команды во время ПВП."));
            return 0;
        }

        List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());
        if (players.size() < 2) {
            ctx.getSource().sendFailure(Component.literal("Для разделения на команды нужно минимум 2 игрока."));
            return 0;
        }

        Collections.shuffle(players, RANDOM);
        int n1 = players.size() / 2;
        if (players.size() % 2 == 1 && RANDOM.nextBoolean()) {
            n1++; // лишний игрок достаётся случайной команде
        }

        ensureTeams();
        runCmd("team empty team1");
        runCmd("team empty team2");

        List<String> t1 = new ArrayList<>();
        List<String> t2 = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            String name = players.get(i).getName().getString();
            if (i < n1) {
                runCmd("team join team1 " + name);
                t1.add(name);
            } else {
                runCmd("team join team2 " + name);
                t2.add(name);
            }
        }

        soundAll("minecraft:entity.player.levelup", "1 1");
        broadcast(Component.literal("🎲 Команды распределены случайно:").withStyle(ChatFormatting.GOLD));
        broadcast(Component.literal("🔵 team1: " + String.join(", ", t1)).withStyle(ChatFormatting.BLUE));
        broadcast(Component.literal("🔴 team2: " + String.join(", ", t2)).withStyle(ChatFormatting.RED));
        return 1;
    }

    private static void ensureTeams() {
        runCmd("team add team1");
        runCmd("team add team2");
        runCmd("team modify team1 color blue");
        runCmd("team modify team2 color red");
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
        winnerSelector = "";
        winnerFxTicks = 0;
        pvpTicks = 0;
        glowAnnounced = false;
        pvpStartPlayers = aliveCount();
        pvpStartKeys = aliveKeys().size();

        // Цвета команд для подсветки
        runCmd("team modify team1 color blue");
        runCmd("team modify team2 color red");

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

        broadcast(Component.literal("⚔ ПВП началось! Кто умрёт — станет спектатором. "
                + "Сохранение инвентаря отключено! Через 5 минут все будут подсвечены.")
                .withStyle(ChatFormatting.DARK_RED));
    }

    /** Если остались живыми игроки только одной команды (или один игрок) — объявляем победителей. */
    private static void checkWinners() {
        if (!pvp || winnerAnnounced || pvpStartKeys < 2) return;
        if (aliveKeys().size() == 1) {
            announceWinners();
        }
    }

    private static void announceWinners() {
        ServerPlayer first = null;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (isAlive(p)) {
                first = p;
                break;
            }
        }
        if (first == null) return;

        winnerAnnounced = true;
        winnerFxTicks = 100;

        String title;
        String subtitle;
        String color;
        Component chat;

        var team = first.getTeam();
        if (team != null) {
            String teamName = team.getName();
            List<String> nicks = new ArrayList<>();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                var t = p.getTeam();
                if (t != null && t.getName().equals(teamName)) {
                    nicks.add(p.getName().getString());
                }
            }
            winnerSelector = "team=" + teamName;
            title = "ПОБЕДА " + teamName;
            subtitle = String.join(", ", nicks);
            color = teamColorName(teamName);
            chat = Component.literal("★ ПОБЕДИЛА КОМАНДА " + teamName + ": " + String.join(", ", nicks) + " ★")
                    .withStyle(teamFormat(teamName));
        } else {
            String name = first.getName().getString();
            winnerSelector = "name=" + name;
            title = name;
            subtitle = "ПОБЕДИТЕЛЬ ПВП";
            color = "gold";
            chat = Component.literal("★ ПОБЕДИТЕЛЬ: " + name + " ★").withStyle(ChatFormatting.GOLD);
        }

        titleAll(title, color, subtitle, "yellow", "10 80 20");
        soundAll("minecraft:ui.toast.challenge_complete", "1 1");
        soundAll("minecraft:entity.lightning_bolt.thunder", "0.7 1.3");
        broadcast(chat);

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

        // Подсветку убираем, сохранение инвентаря возвращается
        runCmd("effect clear @a minecraft:glowing");
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

        runCmd("effect clear @a minecraft:glowing");
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
