/**
 * Copyright 2013 Alex Yanchenko
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 */
package org.droidparts.bus;

import static java.lang.String.format;
import static org.droidparts.inner.ClassSpecRegistry.getReceiveEventsSpecs;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

import org.droidparts.inner.ann.MethodSpec;
import org.droidparts.inner.ann.bus.ReceiveEventsAnn;
import org.droidparts.util.L;

import android.os.Handler;
import android.os.Looper;

public class EventBus {

	private static final String ALL = "_all_";

	private static final ConcurrentHashMap<String, ConcurrentHashMap<EventReceiver<Object>, Boolean>> eventNameToReceivers = new ConcurrentHashMap<String, ConcurrentHashMap<EventReceiver<Object>, Boolean>>();
	private static final ConcurrentHashMap<String, Object[]> stickyEvents = new ConcurrentHashMap<String, Object[]>();

	public static void registerAnnotatedReceiver(Object obj) {
		MethodSpec<ReceiveEventsAnn>[] specs = getReceiveEventsSpecs(obj
				.getClass());
		for (MethodSpec<ReceiveEventsAnn> spec : specs) {
			ReflectiveReceiver receiver = new ReflectiveReceiver(obj,
					spec.method);
			registerReceiver(receiver, spec.ann.names);
		}
	}

	public static void unregisterAnnotatedReceiver(Object obj) {
		for (ConcurrentHashMap<EventReceiver<Object>, Boolean> receivers : eventNameToReceivers
				.values()) {
			for (EventReceiver<Object> receiver : receivers.keySet()) {
				if (receiver instanceof ReflectiveReceiver) {
					if (obj == ((ReflectiveReceiver) receiver).objectRef.get()) {
						receivers.remove(receiver);
					}
				}
			}
		}
	}

	public static void registerReceiver(EventReceiver<?> receiver,
			String... eventNames) {
		@SuppressWarnings("unchecked")
		EventReceiver<Object> rec = (EventReceiver<Object>) receiver;
		if (eventNames.length == 0) {
			for (String name : stickyEvents.keySet()) {
				notifyReceiver(rec, name, stickyEvents.get(name));
			}
			receiversForEventName(ALL).put(rec, Boolean.FALSE);
		} else {
			for (String name : eventNames) {
				Object[] data = stickyEvents.get(name);
				if (data != null) {
					notifyReceiver(rec, name, data);
				}
			}
			for (String action : eventNames) {
				receiversForEventName(action).put(rec, Boolean.FALSE);
			}
		}
	}

	public static void unregisterReceiver(EventReceiver<?> receiver) {
		receiversForEventName(ALL).remove(receiver);
		for (String eventName : eventNameToReceivers.keySet()) {
			ConcurrentHashMap<EventReceiver<Object>, Boolean> map = eventNameToReceivers
					.get(eventName);
			map.remove(receiver);
			if (map.isEmpty()) {
				eventNameToReceivers.remove(eventName);
			}

		}
	}

	public void clearStickyEvents(String... names) {
		if (names.length == 0) {
			stickyEvents.clear();
		} else {
			HashSet<String> nameSet = new HashSet<String>(Arrays.asList(names));
			for (String eventName : eventNameToReceivers.keySet()) {
				if (nameSet.contains(eventName)) {
					eventNameToReceivers.remove(eventName);
					break;
				}
			}
		}
	}

	public static <T> void postEventSticky(String name, Object... data) {
		stickyEvents.put(name, data);
		postEvent(name, data);
	}

	public static <T> void postEvent(final String name, final Object... data) {
		Runnable r = new Runnable() {

			@Override
			public void run() {
				HashSet<EventReceiver<Object>> receivers = new HashSet<EventReceiver<Object>>();
				receivers.addAll(receiversForEventName(ALL).keySet());
				receivers.addAll(receiversForEventName(name).keySet());
				for (EventReceiver<Object> rec : receivers) {
					notifyReceiver(rec, name, data);
				}
			}
		};
		runOnUiThread(r);
	}

	private static ConcurrentHashMap<EventReceiver<Object>, Boolean> receiversForEventName(
			String name) {
		ConcurrentHashMap<EventReceiver<Object>, Boolean> map = eventNameToReceivers
				.get(name);
		if (map == null) {
			map = new ConcurrentHashMap<EventReceiver<Object>, Boolean>();
			eventNameToReceivers.put(name, map);
		}
		return map;
	}

	private static void notifyReceiver(EventReceiver<Object> receiver,
			String event, Object[] data) {
		Object realData = data;
		if (data.length == 0) {
			realData = null;
		} else if (data.length == 1) {
			realData = data[0];
		}
		try {
			receiver.onEvent(event, realData);
		} catch (IllegalArgumentException e) {
			L.w(format("Failed to deliver event %s to %s: %s.", event, receiver
					.getClass().getName(), e.getMessage()));
		} catch (Exception e) {
			L.w(e);
			L.w("Receiver unregistered.");
			unregisterReceiver(receiver);
		}

	}

	private static void runOnUiThread(Runnable r) {
		if (handler == null) {
			handler = new Handler(Looper.getMainLooper());
		}
		handler.post(r);
	}

	private static Handler handler;

	private static class ReflectiveReceiver implements EventReceiver<Object> {

		final WeakReference<Object> objectRef;
		final Method method;

		ReflectiveReceiver(Object object, Method method) {
			objectRef = new WeakReference<Object>(object);
			this.method = method;
		}

		@Override
		public void onEvent(String name, Object data) {
			try {
				Object obj = objectRef.get();
				int argCount = method.getParameterTypes().length;
				if (argCount == 1) {
					method.invoke(obj, name);
				} else {
					method.invoke(obj, name, data);
				}
			} catch (IllegalArgumentException e) {
				throw e;
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}

	}

}