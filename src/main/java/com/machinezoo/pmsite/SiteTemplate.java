// Part of PMSite: https://pmsite.machinezoo.com
package com.machinezoo.pmsite;

import static java.util.stream.Collectors.*;
import java.util.*;
import java.util.function.*;
import com.machinezoo.pushmode.dom.*;
import com.machinezoo.stagean.*;

/*
 * Standard text-only template engines would not work with PushMode DOM tree that can contain event listeners.
 * We could force some template engine to somehow produce a DOM tree, but it's easier to roll our own.
 * XML happens to be very practical for defining text mixed with dynamic content.
 */
/**
 * Template expander.
 */
@StubDocs
@DraftApi("Drop XML file support. Encourage multi-line strings in code using Markdown or XML.")
public class SiteTemplate {
	private final DomContent template;
	public SiteTemplate(DomContent template) {
		Objects.requireNonNull(template);
		this.template = template;
	}
	/*
	 * Widgets are used to add dynamic content into templates.
	 * This is not as good as proper template engine, but it will work reasonably well.
	 */
	private final Map<String, Runnable> widgets = new HashMap<>();
	public SiteTemplate register(String name, Runnable runnable) {
		if (widgets.containsKey(name))
			throw new IllegalArgumentException("Duplicate element name: " + name);
		widgets.put(name, runnable);
		return this;
	}
	public SiteTemplate register(String name, Supplier<? extends DomContent> supplier) {
		return register(name, () -> SiteFragment.get().add(supplier.get()));
	}
	public SiteTemplate remove(String name) {
		widgets.remove(name);
		return this;
	}
	/*
	 * Widget element might carry attributes or content that are used to generate widget content.
	 * We need to expose this information.
	 */
	private static class Context {
		private final DomElement element;
		Context(DomElement element) {
			this.element = element;
		}
		/*
		 * Consumed attributes will not be automatically copied from source element to the generated element.
		 * This is useful for passing parameters directly to the widget
		 * while still allowing extra attributes (especially class) on the custom elements.
		 * 
		 * List of consumed attributes can be dynamic, i.e. it may depend on the code path taken in the widget.
		 * Widgets are expected to call consume() or to directly manipulate the set returned by consumed()
		 * in order to prevent consumed attributes from appearing in output HTML.
		 */
		private Set<String> consumed = new HashSet<>();
	}
	private static final ThreadLocal<Context> current = new ThreadLocal<>();
	private static Context current() {
		var context = current.get();
		/*
		 * Always just throw. It's always an error to query widget element outside of template expansion.
		 */
		if (context == null)
			throw new IllegalStateException("No widget is being expanded.");
		return context;
	}
	public static DomElement element() {
		return current().element;
	}
	public static String consume(String name) {
		var context = current();
		var value = context.element.attributeAsString(name);
		if (value != null)
			context.consumed.add(name);
		return value;
	}
	/*
	 * The above-defined bindings are expanded by the template compiler below.
	 * This is done recursively, so widgets can nest and expand into more custom elements.
	 */
	private DomContent expand(DomContent source) {
		/*
		 * Leaves (DomText nodes) are not expanded at all.
		 */
		if (!(source instanceof DomContainer))
			return source;
		/*
		 * The only possible non-element container is DomFragment, but let's keep this general.
		 * DomFragment cannot be expanded, so we just compile its children.
		 */
		if (!(source instanceof DomElement))
			return new DomFragment().add(((DomContainer)source).children().stream().map(this::expand));
		var element = (DomElement)source;
		/*
		 * Custom element names must be prefixed with "x-", so that we can detect misconfigured widgets.
		 * XML namespaces are an alternative, but DomElement doesn't support them and they are more verbose.
		 * Their only advantage is a possible schema support that would ease XML editing,
		 * but most of the widgets in apps are going to be app-specific and thus wouldn't have the schema.
		 */
		if (element.tagname().startsWith("x-")) {
			var widget = widgets.get(element.tagname().substring(2));
			if (widget == null)
				throw new IllegalStateException("No such element: " + element.tagname());
			var id = element.id() != null ? element.id() : element.tagname().substring(2);
			var context = new Context(element);
			DomFragment expanded;
			var outer = current.get();
			try {
				current.set(context);
				expanded = SiteFragment.get()
					.nest(id)
					.run(widget)
					.content();
			} finally {
				current.set(outer);
			}
			/*
			 * We want to allow extra attributes on custom elements, especially class for styling.
			 * Here we add attributes declared on source element to the generated element.
			 */
			if (expanded.children().size() == 1) {
				var generated = expanded.elements().findFirst().orElse(null);
				if (generated != null) {
					var attributes = element.attributes().stream()
						.filter(a -> !context.consumed.contains(a.name()))
						.collect(toList());
					if (!attributes.isEmpty()) {
						/*
						 * We cannot edit the generated element directly, because it might be shared or frozen.
						 * We will perform a fast shallow copy, because we only need to change the top-level element.
						 */
						var decorated = new DomElement(generated.tagname())
							.key(generated.key())
							.id(generated.id())
							.set(generated.attributes())
							/*
							 * This is the only change we are making.
							 * Attributes explicitly set on the source element override generated attributes.
							 */
							.set(attributes)
							.add(generated.children());
						for (var listener : generated.listeners())
							decorated.subscribe(listener);
						expanded = new DomFragment().add(decorated);
					}
				}
			}
			/*
			 * Recursively compile the rendered content.
			 * Here we risk infinite recursion if custom elements expand into each other,
			 * but it's rare enough and inconsequential enough to not care.
			 */
			return expand(expanded);
		}
		/*
		 * Ordinary element stays as is, but its contents must be compiled.
		 */
		var compiled = new DomElement(element.tagname())
			.key(element.key())
			.id(element.id())
			.set(element.attributes());
		for (var listener : element.listeners())
			compiled.subscribe(listener);
		compiled.add(element.children().stream().map(this::expand));
		return compiled;
	}
	public void render() {
		SiteFragment.get().add(expand(template));
	}
}
