// Part of PMSite: https://pmsite.machinezoo.com
package com.machinezoo.pmsite.utils;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.machinezoo.hookless.*;
import com.machinezoo.noexception.*;
import com.machinezoo.noexception.optional.*;
import com.machinezoo.noexception.slf4j.*;
import com.machinezoo.stagean.*;
import one.util.streamex.*;

/*
 * This class essentially exposes reactive dependency on content of application class files and resources.
 * It is used only during development. This class does nothing in production and during unit test runs.
 * Dependency on code allows us to refresh pages and various caches whenever application code changes during development.
 * 
 * Reloading code without application restart is obviously possible for plain resources, but changes in class files are more tricky.
 * Changes that affect only method body can be reloaded even with standard Java in debug mode.
 * More complex code changes are only reloaded when using some Java extension, for example DCEVM.
 * 
 * This class however doesn't do any code reloading itself. It just ensures that reactive computations are reexecuted,
 * which gives the reloaded code an opportunity to take effect.
 * 
 * This only works for reactive computations that query this class.
 * We might wish to refresh all reactive computations, but that would require global refresh feature in hookless.
 * 
 * Temporary, replace with:
 * - reactive filesystem & resources in hookless
 * - reactive buster generator that sits on top of the above
 * - data URIs and CDN for most page resources
 * - restart whole app upon code change the way Spring Boot does it
 * - Cache-Control for everything else
 */
/**
 * Resource and code reloading support.
 */
@StubDocs
@DraftApi("temporary, see comments")
public class SiteReload {
    /*
     * Application launch time is a good default.
     * It changes with every restart, so changes not captured at run time are captured in launch time.
     */
    private static final Instant launch = Instant.now();
    /*
     * Use launch time as default, because constant default would result in unwanted caching in browsers
     * for resources that are cache-busted using only the timestamp rather than content hash.
     */
    private static final ReactiveVariable<Instant> refresh = new ReactiveVariable<Instant>(launch);
    private static final boolean DEBUG_MODE;
    static {
        DEBUG_MODE = Exceptions.silence()
            /*
             * Is this sufficiently general and reliable? Probably not.
             * Should do a little research and expand this code or wrap it in a library.
             */
            .getAsBoolean(() -> ProcessHandle.current().info().commandLine().orElse("").contains("jdwp"))
            .orElse(false);
    }
    private static volatile OptionalBoolean enabled = OptionalBoolean.empty();
    private static boolean enabled() { return enabled.orElse(DEBUG_MODE && SiteRunMode.get() == SiteRunMode.DEVELOPMENT); }
    public static void enable(boolean enabled) { SiteReload.enabled = OptionalBoolean.of(enabled); }
    public static void watch() {
        if (enabled())
            refresh.get();
    }
    /*
     * Cache buster is appended to URLs to force reloading of changed resources.
     * Using timestamps as cache busters results in too many resource reloads.
     * It is better to use content hash as cache buster.
     * Timestamp is however an acceptable fallback in case content hash is not available.
     */
    private static final String buster = "?v=" + launch.toEpochMilli();
    public static String buster() {
        if (!enabled())
            return buster;
        return "?v=" + refresh.get().toEpochMilli();
    }
    /*
     * By default, we monitor all directories is class path and module path, which includes, in case of Maven project opened in Eclipse,
     * target/classes directories of current project as well as all projects resolved within the workspace.
     * If this automatic detection fails, application can always customize this list.
     * 
     * Volatile is a simple way to ensure writes are observed in other threads without locking.
     * Applications should nevertheless complete configuration of SiteReload before starting it.
     */
    private static volatile Path[] roots = StreamEx.of(System.getProperty("java.class.path", "").split(":"))
        .append(System.getProperty("jdk.module.path", "").split(":"))
        .map(Paths::get)
        .filter(Files::isDirectory)
        .toArray(Path[]::new);
    public static void roots(Path[] roots) { SiteReload.roots = roots; }
    /*
     * Filesystem is probed at fixed interval. It is simpler and more reliable than filesystem monitoring.
     * With 100ms intervals, it is fast enough to seem interactive.
     * Total time between hitting Ctrl+S and seeing changes in the browser should then be under 200ms.
     */
    private static volatile int interval = 100;
    public static void interval(Duration interval) { SiteReload.interval = (int)interval.toMillis(); }
    /*
     * JVM lags behind code changes. When we detect file changes, JVM might be still using the old code.
     * In order to give JVM time to reload changed code, we trigger page/cache refresh at a number of latencies.
     */
    private static volatile int[] latencies = new int[] {
        100,
        300,
        1_000,
        3_000
    };
    public static void latencies(Duration... latencies) { SiteReload.latencies = Arrays.stream(latencies).mapToInt(d -> (int)d.toMillis()).toArray(); }
    /*
     * Live reload is initialized automatically when it is first used. It's easier to use this way.
     * Without explicit start() method, there is nothing to put in main() that would slow down app initialization.
     */
    private static ScheduledExecutorService executor;
    static {
        if (enabled()) {
            /*
             * Live reload initialization is delayed, because it is non-essential and heavy.
             */
            SiteLaunch.delay(() -> {
                executor = new SiteThread()
                    .owner(SiteReload.class)
                    .lowestPriority()
                    .scheduled();
                /*
                 * If checking code fails, for example when file disappears before we can call Files.getLastModifiedTime() on it,
                 * just log the exception and try again in the next iteration.
                 * This risks producing an avalanche of errors if the exceptions persists (bad configuration, protected file, ...),
                 * but this only happens during development, so nobody will be particularly upset about it.
                 */
                executor.scheduleAtFixedRate(ExceptionLogging.log().runnable(SiteReload::check), 0, interval, TimeUnit.MILLISECONDS);
            });
        }
    }
    private static Instant last = launch;
    private static void check() {
        Instant current = Arrays.stream(roots)
            .flatMap(Exceptions.sneak().function(p -> Files.walk(p)))
            .filter(Files::isRegularFile)
            .map(p -> Exceptions.sneak().get(() -> Files.getLastModifiedTime(p)).toInstant())
            .max(Comparator.naturalOrder())
            /*
             * If no files are found (likely a configuration error), default to launch time instead of throwing.
             */
            .orElse(launch);
        if (current.compareTo(last) != 0) {
            last = current;
            /*
             * Refresh with current time rather than file time.
             * This gives us an opportunity to trigger refresh several times later with different latencies.
             */
            refresh.set(Instant.now());
            for (int latency : latencies)
                executor.schedule(() -> refresh.set(Instant.now()), latency, TimeUnit.MILLISECONDS);
        }
    }
}
