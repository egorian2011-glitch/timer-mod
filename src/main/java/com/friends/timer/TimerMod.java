package com.friends.timer;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.BossEvent;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

public class TimerMod implements ModInitializer {

    // ======================================================================
    //                              НАСТРОЙКИ
    // ======================================================================

    /** Надпись по центру экрана, когда таймер закончился. */
    private static final String PVP_TITLE = "ПВП НАЧИНАЕТСЯ";
    private static final String PVP_SUBTITLE = "Погибшие становятся спектаторами";

    private static final int TICKS_PER_MINUTE = 20 * 60;

    /** Автоматическое голосование за продление в последнюю минуту. */
    private static final int MAX_EXTENSIONS = 3;
    private static final int EXTENSION_MINUTES = 2;
    private static final int VOTE_DURATION_TICKS = 20 * 20;

    /** Голосование, которое запускает игрок (/votetime). */
    private static final int MAX_VOTE_MINUTES = 10;
    private static final int PLAYER_VOTE_COOLDOWN_TICKS = 20 * 60;
    private static final int MIN_TICKS_FOR_TIME_VOTE = 20 * 40;

    /** Через сколько тиков ПВП все начинают светиться (5 минут). */
    private static final int GLOW_AFTER_TICKS = 5 * TICKS_PER_MINUTE;

    /** Через сколько тиков после победы игра заканчивается (очистка и убийство). 200 = 10 сек. */
    private static final int GAME_END_DELAY_TICKS = 20 * 10;

    /** Значение правила immediate_respawn, которое возвращается после автовозрождения. */
    private static final boolean IMMEDIATE_RESPAWN_DEFAULT = false;

    // --- аирдроп ---
    private static final boolean AIRDROP_ENABLED = true;
    /** Аирдроп появляется только если таймер не короче этого. */
    private static final int AIRDROP_MIN_TIMER_MINUTES = 5;
    /** Когда падает: доля прошедшего времени таймера (от и до). */
    private static final double AIRDROP_EARLIEST = 0.30;
    private static final double AIRDROP_LATEST = 0.65;
    /** Радиус карты: аирдроп никогда не упадёт дальше этого от центра игры. */
    private static final int MAP_RADIUS = 250;
    /** Минимальное расстояние от центра до места падения. */
    private static final int DROP_MIN_DIST = 40;
    /** Радиус площадки (9x9 при радиусе 4). */
    private static final int FORT_RADIUS = 4;
    /** Сколько тиков сундук закрыт после падения (0 — открыт сразу). 600 = 30 сек. */
    private static final int CHEST_LOCK_TICKS = 20 * 30;
    /** Шансы типов добычи: обычная 60%, редкая 30%, остальное — джекпот (10%). */
    private static final double COMMON_CHANCE = 0.60;
    private static final double RARE_CHANCE = 0.30;
    /** Сколько случайных позиций из списка обычной добычи кладётся в сундук. */
    private static final int COMMON_PICKS = 3;

    private static final String SPEED_POTION =
            "minecraft:potion[potion_contents={potion:\"minecraft:swiftness\"}]";

    // ======================================================================

    private static final Random RANDOM = new Random();

    private enum VoteType { AUTO_EXTEND, ADD_TIME }

    private enum DropState { NONE, SEARCHING, SPAWNED, OPENED }

    /** Нота для мини-музыки при открытии сундука. */
    private record Note(int tick, String sound, double pitch) {}

    private static final Note[] JINGLE = {
            new Note(0, "minecraft:ui.toast.challenge_complete", 1.0),
            new Note(0, "minecraft:entity.firework_rocket.launch", 1.0),
            new Note(4, "minecraft:block.note_block.bell", 0.75),
            new Note(7, "minecraft:block.note_block.bell", 0.945),
            new Note(10, "minecraft:block.note_block.bell", 1.125),
            new Note(13, "minecraft:block.note_block.bell", 1.5),
            new Note(18, "minecraft:block.note_block.bell", 0.75),
            new Note(18, "minecraft:block.note_block.bell", 1.125),
            new Note(18, "minecraft:block.note_block.chime", 1.5),
            new Note(18, "minecraft:entity.firework_rocket.blast", 1.0),
            new Note(18, "minecraft:entity.firework_rocket.twinkle", 1.0),
            new Note(24, "minecraft:entity.firework_rocket.twinkle", 1.2),
            new Note(30, "minecraft:entity.player.levelup", 1.0),
    };

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
    /** Сколько тиков таймера уже прошло (без пауз). */
    private static int timerElapsed = 0;

    // --- игра (/game start) ---
    private static boolean gameActive = false;
    private static int gameEndTicks = 0;
    private static int respawnRestoreTicks = 0;

    // --- напоминание оператору при входе ---
    private static final Map<UUID, Integer> opHelpAt = new HashMap<>();

    // --- пауза ---
    private static boolean paused = false;
    /** Игрок, которого пауза не касается (для /playerpause). null — заморожены все. */
    private static UUID pauseExempt = null;
    /** Кого заморозили и в каком режиме он был (SURVIVAL или CREATIVE). */
    private static final Map<UUID, GameType> frozenModes = new HashMap<>();

    // --- голосования ---
    private static boolean voteActive = false;
    private static boolean voteDone = false; // автоголосование уже было отклонено
    private static VoteType voteType = VoteType.AUTO_EXTEND;
    private static int voteMinutes = 0;
    private static int voteTicksLeft = 0;
    private static int extensionsUsed = 0;
    private static int lastPlayerVoteTick = -PLAYER_VOTE_COOLDOWN_TICKS;
    private static final Map<UUID, Boolean> votes = new HashMap<>();

    // --- ПВП ---
    private static final Set<UUID> fallen = new HashSet<>();
    private static final Set<UUID> reviveOnJoin = new HashSet<>();
    private static final Map<String, Integer> kills = new HashMap<>();

    /** Для итогов: порядок выбывания и первая кровь. */
    private static final List<String> deathLog = new ArrayList<>();
    private static String firstBlood = null;

    private static int pvpStartKeys = 0;
    private static int pvpStartPlayers = 0;
    private static int pvpTicks = 0;
    private static boolean glowAnnounced = false;

    private static boolean winnerAnnounced = false;
    private static String winnerSelector = "";
    private static int winnerFxTicks = 0;

    /** true, если мод сейчас отключил сохранение инвентаря и должен его вернуть. */
    private static boolean keepInvDisabled = false;

    // --- аирдроп ---
    private static boolean dropArmed = false;
    private static DropState dropState = DropState.NONE;
    private static int dropTriggerTick = 0;
    private static int dropWarnTick = 0;
    private static int dropSearchAttempts = 0;
    private static boolean dropSpotFound = false;
    private static int dropX, dropY, dropZ;
    private static double centerX = 0, centerZ = 0;
    private static int dropLockTicks = 0;
    private static int dropAge = 0;
    private static String dropTier = "";
    private static boolean jingleActive = false;
    private static int jingleTick = 0;

