package org.example.easyban;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Scheduler bridge that works on classic Paper/Spigot AND on Folia.
 *
 * <p>{@code Bukkit.getScheduler()} throws UnsupportedOperationException on Folia,
 * so every scheduled task of EasyBan goes through this class. Folia's schedulers
 * are reached through reflection, which means the plugin still compiles and runs
 * on any Paper API version, old or new (26.x included).</p>
 */
public final class SchedulerCompat {

    private static final String SCHEDULER_PACKAGE = "io.papermc.paper.threadedregions.scheduler.";
    private static Boolean folia;

    private SchedulerCompat() {
    }

    /** True when running on a regionised (Folia) server. */
    public static boolean isFolia() {
        if (folia == null) {
            boolean found;
            try {
                Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
                found = true;
            } catch (Throwable ignored) {
                found = false;
            }
            folia = found;
        }
        return folia;
    }

    /** Runs on the main thread (Paper) or the global region thread (Folia). */
    public static void runSync(Plugin plugin, Runnable task) {
        if (plugin == null || !plugin.isEnabled()) return;
        if (isFolia()) {
            try {
                Object scheduler = serverScheduler("getGlobalRegionScheduler");
                Method run = Class.forName(SCHEDULER_PACKAGE + "GlobalRegionScheduler")
                        .getMethod("run", Plugin.class, Consumer.class);
                run.invoke(scheduler, plugin, consumer(task));
                return;
            } catch (Throwable ignored) {
                // fall through to the legacy scheduler
            }
        }
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (Throwable t) {
            warn(plugin, t);
        }
    }

    /** Delayed main-thread / global-region task. */
    public static void runLater(Plugin plugin, Runnable task, long delayTicks) {
        if (plugin == null || !plugin.isEnabled()) return;
        if (isFolia()) {
            try {
                Object scheduler = serverScheduler("getGlobalRegionScheduler");
                Method run = Class.forName(SCHEDULER_PACKAGE + "GlobalRegionScheduler")
                        .getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
                run.invoke(scheduler, plugin, consumer(task), Math.max(1L, delayTicks));
                return;
            } catch (Throwable ignored) {
            }
        }
        try {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        } catch (Throwable t) {
            warn(plugin, t);
        }
    }

    /** Runs off the game thread. */
    public static void runAsync(Plugin plugin, Runnable task) {
        if (plugin == null || !plugin.isEnabled()) return;
        if (isFolia()) {
            try {
                Object scheduler = serverScheduler("getAsyncScheduler");
                Method run = Class.forName(SCHEDULER_PACKAGE + "AsyncScheduler")
                        .getMethod("runNow", Plugin.class, Consumer.class);
                run.invoke(scheduler, plugin, consumer(task));
                return;
            } catch (Throwable ignored) {
            }
        }
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        } catch (Throwable t) {
            warn(plugin, t);
        }
    }

    /** Repeating async task (ticks are converted to milliseconds on Folia). */
    public static void runAsyncTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (plugin == null || !plugin.isEnabled()) return;
        if (isFolia()) {
            try {
                Object scheduler = serverScheduler("getAsyncScheduler");
                Method run = Class.forName(SCHEDULER_PACKAGE + "AsyncScheduler")
                        .getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class);
                run.invoke(scheduler, plugin, consumer(task),
                        Math.max(1L, delayTicks) * 50L, Math.max(1L, periodTicks) * 50L, TimeUnit.MILLISECONDS);
                return;
            } catch (Throwable ignored) {
            }
        }
        try {
            Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks);
        } catch (Throwable t) {
            warn(plugin, t);
        }
    }

    /** Runs on the thread that owns the entity (Folia) or the main thread (Paper). */
    public static void runEntity(Plugin plugin, Entity entity, Runnable task) {
        if (plugin == null || !plugin.isEnabled() || entity == null) return;
        if (isFolia()) {
            try {
                Object entityScheduler = entityScheduler(entity);
                Method run = Class.forName(SCHEDULER_PACKAGE + "EntityScheduler")
                        .getMethod("run", Plugin.class, Consumer.class, Runnable.class);
                run.invoke(entityScheduler, plugin, consumer(task), null);
                return;
            } catch (Throwable ignored) {
            }
        }
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (Throwable t) {
            warn(plugin, t);
        }
    }

    /** Cancels every task this plugin still has queued. */
    public static void cancelAll(Plugin plugin) {
        if (plugin == null) return;
        if (isFolia()) {
            tryCancel(plugin, "getGlobalRegionScheduler", "GlobalRegionScheduler");
            tryCancel(plugin, "getAsyncScheduler", "AsyncScheduler");
        }
        try {
            Bukkit.getScheduler().cancelTasks(plugin);
        } catch (Throwable ignored) {
            // Not available on Folia - the calls above already handled it.
        }
    }

    // ------------------------------------------------------------------ //
    //  Internals                                                          //
    // ------------------------------------------------------------------ //

    private static void tryCancel(Plugin plugin, String getter, String iface) {
        try {
            Object scheduler = serverScheduler(getter);
            Method cancel = Class.forName(SCHEDULER_PACKAGE + iface).getMethod("cancelTasks", Plugin.class);
            cancel.invoke(scheduler, plugin);
        } catch (Throwable ignored) {
        }
    }

    private static Object serverScheduler(String getter) throws Exception {
        Method m = Class.forName("org.bukkit.Server").getMethod(getter);
        return m.invoke(Bukkit.getServer());
    }

    private static Object entityScheduler(Entity entity) throws Exception {
        try {
            return Class.forName("org.bukkit.entity.Entity").getMethod("getScheduler").invoke(entity);
        } catch (NoSuchMethodException e) {
            return entity.getClass().getMethod("getScheduler").invoke(entity);
        }
    }

    private static Consumer<Object> consumer(Runnable task) {
        return scheduledTask -> task.run();
    }

    private static void warn(Plugin plugin, Throwable t) {
        plugin.getLogger().warning("Could not schedule a task: " + t);
    }
}