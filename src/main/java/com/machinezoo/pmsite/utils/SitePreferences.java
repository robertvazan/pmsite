// Part of PMSite: https://pmsite.machinezoo.com
package com.machinezoo.pmsite.utils;

import static java.util.stream.Collectors.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.prefs.*;
import com.machinezoo.hookless.*;
import com.machinezoo.hookless.prefs.*;
import com.machinezoo.noexception.*;
import com.machinezoo.stagean.*;

/**
 * Utility {@link ReactivePreferences} implementations.
 */
@StubDocs
@DraftApi("spin off into dedicated library, wrapper for unlimited key/node/value strings")
public class SitePreferences {
	public static void storeIn(Path path) {
		/*
		 * This only covers linux implementation. Windows registry implementation is not affected.
		 * 
		 * Linux Preferences will complain about not being able to write systemRoot (in /etc)
		 * if Preferences.systemRoot() is ever called by the application,
		 * regardless of whether the returned Preferences object is ever used.
		 * Even userRoot location is not right. It's somewhere in $HOME, which is a tmpfs inside flatpak.
		 * We will redirect both into the specified path to avoid these problems.
		 * This also allows the application to separate its preferences from other applications in an application-specific directory.
		 * 
		 * We have to create .systemPrefs subdirectory as otherwise java.util.prefs.systemRoot property would be ignored.
		 */
		Exceptions.sneak().run(() -> Files.createDirectories(path.resolve(".systemPrefs")));
		if (System.getProperty("java.util.prefs.userRoot") == null)
			System.setProperty("java.util.prefs.userRoot", path.toString());
		if (System.getProperty("java.util.prefs.systemRoot") == null)
			System.setProperty("java.util.prefs.systemRoot", path.toString());
	}
	private static final ReactivePreferencesFactory common = new ReactivePreferencesFactory() {
		@Override
		public ReactivePreferences systemRoot() {
			return ReactivePreferences.systemRoot();
		}
		@Override
		public ReactivePreferences userRoot() {
			return ReactivePreferences.userRoot();
		}
	};
	private static final ThreadLocal<ReactivePreferencesFactory> current = new ThreadLocal<>();
	public static class ThreadLocalFactory implements ReactivePreferencesFactory {
		private ReactivePreferencesFactory current() {
			return Optional.ofNullable(current.get()).orElse(common);
		}
		@Override
		public ReactivePreferences systemRoot() {
			return current().systemRoot();
		}
		@Override
		public ReactivePreferences userRoot() {
			return current().userRoot();
		}
	}
	public static CloseableScope open(ReactivePreferencesFactory prefs) {
		var previous = current.get();
		current.set(prefs);
		return () -> current.set(previous);
	}
	public static void runWith(ReactivePreferencesFactory prefs, Runnable runnable) {
		try (var context = open(prefs)) {
			runnable.run();
		}
	}
	public static <T> T supplyWith(ReactivePreferencesFactory prefs, Supplier<T> supplier) {
		try (var context = open(prefs)) {
			return supplier.get();
		}
	}
	private static class Memory extends AbstractPreferences {
		private final Memory parent;
		private final Map<String, Memory> children = new HashMap<>();
		private final Map<String, String> values = new HashMap<>();
		Memory(Memory parent, String name) {
			super(parent, name);
			this.parent = parent;
			newNode = true;
		}
		@Override
		public boolean isUserNode() {
			return false;
		}
		@Override
		protected String[] childrenNamesSpi() {
			return children.keySet().stream().toArray(String[]::new);
		}
		@Override
		protected Memory childSpi(String name) {
			Memory child = children.get(name);
			if (child == null)
				children.put(name, child = new Memory(this, name));
			return child;
		}
		@Override
		protected void removeNodeSpi() {
			parent.children.remove(name());
		}
		@Override
		protected String[] keysSpi() {
			return values.keySet().stream().toArray(String[]::new);
		}
		@Override
		protected String getSpi(String key) {
			return values.get(key);
		}
		@Override
		protected void putSpi(String key, String value) {
			values.put(key, value);
		}
		@Override
		protected void removeSpi(String key) {
			values.remove(key);
		}
		@Override
		protected void flushSpi() {
		}
		@Override
		protected void syncSpi() {
		}
	}
	public static ReactivePreferences memory() {
		return ReactivePreferences.wrap(new Memory(null, ""));
	}
	private static void requireRoot(ReactivePreferences prefs) {
		if (prefs.parent() != null)
			throw new IllegalArgumentException("Root ReactivePreferences node required.");
	}
	private static class Proxy extends AbstractReactivePreferences {
		final ReactivePreferences innerRoot;
		final ReactivePreferences inner;
		Proxy(ReactivePreferences innerRoot) {
			super(null, "");
			this.innerRoot = innerRoot;
			inner = innerRoot;
		}
		Proxy(Proxy parent, String name) {
			super(parent, name);
			innerRoot = parent.innerRoot;
			inner = parent.inner.node(name);
		}
		@Override
		protected String[] childrenNamesSpi() throws BackingStoreException {
			return inner.childrenNames();
		}
		@Override
		protected AbstractReactivePreferences childSpi(String name) {
			return new Proxy(this, name);
		}
		@Override
		protected CompletableFuture<Void> removeNodeSpi() {
			return inner.removeNode();
		}
		@Override
		protected String[] keysSpi() throws BackingStoreException {
			return inner.keys();
		}
		@Override
		protected String getSpi(String key) {
			return inner.get(key, null);
		}
		@Override
		protected void putSpi(String key, String value) {
			inner.put(key, value);
		}
		@Override
		protected void removeSpi(String key) {
			inner.remove(key);
		}
		@Override
		protected CompletableFuture<Void> flushSpi() {
			return inner.flush();
		}
	}
	public static ReactivePreferences subtree(ReactivePreferences prefs) {
		return new Proxy(prefs);
	}
	private static class AsUser extends Proxy {
		AsUser(ReactivePreferences innerRoot) {
			super(innerRoot);
			requireRoot(innerRoot);
		}
		AsUser(AsUser parent, String name) {
			super(parent, name);
		}
		@Override
		public boolean isUserNode() {
			return true;
		}
		@Override
		protected AbstractReactivePreferences childSpi(String name) {
			return new AsUser(this, name);
		}
	}
	public static ReactivePreferences asUser(ReactivePreferences prefs) {
		return new AsUser(prefs);
	}
	private static class AsSystem extends Proxy {
		AsSystem(ReactivePreferences innerRoot) {
			super(innerRoot);
			requireRoot(innerRoot);
		}
		AsSystem(AsSystem parent, String name) {
			super(parent, name);
		}
		@Override
		public boolean isUserNode() {
			return false;
		}
		@Override
		protected AbstractReactivePreferences childSpi(String name) {
			return new AsSystem(this, name);
		}
	}
	public static ReactivePreferences asSystem(ReactivePreferences prefs) {
		return new AsSystem(prefs);
	}
	private static class Cascade extends AbstractReactivePreferences {
		private final List<ReactivePreferences> layers;
		Cascade(ReactivePreferences... layers) {
			super(null, "");
			for (var layer : layers)
				requireRoot(layer);
			this.layers = List.of(layers);
		}
		Cascade(Cascade parent, String name) {
			super(parent, name);
			layers = parent.layers.stream().map(p -> p.node(name)).collect(toList());
		}
		@Override
		protected String[] childrenNamesSpi() throws BackingStoreException {
			return layers.stream()
				.flatMap(Exceptions.sneak().function(p -> Arrays.stream(p.childrenNames())))
				.distinct()
				.toArray(String[]::new);
		}
		@Override
		protected AbstractReactivePreferences childSpi(String name) {
			return new Cascade(this, name);
		}
		@Override
		protected CompletableFuture<Void> removeNodeSpi() {
			return CompletableFuture.allOf(layers.stream().map(p -> p.removeNode()).toArray(CompletableFuture[]::new));
		}
		@Override
		protected String[] keysSpi() throws BackingStoreException {
			return layers.stream()
				.flatMap(Exceptions.sneak().function(p -> Arrays.stream(p.keys())))
				.distinct()
				.toArray(String[]::new);
		}
		@Override
		protected String getSpi(String key) {
			for (var layer : layers) {
				String value = layer.get(key, null);
				if (value != null)
					return value;
			}
			return null;
		}
		@Override
		protected void putSpi(String key, String value) {
			for (var layer : layers)
				layer.put(key, value);
		}
		@Override
		protected void removeSpi(String key) {
			for (var layer : layers)
				layer.remove(key);
		}
		@Override
		protected CompletableFuture<Void> flushSpi() {
			return CompletableFuture.allOf(layers.stream().map(p -> p.flush()).toArray(CompletableFuture[]::new));
		}
	}
	public static ReactivePreferences cascade(ReactivePreferences... layers) {
		return new Cascade(layers);
	}
	private static class Freezing extends Proxy {
		Freezing(ReactivePreferences innerRoot) {
			super(innerRoot);
			requireRoot(innerRoot);
		}
		Freezing(Freezing parent, String name) {
			super(parent, name);
		}
		@Override
		protected AbstractReactivePreferences childSpi(String name) {
			return new Freezing(this, name);
		}
		private static class FreezeKey {
			final boolean user;
			final String path;
			final String key;
			FreezeKey(boolean user, String path, String key) {
				this.user = user;
				this.path = path;
				this.key = key;
			}
			@Override
			public boolean equals(Object obj) {
				if (!(obj instanceof FreezeKey))
					return false;
				FreezeKey other = (FreezeKey)obj;
				return user == other.user && path.equals(other.path) && key.equals(other.key);
			}
			@Override
			public int hashCode() {
				return Objects.hash(user, path, key);
			}
		}
		@Override
		protected String getSpi(String key) {
			return CurrentReactiveScope.freeze(new FreezeKey(isUserNode(), absolutePath(), key), () -> super.getSpi(key));
		}
	}
	public static ReactivePreferences freezing(ReactivePreferences prefs) {
		return new Freezing(prefs);
	}
}