    private static boolean isOp(CommandSourceStack s) {
        return s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    /** Является ли игрок оператором (уровень 2 и выше). Если проверка не соберётся — менять только эту строку. */
    private static boolean isOpPlayer(ServerPlayer p) {
        return p.getPermissionContext().permissionLevel().ordinal() >= 2;
    }

    @Override
    public void onInitialize() {
        bar = new ServerBossEvent(UUID.randomUUID(), Component.literal(""),
                BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // /timer — только для операторов
            dispatcher.register(
                    Commands.literal("timer")
                            .requires(TimerMod::isOp)
                            .executes(ctx -> status(ctx))
                            .then(Commands.literal("start")
                                    .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 1440))
                                            .executes(ctx -> timerStart(ctx, IntegerArgumentType.getInteger(ctx, "minutes")))))
                            .then(Commands.literal("add")
                                    .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 1440))
                                            .executes(ctx -> add(ctx, IntegerArgumentType.getInteger(ctx, "minutes")))))
                            .then(Commands.literal("stop")
                                    .executes(ctx -> stop(ctx)))
                            .then(Commands.literal("spec")
                                    .then(Commands.literal("off").executes(ctx -> specOff(ctx))))
            );

            // Голосовать может любой игрок
            dispatcher.register(
                    Commands.literal("vote")
                            .then(Commands.literal("yes").executes(ctx -> vote(ctx, true)))
                            .then(Commands.literal("no").executes(ctx -> vote(ctx, false)))
            );

            dispatcher.register(
                    Commands.literal("randomteam")
                            .requires(TimerMod::isOp)
                            .executes(ctx -> randomTeamCmd(ctx))
            );

            dispatcher.register(
                    Commands.literal("game")
                            .requires(TimerMod::isOp)
                            .then(Commands.literal("start")
                                    .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 1440))
                                            .executes(ctx -> gameStart(ctx, IntegerArgumentType.getInteger(ctx, "minutes")))))
                            .then(Commands.literal("delete").executes(ctx -> gameDelete(ctx)))
                            .then(Commands.literal("pause").executes(ctx -> gamePause(ctx)))
                            .then(Commands.literal("resume").executes(ctx -> gameResume(ctx)))
                            .then(Commands.literal("help").executes(ctx -> gameHelp(ctx)))
            );

            dispatcher.register(
                    Commands.literal("playerpause")
                            .requires(TimerMod::isOp)
                            .executes(ctx -> playerPause(ctx))
                            .then(Commands.literal("off").executes(ctx -> gameResume(ctx)))
            );

            // Голосование, которое может запустить любой игрок
            dispatcher.register(
                    Commands.literal("votetime")
                            .then(Commands.argument("minutes", IntegerArgumentType.integer(1, MAX_VOTE_MINUTES))
                                    .executes(ctx -> startPlayerVote(ctx, VoteType.ADD_TIME,
                                            IntegerArgumentType.getInteger(ctx, "minutes"))))
            );
        });

        ServerTickEvents.END_SERVER_TICK.register(this::onTick);

        // Во время паузы игроки не получают урон (кроме того, кого пауза не касается)
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (paused && entity instanceof ServerPlayer p && !p.getUUID().equals(pauseExempt)) {
                return false;
            }
            return true;
        });

        // Смерть во время ПВП: сообщение, звук, проверка победителей
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!pvp) return;
            if (!(entity instanceof ServerPlayer victim)) return;
            if (!fallen.add(victim.getUUID())) return;

            String victimName = victim.getName().getString();
            String killerName = null;
            Component msg;
            if (source.getEntity() instanceof ServerPlayer killer && killer != victim) {
                killerName = killer.getName().getString();
                kills.merge(killerName, 1, Integer::sum);
                msg = Component.literal("☠ " + victimName + " убит игроком " + killerName)
                        .withStyle(ChatFormatting.RED);
            } else {
                msg = Component.literal("☠ " + victimName + " погиб")
                        .withStyle(ChatFormatting.RED);
            }

            // Для итогов игры
            if (firstBlood == null) {
                firstBlood = killerName != null
                        ? killerName + " убил " + victimName
                        : victimName + " погиб первым";
            }
            deathLog.add(victimName);

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
            server = srv;
            ServerPlayer player = handler.getPlayer();
            if (running || pvp) {
                bar.addPlayer(player);
            }
            if (reviveOnJoin.remove(player.getUUID())) {
                player.setGameMode(GameType.SURVIVAL);
                String n = player.getName().getString();
                runCmd("effect clear " + n + " minecraft:slowness");
                runCmd("effect clear " + n + " minecraft:jump_boost");
            }
            if (pvp && fallen.contains(player.getUUID())) {
                player.setGameMode(GameType.SPECTATOR);
            }
            if (paused && !player.getUUID().equals(pauseExempt)) {
                freeze(player);
            }
            // Напоминание оператору (через 2 секунды после входа)
            if (isOpPlayer(player)) {
                opHelpAt.put(player.getUUID(), srv.getTickCount() + 40);
            }
        });

        // Открытие сундука с аирдропом
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            if (dropState != DropState.SPAWNED) return InteractionResult.PASS;
            BlockPos bp = hit.getBlockPos();
            if (bp.getX() != dropX || bp.getY() != dropY || bp.getZ() != dropZ) return InteractionResult.PASS;

            if (dropLockTicks > 0) {
                int sec = (dropLockTicks + 19) / 20;
                runCmd("title " + sp.getName().getString() + " actionbar "
                        + "{\"text\":\"🔒 Сундук откроется через " + sec + " сек.\",\"color\":\"yellow\"}");
                return InteractionResult.FAIL;
            }
            onDropOpened(sp);
            return InteractionResult.PASS;
        });

        // Сундук нельзя сломать, пока его не открыли
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            if (dropState == DropState.SPAWNED
                    && pos.getX() == dropX && pos.getY() == dropY && pos.getZ() == dropZ) {
                return false;
            }
            return true;
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(srv -> {
            server = srv;
            if (keepInvDisabled) {
                setKeepInventory(true);
            }
            if (respawnRestoreTicks > 0) {
                runCmd("gamerule immediate_respawn " + IMMEDIATE_RESPAWN_DEFAULT);
                respawnRestoreTicks = 0;
            }
            if (paused) {
                unfreezeAll();
            }
            bar.removeAllPlayers();
            running = false;
            pvp = false;
            paused = false;
            voteActive = false;
            gameActive = false;
            fallen.clear();
            kills.clear();
        });
    }

    // ---------------------------------------------------------------- tick

    private void onTick(MinecraftServer srv) {
        server = srv;
        int tick = srv.getTickCount();

        // Напоминание оператору после входа
        if (!opHelpAt.isEmpty()) {
            for (Iterator<Map.Entry<UUID, Integer>> it = opHelpAt.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<UUID, Integer> e = it.next();
                if (tick >= e.getValue()) {
                    ServerPlayer p = srv.getPlayerList().getPlayer(e.getKey());
                    if (p != null) {
                        for (Component c : opReminderLines()) {
                            p.sendSystemMessage(c);
                        }
                    }
                    it.remove();
                }
            }
        }

        // Возврат правила immediate_respawn после автовозрождения
        if (respawnRestoreTicks > 0) {
            respawnRestoreTicks--;
            if (respawnRestoreTicks == 0) {
                runCmd("gamerule immediate_respawn " + IMMEDIATE_RESPAWN_DEFAULT);
            }
        }

        // Пауза: ничего не идёт, все заморожены
        if (paused) {
            if (tick % 20 == 0) {
                bar.setColor(BossEvent.BossBarColor.WHITE);
                if (running) {
                    bar.setName(Component.literal("⏸ ПАУЗА — осталось " + formatTime(remainingTicks)));
                } else if (pvp) {
                    bar.setName(Component.literal("⏸ ПАУЗА — ПВП"));
                }
            }
            if (tick % 40 == 0) {
                for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
                    if (!p.getUUID().equals(pauseExempt)) freeze(p);
                }
            }
            return;
        }

        // Конец игры после победы
        if (gameEndTicks > 0) {
            gameEndTicks--;
            if (gameEndTicks == 0) {
                finishGame();
            }
        }

        // Эффекты аирдропа и музыка
        if (dropState == DropState.SPAWNED) {
            airdropEffects(tick);
        }
        if (jingleActive) {
            jingleStep();
        }

        if (running) {
            remainingTicks--;
            timerElapsed++;
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

            airdropSchedule(tick);

            // За минуту до конца — голосование за продление
            if (!voteActive && !voteDone
                    && extensionsUsed < MAX_EXTENSIONS
                    && totalTicks > TICKS_PER_MINUTE
                    && remainingTicks <= TICKS_PER_MINUTE) {
                beginVote(VoteType.AUTO_EXTEND, EXTENSION_MINUTES, null);
            }
        }

        // Любое голосование (автоматическое или запущенное игроком)
        if (voteActive) {
            voteTicksLeft--;
            if (voteTicksLeft <= 0) {
                resolveVote();
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

    // ------------------------------------------------------------ airdrop

    /** Планирование аирдропа в начале таймера. */
    private static void scheduleAirdrop() {
        dropState = DropState.NONE;
        dropArmed = false;
        dropSpotFound = false;
        timerElapsed = 0;

        // Центр игры — среднее положение игроков в момент запуска (около точки спавна)
        double sx = 0, sz = 0;
        int n = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            sx += p.getX();
            sz += p.getZ();
            n++;
        }
        centerX = n > 0 ? sx / n : 0;
        centerZ = n > 0 ? sz / n : 0;

        if (!AIRDROP_ENABLED || totalTicks < AIRDROP_MIN_TIMER_MINUTES * TICKS_PER_MINUTE) return;

        double f = AIRDROP_EARLIEST + RANDOM.nextDouble() * (AIRDROP_LATEST - AIRDROP_EARLIEST);
        dropTriggerTick = (int) (totalTicks * f);
        dropWarnTick = Math.max(0, dropTriggerTick - TICKS_PER_MINUTE);
        dropArmed = true;
    }

    private static void resetAirdrop() {
        dropArmed = false;
        dropState = DropState.NONE;
        dropSpotFound = false;
    }

    /** Предупреждение за минуту, поиск места, падение в нужный момент. */
    private static void airdropSchedule(int tick) {
        if (!dropArmed) return;

        if (dropState == DropState.NONE && timerElapsed >= dropWarnTick) {
            dropState = DropState.SEARCHING;
            dropSearchAttempts = 0;
            dropSpotFound = false;
            broadcast(Component.literal("📦 Примерно через минуту в случайном месте упадёт АИРДРОП!")
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
            soundAll("minecraft:block.note_block.pling", "1 0.8");
        }

        if (dropState == DropState.SEARCHING) {
            // Ищем место заранее, по одной попытке раз в полсекунды, чтобы не было лагов
            if (!dropSpotFound && tick % 10 == 0 && dropSearchAttempts < 30) {
                dropSearchAttempts++;
                dropSpotFound = tryFindSpot();
            }

            if (timerElapsed >= dropTriggerTick) {
                int extra = 0;
                while (!dropSpotFound && extra < 6) {
                    extra++;
                    dropSpotFound = tryFindSpot();
                }
                if (dropSpotFound) {
                    spawnAirdrop();
                } else {
                    broadcast(Component.literal("📦 Аирдроп не удалось поставить: не нашлось подходящего места.")
                            .withStyle(ChatFormatting.GRAY));
                    resetAirdrop();
                }
            }
        }
    }

    private static int surfaceY(ServerLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    /** Одна попытка найти ровное сухое место на поверхности внутри радиуса карты. */
    private static boolean tryFindSpot() {
        ServerLevel level = server.overworld();

        int maxDist = MAP_RADIUS - FORT_RADIUS - 15;
        double minD = DROP_MIN_DIST;
        // sqrt — чтобы точки были распределены равномерно по площади, а не скучились у центра
        double dist = Math.sqrt(minD * minD + RANDOM.nextDouble() * ((double) maxDist * maxDist - minD * minD));
        double ang = RANDOM.nextDouble() * Math.PI * 2;
        int x = (int) Math.round(centerX + Math.cos(ang) * dist);
        int z = (int) Math.round(centerZ + Math.sin(ang) * dist);

        int r = FORT_RADIUS;
        int y = surfaceY(level, x, z);
        int[][] pts = {{x, z}, {x - r, z - r}, {x + r, z - r}, {x - r, z + r}, {x + r, z + r}};
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int[] pt : pts) {
            int h = surfaceY(level, pt[0], pt[1]);
            BlockState below = level.getBlockState(new BlockPos(pt[0], h - 1, pt[1]));
            // Не вода, не лава и не пустота
            if (below.isAir() || !below.getFluidState().isEmpty()) return false;
            min = Math.min(min, h);
            max = Math.max(max, h);
        }
        // Слишком крутой склон
        if (max - min > 4) return false;

        dropX = x;
        dropY = y;
        dropZ = z;
        return true;
    }

    private static String fill(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
        return "fill " + x1 + " " + y1 + " " + z1 + " " + x2 + " " + y2 + " " + z2 + " " + block;
    }

    /** Строит площадку с бедроковыми стенами, ставит сундук и кладёт лут. */
    private static void spawnAirdrop() {
        ServerLevel level = server.overworld();
        int x = dropX, y = dropY, z = dropZ;
        int r = FORT_RADIUS;
        int x1 = x - r, x2 = x + r, z1 = z - r, z2 = z + r;

        // Убедиться, что вся площадка загружена
        for (int cx = x1 >> 4; cx <= (x2 >> 4); cx++) {
            for (int cz = z1 >> 4; cz <= (z2 >> 4); cz++) {
                level.getChunk(cx, cz);
            }
        }

        int wallBottom = y - 4;
        int wallTop = y + 3;

        // Чистим внутри, делаем основание и пол
        runCmd(fill(x1 + 1, y, z1 + 1, x2 - 1, y + 5, z2 - 1, "minecraft:air"));
        runCmd(fill(x1, y - 4, z1, x2, y - 2, z2, "minecraft:stone"));
        runCmd(fill(x1 + 1, y - 1, z1 + 1, x2 - 1, y - 1, z2 - 1, "minecraft:smooth_stone"));

        // Бедроковые стены
        runCmd(fill(x1, wallBottom, z1, x2, wallTop, z1, "minecraft:bedrock"));
        runCmd(fill(x1, wallBottom, z2, x2, wallTop, z2, "minecraft:bedrock"));
        runCmd(fill(x1, wallBottom, z1 + 1, x1, wallTop, z2 - 1, "minecraft:bedrock"));
        runCmd(fill(x2, wallBottom, z1 + 1, x2, wallTop, z2 - 1, "minecraft:bedrock"));

        // Два прохода: с севера и с юга
        runCmd(fill(x, y, z1, x, y + 2, z1, "minecraft:air"));
        runCmd(fill(x, y, z2, x, y + 2, z2, "minecraft:air"));

        // Фонари по углам
        runCmd("setblock " + (x1 + 1) + " " + y + " " + (z1 + 1) + " minecraft:lantern");
        runCmd("setblock " + (x2 - 1) + " " + y + " " + (z1 + 1) + " minecraft:lantern");
        runCmd("setblock " + (x1 + 1) + " " + y + " " + (z2 - 1) + " minecraft:lantern");
        runCmd("setblock " + (x2 - 1) + " " + y + " " + (z2 - 1) + " minecraft:lantern");

        // Сундук и лут
        runCmd("setblock " + x + " " + y + " " + z + " minecraft:chest");
        fillChest();

        dropState = DropState.SPAWNED;
        dropLockTicks = CHEST_LOCK_TICKS;
        dropAge = 0;

        titleAll("📦 АИРДРОП", "light_purple", "X: " + x + "  Y: " + y + "  Z: " + z, "yellow", "10 80 20");
        soundAll("minecraft:entity.lightning_bolt.thunder", "1 1.2");
        soundAll("minecraft:block.beacon.activate", "1 1");
        broadcast(Component.literal("📦 АИРДРОП УПАЛ! Координаты: X " + x + "  Y " + y + "  Z " + z)
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        if (CHEST_LOCK_TICKS > 0) {
            broadcast(Component.literal("🔒 Сундук откроется через " + (CHEST_LOCK_TICKS / 20)
                    + " сек. Бегите к нему!").withStyle(ChatFormatting.YELLOW));
        }
    }

    // --- лут ---

    private static String[] item(String id, int count) {
        return new String[]{id, String.valueOf(count)};
    }

    @SafeVarargs
    private static List<String[]> bundle(String[]... items) {
        List<String[]> l = new ArrayList<>();
        Collections.addAll(l, items);
        return l;
    }

    private static <T> T pickOne(List<T> list) {
        return list.get(RANDOM.nextInt(list.size()));
    }

    /** Берёт picks случайных наборов из пула и добавляет их предметы в out. */
    private static void addPicks(List<List<String[]>> pool, int picks, List<String[]> out) {
        Collections.shuffle(pool, RANDOM);
        for (int i = 0; i < Math.min(picks, pool.size()); i++) {
            out.addAll(pool.get(i));
        }
    }

    private static List<String[]> rollLoot() {
        List<String[]> out = new ArrayList<>();

        // Еда — всегда: золотое яблоко (1–2) или жареное мясо (8–16)
        if (RANDOM.nextBoolean()) {
            out.add(item("minecraft:golden_apple", 1 + RANDOM.nextInt(2)));
        } else {
            out.add(item("minecraft:cooked_beef", 8 + RANDOM.nextInt(9)));
        }

        double r = RANDOM.nextDouble();
        if (r < COMMON_CHANCE) {
            // ОБЫЧНЫЙ: несколько случайных позиций из списка
            dropTier = "ОБЫЧНАЯ";
            List<List<String[]>> pool = new ArrayList<>();
            pool.add(bundle(item("minecraft:bow", 1), item("minecraft:arrow", 16)));
            pool.add(bundle(item("minecraft:shield", 1)));
            List<String> iron = List.of("minecraft:iron_helmet", "minecraft:iron_chestplate",
                    "minecraft:iron_leggings", "minecraft:iron_boots");
            pool.add(bundle(item(pickOne(iron), 1)));
            pool.add(bundle(item("minecraft:water_bucket", 1)));
            pool.add(bundle(item("minecraft:cobweb", 3)));
            pool.add(bundle(item(RANDOM.nextBoolean() ? "minecraft:oak_planks" : "minecraft:stone", 32)));
            addPicks(pool, COMMON_PICKS, out);
        } else if (r < COMMON_CHANCE + RARE_CHANCE) {
            // РЕДКИЙ: вариант А или Б (50/50), в обоих — жемчуг и зелье скорости
            dropTier = "РЕДКАЯ";
            if (RANDOM.nextBoolean()) {
                out.add(item("minecraft:diamond_chestplate", 1));
            } else {
                List<String> diamonds = new ArrayList<>(List.of("minecraft:diamond_helmet",
                        "minecraft:diamond_leggings", "minecraft:diamond_boots", "minecraft:diamond_sword",
                        "minecraft:diamond_pickaxe", "minecraft:diamond_axe"));
                Collections.shuffle(diamonds, RANDOM);
                out.add(item(diamonds.get(0), 1));
                out.add(item(diamonds.get(1), 1));
            }
            out.add(item("minecraft:ender_pearl", 1));
            out.add(item(SPEED_POTION, 1));
        } else {
            // ДЖЕКПОТ: вариант А или Б (50/50)
            dropTier = "ДЖЕКПОТ";
            if (RANDOM.nextBoolean()) {
                out.add(item("minecraft:diamond_chestplate", 1));
                out.add(item("minecraft:diamond_leggings", 1));
            } else {
                out.add(item("minecraft:enchanted_golden_apple", 1));
                out.add(item("minecraft:diamond_sword", 1));
                out.add(item("minecraft:diamond_leggings", 1));
                out.add(item("minecraft:diamond_boots", 1));
            }
        }
        return out;
    }

    private static void fillChest() {
        List<String[]> items = rollLoot();
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < 27; i++) slots.add(i);
        Collections.shuffle(slots, RANDOM);

        for (int i = 0; i < items.size() && i < slots.size(); i++) {
            String[] it = items.get(i);
            runCmd("item replace block " + dropX + " " + dropY + " " + dropZ
                    + " container." + slots.get(i) + " with " + it[0] + " " + it[1]);
        }
    }

    // --- эффекты ---

    private static void airdropEffects(int tick) {
        dropAge++;

        // Разблокировка сундука
        if (dropLockTicks > 0) {
            dropLockTicks--;
            if (dropLockTicks == 0) {
                soundAll("minecraft:block.beacon.activate", "1 1.3");
                broadcast(Component.literal("🔓 Аирдроп РАЗБЛОКИРОВАН — кто первый возьмёт добычу?")
                        .withStyle(ChatFormatting.GREEN));
            }
        }

        double cx = dropX + 0.5;
        double cz = dropZ + 0.5;

        if (tick % 10 == 0) {
            runCmd("particle minecraft:end_rod " + cx + " " + (dropY + 1.0) + " " + cz
                    + " 0.35 0.6 0.35 0.02 4 force");
            runCmd("particle minecraft:enchant " + cx + " " + (dropY + 1.5) + " " + cz
                    + " 0.4 0.4 0.4 0.8 12 force");
            // Столб света к небу
            runCmd("particle minecraft:end_rod " + cx + " " + (dropY + 22.0) + " " + cz
                    + " 0.12 20 0.12 0.005 6 force");
        }
        if (tick % 60 == 0) {
            runCmd("playsound minecraft:block.beacon.ambient block @a " + cx + " " + (dropY + 1.0)
                    + " " + cz + " 4 1");
        }
        if (tick % 100 == 0) {
            runCmd("playsound minecraft:block.amethyst_block.chime block @a " + cx + " " + (dropY + 1.0)
                    + " " + cz + " 4 1.4");
        }

        // Напоминание с координатами
        if (dropAge > 0 && dropAge % (20 * 40) == 0) {
            broadcast(Component.literal("📦 Аирдроп ещё не открыт: X " + dropX + "  Y " + dropY + "  Z " + dropZ)
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
    }

    private static void onDropOpened(ServerPlayer opener) {
        dropState = DropState.OPENED;
        String name = opener.getName().getString();

        jingleActive = true;
        jingleTick = 0;

        double cx = dropX + 0.5;
        double cz = dropZ + 0.5;
        runCmd("particle minecraft:totem_of_undying " + cx + " " + (dropY + 1.0) + " " + cz
                + " 0.5 0.8 0.5 0.5 80 force");
        runCmd("particle minecraft:firework " + cx + " " + (dropY + 1.5) + " " + cz
                + " 0.6 0.8 0.6 0.15 50 force");

        titleAll(name, "gold", "открыл аирдроп!", "yellow", "5 50 15");
        broadcast(Component.literal("🎁 " + name + " открыл аирдроп! Добыча: " + dropTier)
                .withStyle(ChatFormatting.GOLD));
    }

    /** Шаг мини-музыки победы у сундука. */
    private static void jingleStep() {
        double cx = dropX + 0.5;
        double cz = dropZ + 0.5;

        for (Note n : JINGLE) {
            if (n.tick() == jingleTick) {
                runCmd("playsound " + n.sound() + " block @a " + cx + " " + (dropY + 1.0) + " " + cz
                        + " 4 " + n.pitch());
            }
        }
        if (jingleTick % 4 == 0) {
            runCmd("particle minecraft:note " + cx + " " + (dropY + 1.8) + " " + cz
                    + " 0.7 0.5 0.7 1 4 force");
        }

        jingleTick++;
        if (jingleTick > 40) {
            jingleActive = false;
        }
    }

    // ------------------------------------------------------------ voting

    /** Запуск голосования, которое начал игрок (/votetime). */
    private static int startPlayerVote(CommandContext<CommandSourceStack> ctx, VoteType type, int minutes) {
        server = ctx.getSource().getServer();
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("Голосование могут начинать только игроки."));
            return 0;
        }
        if (voteActive) {
            ctx.getSource().sendFailure(Component.literal("Уже идёт голосование. Подожди его окончания."));
            return 0;
        }
        if (paused) {
            ctx.getSource().sendFailure(Component.literal("Сейчас пауза, голосовать нельзя."));
            return 0;
        }

        int now = server.getTickCount();
        int wait = PLAYER_VOTE_COOLDOWN_TICKS - (now - lastPlayerVoteTick);
        if (wait > 0) {
            ctx.getSource().sendFailure(Component.literal("Подожди ещё " + ((wait + 19) / 20)
                    + " сек. перед следующим голосованием."));
            return 0;
        }

        if (!running) {
            ctx.getSource().sendFailure(Component.literal("Таймер сейчас не идёт."));
            return 0;
        }
        if (remainingTicks < MIN_TICKS_FOR_TIME_VOTE) {
            ctx.getSource().sendFailure(Component.literal("До конца таймера слишком мало времени для голосования."));
            return 0;
        }

        lastPlayerVoteTick = now;
        beginVote(type, minutes, player);
        return 1;
    }

    private static void beginVote(VoteType type, int minutes, ServerPlayer starter) {
        voteActive = true;
        voteType = type;
        voteMinutes = minutes;
        voteTicksLeft = VOTE_DURATION_TICKS;
        votes.clear();

        String question = type == VoteType.ADD_TIME
                ? "Добавить " + minutes + " мин?"
                : "Продлить на " + minutes + " мин?";

        titleAll("ГОЛОСОВАНИЕ", "gold", question, "yellow", "5 50 10");
        soundAll("minecraft:block.note_block.bell", "1 1");

        if (starter != null) {
            votes.put(starter.getUUID(), true);
            broadcast(Component.literal("🗳 " + starter.getName().getString() + " предлагает: " + question
                    + " Время на голосование: 20 секунд.").withStyle(ChatFormatting.GOLD));
        } else {
            broadcast(Component.literal("🗳 Голосование: " + question + " (" + (extensionsUsed + 1) + "/"
                    + MAX_EXTENSIONS + "). Время на голосование: 20 секунд.").withStyle(ChatFormatting.GOLD));
        }

        runCmd("tellraw @a [\"\",{\"text\":\"   [ ДА ]\",\"color\":\"green\",\"bold\":true,"
                + "\"click_event\":{\"action\":\"run_command\",\"command\":\"/vote yes\"}},"
                + "{\"text\":\"     \"},"
                + "{\"text\":\"[ НЕТ ]\",\"color\":\"red\",\"bold\":true,"
                + "\"click_event\":{\"action\":\"run_command\",\"command\":\"/vote no\"}}]");
        broadcast(Component.literal("Или напиши: /vote yes   либо   /vote no")
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
        VoteType type = voteType;
        int minutes = voteMinutes;
        voteActive = false;

        int yes = 0, no = 0;
        for (boolean v : votes.values()) {
            if (v) yes++;
            else no++;
        }
        votes.clear();

        boolean pass;
        String note = "";
        if (yes + no == 0) {
            pass = false;
            note = " Никто не проголосовал.";
        } else if (yes > no) {
            pass = true;
        } else if (no > yes) {
            pass = false;
        } else {
            pass = RANDOM.nextBoolean();
            note = " Ничья — рандом решил: " + (pass ? "ДА" : "НЕТ") + ".";
        }

        String stats = "";
        if (yes + no > 0) {
            int pYes = Math.round(100f * yes / (yes + no));
            stats = "ДА: " + yes + " (" + pYes + "%) | НЕТ: " + no + " (" + (100 - pYes) + "%).";
        }
        broadcast(Component.literal("🗳 Итоги: " + stats + note).withStyle(ChatFormatting.GOLD));

        if (type == VoteType.ADD_TIME) {
            if (pass && running) {
                remainingTicks += minutes * TICKS_PER_MINUTE;
                totalTicks += minutes * TICKS_PER_MINUTE;
                if (remainingTicks > TICKS_PER_MINUTE) {
                    voteDone = false;
                }
                updateBarText();
                soundAll("minecraft:entity.player.levelup", "1 1");
                broadcast(Component.literal("➕ Добавлено " + minutes + " мин. Осталось: "
                        + formatTime(remainingTicks)).withStyle(ChatFormatting.GREEN));
            } else {
                soundAll("minecraft:block.note_block.bass", "1 0.7");
                broadcast(Component.literal("⏳ Время не добавлено.").withStyle(ChatFormatting.YELLOW));
            }
            return;
        }

        // Автоматическое голосование за продление
        if (pass) {
            extensionsUsed++;
            remainingTicks += minutes * TICKS_PER_MINUTE;
            totalTicks += minutes * TICKS_PER_MINUTE;
            voteDone = false;
            updateBarText();
            soundAll("minecraft:entity.player.levelup", "1 1");
            int left = MAX_EXTENSIONS - extensionsUsed;
            broadcast(Component.literal("➕ Таймер продлён на " + minutes + " мин. Осталось: "
                    + formatTime(remainingTicks) + ". Продлений ещё можно: " + left + ".")
                    .withStyle(ChatFormatting.GREEN));
        } else {
            voteDone = true;
            soundAll("minecraft:block.note_block.bass", "1 0.7");
            broadcast(Component.literal("⏳ Таймер не продлён.").withStyle(ChatFormatting.YELLOW));
        }
    }

    // ------------------------------------------------------- /game commands

    private static int gameStart(CommandContext<CommandSourceStack> ctx, int minutes) {
        server = ctx.getSource().getServer();
        if (gameActive || running || pvp) {
            ctx.getSource().sendFailure(Component.literal("Игра уже идёт. Сначала отмени её: /game delete"));
            return 0;
        }
        if (server.getPlayerList().getPlayers().size() < 2) {
            ctx.getSource().sendFailure(Component.literal("Для игры нужно минимум 2 игрока онлайн."));
            return 0;
        }

        // Пауза (/playerpause) снимается сама
        if (paused) {
            liftPause();
            broadcast(Component.literal("▶ Пауза снята автоматически.").withStyle(ChatFormatting.GREEN));
        }

        gameActive = true;
        gameEndTicks = 0;

        // 1. очистка инвентарей и убийство всех (чистый старт)
        runCmd("gamemode survival @a");
        runCmd("clear @a");
        runCmd("kill @a");
        broadcast(Component.literal("🎮 Игра начинается! Инвентари очищены, все убиты.")
                .withStyle(ChatFormatting.GOLD));

        // 2. команды
        randomTeams();

        // 3. таймер
        startTimer(minutes);

        // 4. таблица с командами
        for (Component line : helpLines()) {
            broadcast(line);
        }
        return 1;
    }

    private static int gameHelp(CommandContext<CommandSourceStack> ctx) {
        for (Component line : helpLines()) {
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return 1;
    }

    private static Component line(String text, ChatFormatting format) {
        return Component.literal(text).withStyle(format);
    }

    private static List<Component> rulesLines() {
        List<Component> l = new ArrayList<>();
        l.add(line("━━━━━━━━━━━ КАК ИГРАТЬ ━━━━━━━━━━━", ChatFormatting.GOLD));
        l.add(line("Пока идёт таймер — умирай и возрождайся как обычно.", ChatFormatting.GRAY));
        l.add(line("Когда таймер кончится, начнётся ПВП: кто умер — тот спектатор.", ChatFormatting.GRAY));
        l.add(line("Побеждает команда, в которой остались живые.", ChatFormatting.GRAY));
        l.add(line("Во время таймера в случайном месте упадёт аирдроп с добычей — найди его первым!",
                ChatFormatting.LIGHT_PURPLE));
        return l;
    }

    private static List<Component> playerCommandLines() {
        List<Component> l = new ArrayList<>();
        l.add(line("👥 ДЛЯ ИГРОКОВ:", ChatFormatting.GREEN));
        l.add(line("  /vote yes  или  /vote no — голосовать (или кнопки [ДА] [НЕТ] в чате)", ChatFormatting.WHITE));
        l.add(line("  /votetime <1-" + MAX_VOTE_MINUTES + "> — голосование: добавить минуты к таймеру",
                ChatFormatting.WHITE));
        l.add(line("  За минуту до конца таймера само пройдёт голосование за +" + EXTENSION_MINUTES
                + " мин (до " + MAX_EXTENSIONS + " раз).", ChatFormatting.GRAY));
        return l;
    }

    private static List<Component> opCommandLines() {
        List<Component> l = new ArrayList<>();
        l.add(line("🛠 ДЛЯ ОПЕРАТОРА — порядок, чтобы начать игру:", ChatFormatting.AQUA));
        l.add(line("  1. /playerpause — заморозить всех (кроме тебя)", ChatFormatting.WHITE));
        l.add(line("  2. /setworldspawn — точка возрождения там, где ты стоишь", ChatFormatting.WHITE));
        l.add(line("  3. /kill @a — все возродятся на новой точке", ChatFormatting.WHITE));
        l.add(line("  4. /randomteam — разделить игроков на команды", ChatFormatting.WHITE));
        l.add(line("  5. /game start <мин> — старт (пауза снимется сама)", ChatFormatting.WHITE));
        l.add(line("  Снять паузу вручную: /game resume  или  /playerpause off", ChatFormatting.GRAY));
        l.add(line("  Прочее: /game pause, /game delete, /game help,", ChatFormatting.WHITE));
        l.add(line("  /timer (статус), /timer add|start|stop <мин>, /timer spec off", ChatFormatting.WHITE));
        return l;
    }

    /** Напоминание оператору при заходе на сервер. */
    private static List<Component> opReminderLines() {
        List<Component> l = new ArrayList<>();
        l.add(line("━━━━━━━ НАПОМИНАНИЕ ОПЕРАТОРУ ━━━━━━━", ChatFormatting.GOLD));
        l.addAll(opCommandLines());
        l.add(line("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.GOLD));
        return l;
    }

    /** Таблица с командами: для игроков и для оператора. */
    private static List<Component> helpLines() {
        List<Component> l = new ArrayList<>();
        l.addAll(rulesLines());
        l.add(line(" ", ChatFormatting.WHITE));
        l.addAll(playerCommandLines());
        l.add(line(" ", ChatFormatting.WHITE));
        l.addAll(opCommandLines());
        l.add(line("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.GOLD));
        return l;
    }

    private static int gameDelete(CommandContext<CommandSourceStack> ctx) {
        server = ctx.getSource().getServer();
        cancelGame();
        broadcast(Component.literal("🛑 Игра отменена. Инвентари очищены, все убиты.")
                .withStyle(ChatFormatting.RED));
        return 1;
    }

    private static int gamePause(CommandContext<CommandSourceStack> ctx) {
        server = ctx.getSource().getServer();
        if (paused) {
            ctx.getSource().sendFailure(Component.literal("Уже стоит пауза. Снять: /game resume"));
            return 0;
        }
        doPause(null);
        broadcast(Component.literal("⏸ ПАУЗА. Никто не может двигаться и ломать блоки.")
                .withStyle(ChatFormatting.YELLOW));
        return 1;
    }

    private static int playerPause(CommandContext<CommandSourceStack> ctx) {
        server = ctx.getSource().getServer();
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("Эту команду можно писать только игроку."));
            return 0;
        }
        if (paused) {
            ctx.getSource().sendFailure(Component.literal("Уже стоит пауза. Снять: /game resume"));
            return 0;
        }
        doPause(player.getUUID());
        broadcast(Component.literal("⏸ ПАУЗА. Всё заморожено, кроме игрока "
                + player.getName().getString() + ".").withStyle(ChatFormatting.YELLOW));
        return 1;
    }

    private static int gameResume(CommandContext<CommandSourceStack> ctx) {
        server = ctx.getSource().getServer();
        if (!paused) {
            ctx.getSource().sendFailure(Component.literal("Паузы сейчас нет."));
            return 0;
        }
        doResume();
        return 1;
    }

    private static void doPause(UUID exempt) {
        paused = true;
        pauseExempt = exempt;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!p.getUUID().equals(exempt)) freeze(p);
        }
        titleAll("⏸ ПАУЗА", "yellow", "Игра приостановлена", "gray", "5 40 10");
        soundAll("minecraft:block.note_block.bass", "1 0.6");
    }

    /** Тихо снимает паузу (без сообщений). */
    private static void liftPause() {
        paused = false;
        pauseExempt = null;
        unfreezeAll();
    }

    private static void doResume() {
        liftPause();
        if (running) updateBarText();
        else if (pvp) updatePvpBar();
        titleAll("▶ ПРОДОЛЖАЕМ", "green", "", "gray", "5 30 10");
        soundAll("minecraft:block.beacon.activate", "1 1.2");
        broadcast(Component.literal("▶ Пауза снята.").withStyle(ChatFormatting.GREEN));
    }

    /** Замораживает игрока: нельзя двигаться, прыгать, ломать и ставить блоки. */
    private static void freeze(ServerPlayer p) {
        String n = p.getName().getString();
        runCmd("effect give " + n + " minecraft:slowness infinite 255 true");
        runCmd("effect give " + n + " minecraft:jump_boost infinite 200 true");
        UUID id = p.getUUID();
        if (!frozenModes.containsKey(id) && !p.isSpectator()) {
            frozenModes.put(id, p.isCreative() ? GameType.CREATIVE : GameType.SURVIVAL);
            p.setGameMode(GameType.ADVENTURE);
        }
    }

    private static void unfreezeAll() {
        runCmd("effect clear @a minecraft:slowness");
        runCmd("effect clear @a minecraft:jump_boost");
        for (Map.Entry<UUID, GameType> e : frozenModes.entrySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p != null) {
                p.setGameMode(e.getValue());
            } else {
                reviveOnJoin.add(e.getKey());
            }
        }
        frozenModes.clear();
    }

    /** Полная отмена всего: таймер, ПВП, пауза, голосование + очистка и убийство всех. */
    private static void cancelGame() {
        running = false;
        voteActive = false;
        voteDone = false;
        gameActive = false;
        gameEndTicks = 0;
        winnerFxTicks = 0;
        winnerAnnounced = false;
        jingleActive = false;
        resetAirdrop();
        bar.removeAllPlayers();

        if (paused) {
            paused = false;
            pauseExempt = null;
        }
        unfreezeAll();

        if (pvp) {
            pvp = false;
        }
        for (UUID uuid : fallen) {
            if (server.getPlayerList().getPlayer(uuid) == null) {
                reviveOnJoin.add(uuid);
            }
        }
        fallen.clear();
        kills.clear();

        runCmd("effect clear @a minecraft:glowing");
        if (keepInvDisabled) {
            setKeepInventory(true);
        }
        wipePlayers();
    }

    /** Нормальное завершение игры после победы. */
    private static void finishGame() {
        gameActive = false;
        resetAirdrop();
        endPvp();
        wipePlayers();
        broadcast(Component.literal("🏁 Игра окончена. Инвентари очищены, все убиты и возрождены.")
                .withStyle(ChatFormatting.GOLD));
    }

    /** Всем режим выживания, очистка инвентаря, смерть и автовозрождение. */
    private static void wipePlayers() {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.setGameMode(GameType.SURVIVAL);
        }
        runCmd("clear @a");
        // На пару секунд включаем мгновенное возрождение, чтобы никто не завис на экране смерти
        runCmd("gamerule immediate_respawn true");
        runCmd("kill @a");
        respawnRestoreTicks = 40;
    }

    // ------------------------------------------------------ timer commands

    private static int timerStart(CommandContext<CommandSourceStack> ctx, int minutes) {
        if (running) {
            ctx.getSource().sendFailure(Component.literal("Таймер уже запущен. Используй /timer add <минуты> или /timer stop."));
            return 0;
        }
        if (pvp) {
            ctx.getSource().sendFailure(Component.literal("Сейчас идёт ПВП. Сначала выключи его: /timer spec off"));
            return 0;
        }
        server = ctx.getSource().getServer();
        startTimer(minutes);
        return 1;
    }

    private static void startTimer(int minutes) {
        running = true;
        specEnabled = true;
        totalTicks = minutes * TICKS_PER_MINUTE;
        remainingTicks = totalTicks;

        extensionsUsed = 0;
        voteActive = false;
        voteDone = false;
        votes.clear();

        scheduleAirdrop();

        bar.removeAllPlayers();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            bar.addPlayer(p);
        }
        bar.setProgress(1f);
        updateBarText();

        titleAll(minutes + " МИН", "green", "Таймер запущен", "gray", "5 30 10");
        soundAll("minecraft:block.beacon.activate", "1 1");
        broadcast(Component.literal("▶ Таймер запущен на " + minutes + " мин.").withStyle(ChatFormatting.GREEN));
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
        resetAirdrop();
        bar.removeAllPlayers();
        soundAll("minecraft:block.note_block.bass", "1 0.7");
        broadcast(Component.literal("⏹ Таймер остановлен. ПВП не начнётся.").withStyle(ChatFormatting.YELLOW));
        return 1;
    }

    private static int specOff(CommandContext<CommandSourceStack> ctx) {
        specEnabled = false;
        server = ctx.getSource().getServer();
        gameActive = false;
        gameEndTicks = 0;
        resetAirdrop();
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
        if (paused) state += " | ПАУЗА";
        final String message = "⏱ Таймер: " + state;
        ctx.getSource().sendSuccess(() -> Component.literal(message)
                .withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    // ------------------------------------------------------------- teams

    private static int randomTeamCmd(CommandContext<CommandSourceStack> ctx) {
        server = ctx.getSource().getServer();
        if (pvp) {
            ctx.getSource().sendFailure(Component.literal("Нельзя менять команды во время ПВП."));
            return 0;
        }
        if (!randomTeams()) {
            ctx.getSource().sendFailure(Component.literal("Для разделения на команды нужно минимум 2 игрока."));
            return 0;
        }
        return 1;
    }

    /** Случайно делит игроков онлайн на team1 и team2. */
    private static boolean randomTeams() {
        List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());
        if (players.size() < 2) return false;

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
        return true;
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
        deathLog.clear();
        firstBlood = null;
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
        int n = aliveKeys().size();
        if (n == 1) {
            announceWinners();
        } else if (n == 0) {
            announceDraw();
        }
    }

    private static void announceDraw() {
        winnerAnnounced = true;
        titleAll("НИЧЬЯ", "gray", "Победителей нет", "gray", "10 60 20");
        soundAll("minecraft:entity.lightning_bolt.thunder", "0.7 0.8");
        broadcast(Component.literal("🤝 Ничья — никого не осталось в живых.").withStyle(ChatFormatting.GRAY));
        printResults("ничья, никого не осталось");
        runCmd("effect clear @a minecraft:glowing");
        setKeepInventory(true);
        scheduleGameEnd();
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
        String winnersText;
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
            winnersText = teamName + " (" + String.join(", ", nicks) + ")";
            chat = Component.literal("★ ПОБЕДИЛА КОМАНДА " + teamName + ": " + String.join(", ", nicks) + " ★")
                    .withStyle(teamFormat(teamName));
        } else {
            String name = first.getName().getString();
            winnerSelector = "name=" + name;
            title = name;
            subtitle = "ПОБЕДИТЕЛЬ ПВП";
            color = "gold";
            winnersText = name;
            chat = Component.literal("★ ПОБЕДИТЕЛЬ: " + name + " ★").withStyle(ChatFormatting.GOLD);
        }

        titleAll(title, color, subtitle, "yellow", "10 80 20");
        soundAll("minecraft:ui.toast.challenge_complete", "1 1");
        soundAll("minecraft:entity.lightning_bolt.thunder", "0.7 1.3");
        broadcast(chat);

        // Итоги игры
        printResults(winnersText);

        // Подсветку убираем, сохранение инвентаря возвращается
        runCmd("effect clear @a minecraft:glowing");
        setKeepInventory(true);
        broadcast(Component.literal("🔒 Сохранение инвентаря снова включено.").withStyle(ChatFormatting.GREEN));

        scheduleGameEnd();
    }

    /** Итоги игры в чат. winners может быть null (если победителей не определяли). */
    private static void printResults(String winners) {
        chat("━━━━━━━━━━━ ИТОГИ ИГРЫ ━━━━━━━━━━━", ChatFormatting.GOLD);
        if (winners != null) {
            chat("🏆 Победа: " + winners, ChatFormatting.GOLD);
        }
        chat("⏱ Длительность ПВП: " + formatTime(pvpTicks), ChatFormatting.YELLOW);

        if (firstBlood != null) {
            chat("🩸 Первая кровь: " + firstBlood, ChatFormatting.RED);
        }

        if (kills.isEmpty()) {
            chat("🗡 Убийств игроками не было.", ChatFormatting.GRAY);
        } else {
            List<Map.Entry<String, Integer>> top = new ArrayList<>(kills.entrySet());
            top.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()));
            String[] medals = {"🥇", "🥈", "🥉"};
            chat("🗡 Больше всего убийств:", ChatFormatting.YELLOW);
            for (int i = 0; i < Math.min(3, top.size()); i++) {
                chat("   " + medals[i] + " " + top.get(i).getKey() + " — " + top.get(i).getValue(),
                        ChatFormatting.YELLOW);
            }
        }

        if (!deathLog.isEmpty()) {
            chat("☠ Порядок выбывания: " + String.join(" → ", deathLog), ChatFormatting.GRAY);
        }
        chat("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━", ChatFormatting.GOLD);
    }

    /** Если игра запущена через /game start — через несколько секунд закончить её. */
    private static void scheduleGameEnd() {
        if (gameActive) {
            gameEndTicks = GAME_END_DELAY_TICKS;
            broadcast(Component.literal("🏁 Через " + (GAME_END_DELAY_TICKS / 20)
                    + " сек. игра закончится: инвентари очистятся, все будут убиты и возрождены.")
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    private static void endPvp() {
        // Если ПВП закончили вручную и итогов ещё не было — покажем их
        if (!winnerAnnounced && !fallen.isEmpty()) {
            printResults(null);
        }

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

    private static void chat(String text, ChatFormatting format) {
        broadcast(Component.literal(text).withStyle(format));
    }

    private static void broadcast(Component text) {
        if (server != null) {
            server.getPlayerList().broadcastSystemMessage(text, false);
        }
    }
}
