// Part of PMSite: https://pushmode.machinezoo.com
package com.machinezoo.pmsite.preferences;

import java.util.*;
import com.machinezoo.hookless.*;

public class TransientPreferences extends PreferenceStorage {
	private final Map<String, String> map = new ReactiveCollectionBuilder()
		.compareValues(true)
		.ignoreWriteStatus(true)
		.ignoreWriteExceptions(true)
		.wrapMap(new HashMap<>());
	@Override public synchronized String get(String key) {
		if (key == null)
			return null;
		return map.get(key);
	}
	@Override public synchronized void set(String key, String value) {
		if (key != null) {
			if (value != null)
				map.put(key, value);
			else
				map.remove(key);
		}
	}
}
