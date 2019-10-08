// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite;

import java.nio.file.*;
import java.util.function.Supplier;
import java.util.regex.*;
import org.slf4j.*;
import com.google.common.base.*;
import com.machinezoo.noexception.*;

public class SiteFiles {
	private static Logger logger = LoggerFactory.getLogger(SiteFiles.class);
	private static final Supplier<Path> config = Suppliers.memoize(() -> create("config", "XDG_CONFIG_HOME", "$HOME/.config"));
	public static Path config() {
		return config.get();
	}
	public static Path config(String name) {
		return subdir(config.get(), name);
	}
	private static final Supplier<Path> data = Suppliers.memoize(() -> create("data", "XDG_DATA_HOME", "$HOME/.local/share"));
	public static Path data() {
		return data.get();
	}
	public static Path data(String name) {
		return subdir(data.get(), name);
	}
	private static final Supplier<Path> cache = Suppliers.memoize(() -> create("cache", "XDG_CACHE_HOME", "$HOME/.cache"));
	public static Path cache() {
		return cache.get();
	}
	public static Path cache(String name) {
		return subdir(cache.get(), name);
	}
	private static final Pattern variableRe = Pattern.compile("$([a-zA-Z_][a-zA-Z_0-9]*)");
	private static Path create(String kind, String xdg, String fallback) {
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
		Path path = Paths.get(expanded, "pmsite");
		logger.info("Storing {} files under {}.", kind, expanded);
		/*
		 * Create the directory as a convenience for apps.
		 */
		Exceptions.sneak().run(() -> Files.createDirectories(path));
		return path;
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
