package ru.gsdev.gsautorestart;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GsAutoRestart extends JavaPlugin {

    private BossBar bossBar;
    private int initialCountdownTime;
    private int countdownTime;
    private boolean isCountdownRunning = false;
    private int taskId;
    private Set<Integer> titleDisplayTimes = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfigValues();
        getLogger().info("AutoRestart включен!");
    }

    private void reloadConfigValues() {
        FileConfiguration config = getConfig();
        titleDisplayTimes.clear();
        List<Integer> times = config.getIntegerList("title-display-times");
        titleDisplayTimes.addAll(times);
        initializeBossBar();
    }

    private void initializeBossBar() {
        if (bossBar != null) {
            bossBar.removeAll();
        }

        FileConfiguration config = getConfig();
        bossBar = Bukkit.createBossBar(
                colorize(config.getString("bossbar.title", "&cРестарт через: &e{time}")),
                BarColor.valueOf(config.getString("bossbar.color", "RED")),
                BarStyle.valueOf(config.getString("bossbar.style", "SOLID"))
        );
        bossBar.setVisible(false);
    }

    @Override
    public void onDisable() {
        if (isCountdownRunning) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        if (bossBar != null) {
            bossBar.removeAll();
        }
        getLogger().info("AutoRestart выключен!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("autorestart")) return false;

        if (!sender.hasPermission("autorestart.use")) {
            sendMsg(sender, "messages.no-permission", "&cНет прав!");
            return true;
        }

        if (args.length == 0) {
            sendMsg(sender, "messages.usage", "&cИспользуйте: /autorestart <время> или /autorestart отмена");
            return true;
        }

        if (args[0].equalsIgnoreCase("отмена") || args[0].equalsIgnoreCase("cancel")) {
            handleCancel(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            reloadConfigValues();
            sendMsg(sender, "messages.reloaded", "&aКонфиг перезагружен!");
            return true;
        }

        if (isCountdownRunning) {
            sendMsg(sender, "messages.already-running", "&cОтсчет уже идет!");
            return true;
        }

        int seconds = parseTime(args[0]);
        if (seconds <= 0) {
            sendMsg(sender, "messages.invalid-time", "&cНеверное время! Пример: 300s, 5m, 1h");
            return true;
        }

        startCountdown(seconds);
        sendMsg(sender, "messages.started", "&aРестарт через {time}!", seconds);
        return true;
    }

    private void handleCancel(CommandSender sender) {
        if (isCountdownRunning) {
            cancelCountdown();
            sendMsg(sender, "messages.cancelled", "&aОтменено!");
        } else {
            sendMsg(sender, "messages.no-countdown", "&cНет активного отсчета!");
        }
    }

    private void startCountdown(int seconds) {
        initialCountdownTime = seconds;
        countdownTime = seconds;
        isCountdownRunning = true;

        bossBar.setVisible(true);
        updateBossBarPlayers();
        updateBossBar();

        taskId = new BukkitRunnable() {
            @Override
            public void run() {
                if (countdownTime <= 0) {
                    sendFinalTitles();
                    Bukkit.getScheduler().runTaskLater(GsAutoRestart.this, Bukkit::shutdown,
                            getConfig().getInt("titles.final-stay", 70));
                    bossBar.removeAll();
                    this.cancel();
                    return;
                }

                updateBossBar();

                if (titleDisplayTimes.contains(countdownTime)) {
                    sendTitleToAll(false);
                }

                if (shouldBroadcast(countdownTime)) {
                    broadcast("messages.broadcast", "&cРестарт через &e{time}&c!", countdownTime);
                }

                countdownTime--;
            }
        }.runTaskTimer(this, 0L, 20L).getTaskId();
    }

    private void sendFinalTitles() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            sendTitle(player, true);
        }
    }

    private void sendTitleToAll(boolean isFinal) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            sendTitle(player, isFinal);
        }
    }

    private void sendTitle(Player player, boolean isFinal) {
        String titlePath = isFinal ? "titles.final-main" : "titles.main";
        String subtitlePath = isFinal ? "titles.final-subtitle" : "titles.subtitle";

        player.sendTitle(
                colorize(getCfgString(titlePath, isFinal ? "&4РЕСТАРТ!" : "&cРестарт"))
                        .replace("{time}", formatTime(countdownTime)),
                colorize(getCfgString(subtitlePath, "&eЧерез: {time}"))
                        .replace("{time}", formatTime(countdownTime)),
                getConfig().getInt("titles.fade-in", 10),
                getConfig().getInt("titles.stay", 20),
                getConfig().getInt("titles.fade-out", 20)
        );
    }

    private void updateBossBarPlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!bossBar.getPlayers().contains(player)) {
                bossBar.addPlayer(player);
            }
        }
    }

    private void updateBossBar() {
        bossBar.setTitle(colorize(getCfgString("bossbar.title", "&cРестарт через: &e{time}"))
                .replace("{time}", formatTime(countdownTime)));
        bossBar.setProgress((double) countdownTime / (double) initialCountdownTime);
        updateBossBarPlayers();
    }

    private void cancelCountdown() {
        Bukkit.getScheduler().cancelTask(taskId);
        isCountdownRunning = false;
        bossBar.setVisible(false);
        bossBar.removeAll();
    }

    private boolean shouldBroadcast(int seconds) {
        return titleDisplayTimes.contains(seconds) || seconds <= 10;
    }

    private int parseTime(String timeStr) {
        Matcher m = Pattern.compile("^(\\d+)([smh])$").matcher(timeStr.toLowerCase());
        if (!m.find()) return -1;

        int val = Integer.parseInt(m.group(1));
        switch (m.group(2)) {
            case "s": return val;
            case "m": return val * 60;
            case "h": return val * 3600;
            default: return -1;
        }
    }

    private String formatTime(int seconds) {
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;

        if (h > 0) return String.format("%dч %dм %dс", h, m, s);
        if (m > 0) return String.format("%dм %dс", m, s);
        return String.format("%dс", s);
    }

    private void sendMsg(CommandSender sender, String path, String def, Object... args) {
        sender.sendMessage(colorize(getCfgString(path, def).replace("{time}", args.length > 0 ? formatTime((Integer)args[0]) : "")));
    }

    private void broadcast(String path, String def, int time) {
        Bukkit.broadcastMessage(colorize(getCfgString(path, def).replace("{time}", formatTime(time))));
    }

    private String getCfgString(String path, String def) {
        return getConfig().getString(path, def);
    }

    private String colorize(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}