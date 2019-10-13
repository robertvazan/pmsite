// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite;

import static java.util.stream.Collectors.*;
import java.util.*;
import com.machinezoo.pushmode.dom.*;

/*
 * Custom element for use in SiteTemplate.
 */
public abstract class SiteElement {
	public abstract String name();
	public abstract DomContent render(DomElement reference, SiteTemplate template);
	/*
	 * Take DomContent instead of DomElement, so that we can spare callers of a type check.
	 * Returning DomContent doesn't hurt, because this is usually the last step before returning from render().
	 */
	public static DomContent annotate(DomContent rendered, DomElement reference, String... excluded) {
		if (!(rendered instanceof DomElement))
			return rendered;
		DomElement element = (DomElement)rendered;
		List<DomAttribute> attributes = reference.attributes().stream()
			.filter(a -> !Arrays.asList(excluded).contains(a.name()))
			.collect(toList());
		/*
		 * We cannot edit the rendered element directly, because it might be shared or frozen.
		 * Perform a fast shallow copy, because we only need to change the top-level element.
		 */
		DomElement annotated = new DomElement(element.tagname())
			.key(element.key())
			.id(element.id())
			.set(element.attributes())
			/*
			 * This is the only change we are making.
			 * Attributes explicitly set on the reference element override generated attributes.
			 */
			.set(attributes)
			.add(element.children());
		for (DomListener listener : element.listeners())
			annotated.subscribe(listener);
		return annotated;
	}
}
