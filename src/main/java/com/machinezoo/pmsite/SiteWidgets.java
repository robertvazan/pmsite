// Part of PMSite: https://pmsite.machinezoo.com
package com.machinezoo.pmsite;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;
import com.machinezoo.noexception.*;
import com.machinezoo.pushmode.dom.*;
import com.machinezoo.stagean.*;

/**
 * Helper methods that register {@link SiteWidget} methods in {@link SiteTemplate}.
 */
@StubDocs
@DraftApi
public class SiteWidgets {
	private static class WidgetMethod {
		final String name;
		final Method method;
		final boolean isStatic;
		volatile boolean accessible;
		final List<Supplier<Object>> suppliers = new ArrayList<>();
		final Consumer<Object> consumer;
		WidgetMethod(Method method, SiteWidget annotation) {
			name = !annotation.name().isBlank() ? annotation.name() : !annotation.value().isBlank() ? annotation.value() : method.getName();
			this.method = method;
			isStatic = Modifier.isStatic(method.getModifiers());
			if (isStatic) {
				if (!method.canAccess(null))
					method.setAccessible(true);
				accessible = true;
			}
			for (var parameter : method.getParameters()) {
				/*
				 * List of supported parameter types could be expanded in the future.
				 * 
				 * We do not throw an exception here if the attribute is not present on the element,
				 * because some parameters may be optional and the method can easily check for null itself.
				 */
				if (parameter.getType() == String.class) {
					/*
					 * Parameter names will only work if -parameters is passed to Java compiler.
					 * This is okay as annotations are used to define widgets mostly in app code, which can adjust compiler settings.
					 */
					var pname = parameter.getName();
					suppliers.add(() -> SiteTemplate.consume(pname));
				} else if (parameter.getType() == DomElement.class)
					suppliers.add(() -> SiteTemplate.element());
				else if (DomContent.class.isAssignableFrom(parameter.getType())) {
					suppliers.add(() -> {
						var children = SiteTemplate.element().children();
						return children.isEmpty() ? null : new DomFragment().add(children);
					});
				} else
					throw new IllegalArgumentException("Unsupported parameter type: " + parameter.getType());
			}
			if (DomContent.class.isAssignableFrom(method.getReturnType()))
				consumer = r -> SiteFragment.get().add((DomContent)r);
			else if (method.getReturnType() == void.class) {
				consumer = r -> {
				};
			} else
				throw new IllegalArgumentException("Unsupported return type: " + method.getReturnType());
		}
		Runnable runnable(Object object) {
			if (!isStatic && !accessible && !method.canAccess(object)) {
				method.setAccessible(true);
				accessible = true;
			}
			return Exceptions.wrap().runnable(() -> consumer.accept(method.invoke(object, suppliers.stream().map(p -> p.get()).toArray(Object[]::new))));
		}
	}
	private static List<WidgetMethod> compile(Class<?> clazz) {
		if (clazz.getSuperclass() == null)
			return new ArrayList<>();
		var widgets = compile(clazz.getSuperclass());
		for (var method : clazz.getDeclaredMethods()) {
			var annotation = method.getAnnotation(SiteWidget.class);
			if (annotation != null)
				widgets.add(new WidgetMethod(method, annotation));
		}
		return widgets;
	}
	private static Map<Class<?>, List<WidgetMethod>> cache = new HashMap<>();
	private static synchronized List<WidgetMethod> widgets(Class<?> clazz) {
		return cache.computeIfAbsent(clazz, SiteWidgets::compile);
	}
	public static void register(Class<?> clazz, SiteTemplate template) {
		for (var widget : widgets(clazz))
			if (widget.isStatic)
				template.register(widget.name, widget.runnable(null));
	}
	public static void register(Object object, SiteTemplate template) {
		register(object.getClass(), template);
		for (var widget : widgets(object.getClass()))
			if (!widget.isStatic)
				template.register(widget.name, widget.runnable(object));
	}
}
