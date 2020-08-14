// Part of PMSite: https://pmsite.machinezoo.com
package com.machinezoo.pmsite.utils;

import java.nio.file.*;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.*;
import org.slf4j.*;
import com.google.common.base.*;
import com.machinezoo.noexception.*;
import com.machinezoo.stagean.*;

/*
 * Finding a suitable place in the filesystem for various kinds of files is non-trivial,
 * so let's concentrate all the logic in one class.
 */
/**
 * Access to standard filesystem locations.
 */
@StubDocs
@DraftApi("find or create cross-platform library for standard directory locations")
@DraftCode("designed to work on linux, so-so works on Windows")
public class SiteFiles {
	/*
	 * Allow override of the default locations, which is particularly useful in production.
	 * This has to be done in main() before locations are used for the first time.
	 * Contrary to the defaults, we don't allow environment variables here, because we don't have to.
	 * When applications override locations, it's usually because they know very well where data should be stored.
	 */
	private static Path configIn;
	public static void configIn(Path path) {
		Objects.requireNonNull(path);
		configIn = path;
	}
	private static Path dataIn;
	public static void dataIn(Path path) {
		Objects.requireNonNull(path);
		dataIn = path;
	}
	private static Path cacheIn;
	public static void cacheIn(Path path) {
		Objects.requireNonNull(path);
		cacheIn = path;
	}
	/*
	 * Linux defines three special directories and we want to expose them all here.
	 * Cache is most useful in web apps, but local development mode could make good use of config and data directories.
	 */
	private static final Supplier<Path> config = Suppliers.memoize(() -> create("config", "XDG_CONFIG_HOME", "$HOME/.config", configIn));
	public static Path config() {
		return config.get();
	}
	private static final Supplier<Path> data = Suppliers.memoize(() -> create("data", "XDG_DATA_HOME", "$HOME/.local/share", dataIn));
	public static Path data() {
		return data.get();
	}
	private static final Supplier<Path> cache = Suppliers.memoize(() -> create("cache", "XDG_CACHE_HOME", "$HOME/.cache", cacheIn));
	public static Path cache() {
		return cache.get();
	}
	private static final Pattern variableRe = Pattern.compile("\\$([a-zA-Z_][a-zA-Z_0-9]*)");
	private static Logger logger = LoggerFactory.getLogger(SiteFiles.class);
	private static Path create(String kind, String xdg, String fallback, Path override) {
		Path path;
		if (override != null) {
			/*
			 * If override was specified, we can skip all other logic.
			 */
			path = override;
		} else {
			/*
			 * First try XDG_* variables. Data directories may be in strange locations, for example inside flatpak.
			 */
			String configured = System.getenv(xdg);
			if (configured == null)
				configured = fallback;
			/*
			 * Java doesn't have any built-in environment variable expander, so here's a simple custom one.
			 */
			String expanded = configured;
			int count = 0;
			while (true) {
				Matcher matcher = variableRe.matcher(expanded);
				if (!matcher.find())
					break;
				String variable = matcher.group(1);
				String value = System.getenv(variable);
				if (value == null)
					value = "";
				expanded = expanded.replace("$" + variable, value);
				++count;
				if (count > 100)
					throw new IllegalStateException("Infinite recursion while expanding environment variables: " + configured);
			}
			/*
			 * All PMSite-based apps will store files in the same directories.
			 * That means they have to be careful how they are named.
			 * On the other hand, such sharing of data may be an advantage.
			 * And after all, there can be several sites in the same process, so some sharing is inevitable.
			 * 
			 * We could add a static method to configure app name in main(),
			 * but unit tests would continue to use the default "pmsite".
			 * The only reliable way to configure it is to read it from resources or properties,
			 * but we are currently lazy to implement it since the default works well enough.
			 * 
			 * We could also default to storing data under a subfolder of current folder,
			 * which we could assume to be the current project source tree,
			 * but writing application data files into source tree is not very clean.
			 * Writing them under target/ would make them subject to regular deletion.
			 */
			path = Paths.get(expanded, "pmsite");
		}
		logger.info("Storing {} files under {}.", kind, path);
		/*
		 * Create the directory as a convenience for apps.
		 */
		Exceptions.sneak().run(() -> Files.createDirectories(path));
		return path;
	}
	/*
	 * Provide extra convenience methods for subdirectories since every class will likely want its own directory.
	 */
	public static Path configOf(String name) {
		return subdir(config.get(), name);
	}
	public static Path dataOf(String name) {
		return subdir(data.get(), name);
	}
	public static Path cacheOf(String name) {
		return subdir(cache.get(), name);
	}
	private static Path subdir(Path root, String name) {
		Path path = root.resolve(name);
		/*
		 * Create the directory as a convenience for apps.
		 */
		Exceptions.sneak().run(() -> Files.createDirectories(path));
		return path;
	}
}
