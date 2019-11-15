// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite;

import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.regex.*;
import com.machinezoo.noexception.*;
import com.machinezoo.pushmode.dom.*;

/*
 * Helper for building interactive pages where widgets are simply laid out sequentially in HTML.
 */
public class SiteDialog implements AutoCloseable {
	/*
	 * Input widgets expose two kinds of values: rendered HTML and the input data.
	 * In order to support short one-liners for requesting input from user,
	 * the rendered HTML will be written into current thread-local instance of this class,
	 * so that the one-liner can use its return value for input data obtained from the user.
	 */
	private static final ThreadLocal<SiteDialog> current = new ThreadLocal<>();
	public static SiteDialog current() {
		/*
		 * Throw instead of silently returning null. Makes debugging easier.
		 */
		if (current.get() == null)
			throw new IllegalStateException("There is no active SiteDialog.");
		return current.get();
	}
	/*
	 * The usually way to use this class is to let it create DomFragment where all the widgets are placed.
	 * We will however allow calling code to supply its own output fragment or element.
	 */
	private final DomContainer container;
	/*
	 * We will however expose the stored DomContainter only as DomContent,
	 * because the getter is usually used only when implicit DomFragment is created by this class
	 * and the caller is not expected to add anything else to it.
	 * If it needs to be extended, caller should either add it to a new DomFragment
	 * or supply its own container via constructor.
	 * 
	 * This is an instance method, because it is usually called on the try-with-resources value.
	 */
	public DomContent content() {
		return container;
	}
	/*
	 * Widgets nearly always need a SiteSlot to generate IDs and store data.
	 * We thus make it a required parameter.
	 */
	private final SiteSlot slot;
	/*
	 * Static method, because it is used by widgets that do not have access to the try-with-resources variable.
	 */
	public static SiteSlot slot() {
		return current().slot;
	}
	/*
	 * Convenience method. Widgets usually need nested SiteSlot, not the dialog-wide one.
	 * 
	 * We will allow widgets to use titles (with arbitrary characters) as slot names by sanitizing the names.
	 * This functionality should really be (in much richer form) in SiteSlot itself.
	 */
	private static final Pattern slotRe = Pattern.compile("[-a-zA-Z0-9_.]+");
	public static SiteSlot slot(String name) {
		if (!slotRe.matcher(name).matches()) {
			byte[] hash = Exceptions.sneak().get(() -> MessageDigest.getInstance("SHA-256"))
				.digest(name.getBytes(StandardCharsets.UTF_8));
			name = Base64.getUrlEncoder().encodeToString(hash).replace("_", "").replace("-", "").replace("=", "");
		}
		return slot().nested(name);
	}
	/*
	 * Nesting of SiteDialog scopes is allowed. Widget HTML is always written to the innermost SiteDialog.
	 * This is important, because helper methods will likely create temporary helper "dialogs"
	 * to capture HTML from widgets that needs to be post-processed in some way.
	 */
	private SiteDialog outer;
	/*
	 * The thread-local variable points to current instance since calling the constructor till close() is called.
	 */
	public SiteDialog(SiteSlot slot, DomContainer container) {
		this.slot = slot;
		this.container = container;
		outer = current.get();
		current.set(this);
	}
	public SiteDialog(SiteSlot slot) {
		this(slot, new DomFragment());
	}
	/*
	 * Tolerate double call to close().
	 */
	private boolean closed;
	@Override public void close() {
		if (!closed) {
			closed = true;
			current.set(outer);
			/*
			 * Drop reference to the outer SiteDialog just in case this instance is kept alive by something.
			 */
			outer = null;
		}
	}
	/*
	 * Widgets write HTML into the exposed DomContainer.
	 * This gives them access to full functionality of DomContainer including fluent method chaining.
	 * This is a static method, because widgets do not have access to the try-with-resources variable.
	 * 
	 * This method returns the same value as content() above,
	 * but it has different purpose and it is static to fit this purpose.
	 */
	public static DomContainer out() {
		return current().container;
	}
	/*
	 * Since a lot of SiteDialog-based code will be called from within XML templates,
	 * we will provide convenient binding that uses the same name that is used for dialog's SiteSlot
	 * and that takes page reference (to obtain the the SiteSlot) from binding context.
	 */
	public static SiteBinding binding(String name, Runnable code) {
		return new SiteBinding() {
			@Override public String name() {
				return name;
			}
			@Override public DomContent expand(SiteBindingContext context) {
				try (SiteDialog dialog = new SiteDialog(context.page().slot(name))) {
					try {
						code.run();
					} catch (Throwable ex) {
						SiteDialog.out().add(context.page().handle(ex));
					}
					return dialog.content();
				}
			}
		};
	}
}
