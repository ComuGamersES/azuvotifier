package com.vexsoftware.votifier.platform.scheduler;

import com.vexsoftware.votifier.NuVotifierBukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.TimeUnit;

public class BukkitScheduler implements VotifierScheduler {

    private final NuVotifierBukkit plugin;

    private static final long MILLIS_PER_TICK = 50L;

    public BukkitScheduler(NuVotifierBukkit plugin) {
        this.plugin = plugin;
    }

    private int convertToTicks(int time, TimeUnit unit) {
        return (int) (unit.toMillis(time) / MILLIS_PER_TICK);
    }

    @Override
    public ScheduledVotifierTask delayedOnPool(Runnable runnable, int delay, TimeUnit unit) {
        int delayTicks = convertToTicks(delay, unit);
        BukkitTask task = plugin.getServer().getScheduler()
                .runTaskLaterAsynchronously(plugin, runnable, delayTicks);
        return new BukkitTaskWrapper(task);
    }

    @Override
    public ScheduledVotifierTask repeatOnPool(Runnable runnable, int delay, int repeat, TimeUnit unit) {
        int delayTicks = convertToTicks(delay, unit);
        int repeatTicks = convertToTicks(repeat, unit);
        BukkitTask task = plugin.getServer().getScheduler()
                .runTaskTimerAsynchronously(plugin, runnable, delayTicks, repeatTicks);
        return new BukkitTaskWrapper(task);
    }

    private static class BukkitTaskWrapper implements ScheduledVotifierTask {

        private final BukkitTask task;

        BukkitTaskWrapper(BukkitTask task) {
            this.task = task;
        }

        @Override
        public void cancel() {
            task.cancel();
        }
    }
}
