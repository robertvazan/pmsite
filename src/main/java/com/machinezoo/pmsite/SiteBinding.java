// Part of PMSite: https://pmsite.machinezoo.com
package com.machinezoo.pmsite;

import java.util.function.*;
import com.machinezoo.pushmode.dom.*;
import com.machinezoo.stagean.*;

/*
 * SiteTemplate supports bindings that expand special x-* XML elements in template XML.
 * SiteBinding defines properties and behavior of these custom elements.
 */
/**
 * Defines a piece of dynamic content that can be embedded in {@link SiteTemplate}.
 */
@StubDocs
@DraftApi("improve convenience, predefined SiteFragment, exception handling for inline bindings")
public abstract class SiteBinding {
	/*
	 * Default binding name simplifies configuration and standardizes content of XML templates.
	 */
	public abstract String name();
	/*
	 * Bindings override this method to provide their generated content.
	 */
	public abstract DomContent expand(SiteBindingContext context);
	/*
	 * Having rename method here is arguably better
	 * than providing separate overload for SiteTemplate.bind() with changed binding name.
	 */
	public SiteBinding rename(String name) {
		SiteBinding original = this;
		return new SiteBinding() {
			@Override
			public String name() {
				return name;
			}
			@Override
			public DomContent expand(SiteBindingContext context) {
				return original.expand(context);
			}
		};
	}
	/*
	 * Simple implementations of bindings are below.
	 * Block variants provide automatic exception handling. Inline variants do not.
	 * Supplier variants are for content that doesn't need information from the template.
	 * Function variants can access many of the features of SiteBinding.
	 * None of these implementations consume any attributes. That requires derived class.
	 */
	public static SiteBinding inline(String name, Supplier<? extends DomContent> supplier) {
		return new SiteBinding() {
			@Override
			public String name() {
				return name;
			}
			@Override
			public DomContent expand(SiteBindingContext context) {
				return supplier.get();
			}
		};
	}
	public static SiteBinding inline(String name, Function<SiteBindingContext, ? extends DomContent> function) {
		return new SiteBinding() {
			@Override
			public String name() {
				return name;
			}
			@Override
			public DomContent expand(SiteBindingContext context) {
				return function.apply(context);
			}
		};
	}
	public static SiteBinding block(String name, Supplier<? extends DomContent> supplier) {
		return new SiteBinding() {
			@Override
			public String name() {
				return name;
			}
			@Override
			public DomContent expand(SiteBindingContext context) {
				try {
					return supplier.get();
				} catch (Throwable ex) {
					/*
					 * Consume all attributes in case we are returning error.
					 * We don't want any extra CSS classes on the error element.
					 */
					context.consumeAll();
					return context.page().handle(ex);
				}
			}
		};
	}
	public static SiteBinding block(String name, Function<SiteBindingContext, ? extends DomContent> function) {
		return new SiteBinding() {
			@Override
			public String name() {
				return name;
			}
			@Override
			public DomContent expand(SiteBindingContext context) {
				try {
					return function.apply(context);
				} catch (Throwable ex) {
					context.consumeAll();
					return context.page().handle(ex);
				}
			}
		};
	}
}
