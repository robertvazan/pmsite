// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite;

import static java.util.stream.Collectors.*;
import java.util.*;
import java.util.function.*;
import com.machinezoo.noexception.*;
import com.machinezoo.pushmode.dom.*;

/*
 * SiteTemplate supports bindings that expand special x-* XML elements in template XML.
 * SiteBinding defines properties and behavior of these custom elements.
 */
public abstract class SiteBinding {
	/*
	 * Default binding name simplifies configuration and standardizes content of XML templates.
	 */
	public abstract String name();
	/*
	 * Provide access to SiteTemplate, so that bindings can use page metadata in generated HTML.
	 */
	private SiteTemplate template;
	public SiteBinding template(SiteTemplate template) {
		this.template = template;
		return this;
	}
	public SiteTemplate template() {
		return template;
	}
	/*
	 * Provide access to the page, so that bindings can use metadata from the page or its SiteLocation.
	 */
	private SitePage page;
	public SiteBinding page(SitePage page) {
		this.page = page;
		return this;
	}
	public SitePage page() {
		return page;
	}
	/*
	 * Source element as it appears in template XML.
	 */
	private DomElement source;
	public SiteBinding source(DomElement source) {
		this.source = source;
		return this;
	}
	public DomElement source() {
		return source;
	}
	/*
	 * This is what SiteTemplate calls. Bindings generally shouldn't override it
	 * as it provides attribute annotation by default.
	 */
	public DomContent render() {
		DomContent rendered = expand();
		if (rendered instanceof DomElement)
			rendered = annotate((DomElement)rendered);
		return rendered;
	}
	/*
	 * This is what bindings should usually override to provide their generated content.
	 * This method is abstract to make it easier to implement the class.
	 * In the rare cases when bindings override render() directly, they can just return null here.
	 */
	public abstract DomContent expand();
	/*
	 * Here the binding can configure source element attributes it consumes.
	 * These attributes will not be appended to the output element below.
	 */
	public boolean consumes(String name) {
		return false;
	}
	/*
	 * We want to allow extra attributes on custom elements, especially class for styling.
	 * This method adds attributes declared on source element to the generated element.
	 */
	public DomElement annotate(DomElement target) {
		List<DomAttribute> attributes = source.attributes().stream()
			.filter(a -> !consumes(a.name()))
			.collect(toList());
		/*
		 * We cannot edit the rendered element directly, because it might be shared or frozen.
		 * Perform a fast shallow copy, because we only need to change the top-level element.
		 */
		DomElement annotated = new DomElement(target.tagname())
			.key(target.key())
			.id(target.id())
			.set(target.attributes())
			/*
			 * This is the only change we are making.
			 * Attributes explicitly set on the reference element override generated attributes.
			 */
			.set(attributes)
			.add(target.children());
		for (DomListener listener : target.listeners())
			annotated.subscribe(listener);
		return annotated;
	}
	/*
	 * Simple implementations of bindings are below.
	 * Block variants provide automatic exception handling. Inline variants do not.
	 * Supplier variants are for content that doesn't need information from the template.
	 * Function variants can access many of the features of SiteBinding.
	 */
	public static Supplier<SiteBinding> inline(String name, Supplier<? extends DomContent> supplier) {
		return () -> new SiteBinding() {
			@Override public String name() {
				return name;
			}
			@Override public DomContent expand() {
				return supplier.get();
			}
		};
	}
	public static Supplier<SiteBinding> inline(String name, Function<SiteBinding, ? extends DomContent> function) {
		return () -> new SiteBinding() {
			@Override public String name() {
				return name;
			}
			@Override public DomContent expand() {
				return function.apply(this);
			}
			@Override public boolean consumes(String name) {
				return true;
			}
		};
	}
	public static Supplier<SiteBinding> block(String name, Supplier<? extends DomContent> supplier) {
		return () -> new SiteBinding() {
			@Override public String name() {
				return name;
			}
			@Override public DomContent expand() {
				try {
					return supplier.get();
				} catch (Throwable ex) {
					Exceptions.log().handle(ex);
					return SitePage.formatError(ex);
				}
			}
		};
	}
	public static Supplier<SiteBinding> block(String name, Function<SiteBinding, ? extends DomContent> function) {
		return () -> new SiteBinding() {
			@Override public String name() {
				return name;
			}
			@Override public DomContent expand() {
				try {
					return function.apply(this);
				} catch (Throwable ex) {
					Exceptions.log().handle(ex);
					return SitePage.formatError(ex);
				}
			}
			@Override public boolean consumes(String name) {
				return true;
			}
		};
	}
}
