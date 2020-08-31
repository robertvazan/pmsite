// Part of PMSite: https://pmsite.machinezoo.com
package com.machinezoo.pmsite;

import static java.util.stream.Collectors.*;
import java.util.*;
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
@DraftApi("perhaps use XML namespaces instead of x- prefix")
public class SiteTemplate {
	private final DomContent template;
	public SiteTemplate(DomContent template) {
		Objects.requireNonNull(template);
		this.template = template;
	}
	/*
	 * Bindings are used to add dynamic content into templates.
	 * This is not as good as proper template engine, but it will work reasonably well.
	 */
	private final Map<String, SiteBinding> bindings = new HashMap<>();
	public SiteTemplate bind(SiteBinding binding) {
		bindings.put(binding.name(), binding);
		return this;
	}
	/*
	 * The above-defined bindings are expanded by the template compiler below.
	 * This is done recursively, so bindings can nest and expand into more custom elements.
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
		 * Custom element names must be prefixed with "x-", so that we can detect misconfigured bindings.
		 * XML namespaces might be better, but DomElement doesn't support them.
		 */
		if (element.tagname().startsWith("x-")) {
			var binding = bindings.get(element.tagname().substring(2));
			if (binding == null)
				throw new IllegalStateException("No such binding: " + element.tagname());
			SiteBindingContext context = new SiteBindingContext() {
				@Override
				public DomElement source() {
					return element;
				}
				@Override
				public SiteTemplate template() {
					return SiteTemplate.this;
				}
				@Override
				public SitePage page() {
					return SiteFragment.get().page();
				}
			};
			var expanded = binding.expand(context);
			if (expanded instanceof DomElement) {
				/*
				 * We want to allow extra attributes on custom elements, especially class for styling.
				 * Here we add attributes declared on source element to the generated element.
				 */
				var generated = (DomElement)expanded;
				var attributes = element.attributes().stream()
					.filter(a -> !context.consumed().contains(a.name()))
					.collect(toList());
				if (!attributes.isEmpty()) {
					/*
					 * We cannot edit the generated element directly, because it might be shared or frozen.
					 * We will perform a fast shallow copy, because we only need to change the top-level element.
					 */
					var annotated = new DomElement(generated.tagname())
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
						annotated.subscribe(listener);
					expanded = annotated;
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
		 * The element stays as is, but its contents must be compiled.
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
