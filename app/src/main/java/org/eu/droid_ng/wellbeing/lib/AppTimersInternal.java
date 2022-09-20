package org.eu.droid_ng.wellbeing.lib;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.eu.droid_ng.wellbeing.AppTimersBroadcastReciever;
import org.eu.droid_ng.wellbeing.R;
import org.eu.droid_ng.wellbeing.shim.PackageManagerDelegate;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

//TODO: make package-private
/**
 * Load AppTimers state from disk and hold it in a instance. Allow easy configuration with API calls and make this handle all the specifics.
 * Architecture notes:
 *
 * App timers must not depend on our GlobalWellbeingState service infrastructure.
 * Android calls an Intent which we can use for a quick BroadcastReciever to
 * suspend the app. However, we have an limit of 1000 observers and we must provide the id mapping for them.
 * Use SharedPreferences to store known observers (uoid) and their ids (oid). When readding observer, substract timeUsed.
 *
 */
public class AppTimersInternal {
	@SuppressLint("StaticFieldLeak")
	private static AppTimersInternal instance;

	private final Context ctx;
	private final PackageManager pm;
	private final PackageManagerDelegate pmd;
	public final UsageStatsManager usm;
	private final SharedPreferences prefs; // uoid <-> oid map
	private final SharedPreferences config;

	private AppTimersInternal(Context ctx) {
		this.ctx = ctx;
		this.pm = ctx.getPackageManager();
		this.pmd = new PackageManagerDelegate(pm);
		this.usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
		this.prefs = ctx.getSharedPreferences("AppTimersInternal", 0);
		this.config = ctx.getSharedPreferences("appTimers", 0);
	}

	public static AppTimersInternal get(Context ctx) {
		if (instance == null) {
			instance = new AppTimersInternal(ctx);
		}
		return instance;
	}

	// start time limit core
	private void updatePrefs(String key, int value) {
		if (value < 0) {
			prefs.edit().remove(key).apply();
		} else {
			prefs.edit().putInt(key, value).apply();
		}
	}

	private int makeOid() {
		Collection<?> vals = prefs.getAll().values();
		// try to save time by starting at size value
		for (int i = vals.size(); i < 1000; i++) {
			if (!vals.contains(i))
				return i;
		}
		// if all high values are used up, try all values
		for (int i = 0; i < 1000; i++) {
			if (!vals.contains(i))
				return i;
		}
		// cant handle this
		throw new IllegalStateException("more than 1000 observers registered");
	}

	private static final class ParsedUoid {
		public final String action;
		public final long timeMillis;
		public final String[] pkgs;

		public ParsedUoid(String action, long timeMillis, String[] pkgs) {
			this.action = action;
			this.timeMillis = timeMillis;
			this.pkgs = pkgs;
		}

		@NonNull
		@Override
		public String toString() {
			return action + ":" + timeMillis + "//" + String.join(":", pkgs);
		}

		public static ParsedUoid from(String uoid) {
			int l = uoid.indexOf(":");
			int ll = uoid.indexOf("//");
			String action = uoid.substring(0, l);
			long timeMillis = Long.parseLong(uoid.substring(l + 1, ll));
			String[] pkgs = uoid.substring(ll + 2).split(":");
			return new ParsedUoid(action, timeMillis, pkgs);
		}
	}

	private void setUnhintedAppTimerInternal(Integer oid, String uoid, String[] toObserve, Duration timeLimit) {
		Intent i = new Intent(ctx, AppTimersBroadcastReciever.class);
		i.putExtra("observerId", oid);
		i.putExtra("uniqueObserverId", uoid);
		PendingIntent pintent = PendingIntent.getBroadcast(ctx, oid, i, PendingIntent.FLAG_IMMUTABLE);
		PackageManagerDelegate.registerAppUsageObserver(usm, oid, toObserve, timeLimit.toMillis(), TimeUnit.MILLISECONDS, pintent);
	}

	private void setHintedAppTimerInternal(Integer oid, String uoid, String[] toObserve, Duration timeLimit, Duration timeUsed) {
		Intent i = new Intent(ctx, AppTimersBroadcastReciever.class);
		i.putExtra("observerId", oid);
		i.putExtra("uniqueObserverId", uoid);
		PendingIntent pintent = PendingIntent.getBroadcast(ctx, oid, i, PendingIntent.FLAG_IMMUTABLE);
		PackageManagerDelegate.registerAppUsageLimitObserver(usm, oid, toObserve, timeLimit, timeUsed, pintent);
	}

	private void setAppTimerInternal(String uoid, String[] toObserve, Duration timeLimit, @Nullable Duration timeUsed) {
		int oid = prefs.getInt(uoid, -1);
		if (timeUsed == null) {
			setUnhintedAppTimerInternal(oid, uoid, toObserve, timeLimit);
		} else {
			setHintedAppTimerInternal(oid, uoid, toObserve, timeLimit, timeUsed);
		}
	}
	// end time limit core

	// start AppTimer feature
	private void setAppTimer(String[] toObserve, Duration timeLimit, @Nullable Duration timeUsed) {
		// AppLimit: do not provide info to launcher, use registerAppUsageObserver
		// AppTimer: provide info to launcher, use registerAppUsageLimitObserver
		String uoid = new ParsedUoid(timeUsed == null ? "AppLimit" : "AppTimer", timeLimit.toMillis(), toObserve).toString();
		Duration timeLimitInternal = timeLimit;
		if (timeUsed != null) {
			timeLimitInternal = timeLimitInternal.minus(timeUsed);
		}
		if (!prefs.contains(uoid)) {
			updatePrefs(uoid, makeOid());
		}
		setAppTimerInternal(uoid, toObserve, timeLimitInternal, timeUsed);
	}

	private void dropAppTimer(ParsedUoid parsedUoid) {
		String uoid = parsedUoid.toString();
		updatePrefs(uoid, -1); //delete pref
		if (!parsedUoid.action.equals("AppLimit")) {
			PackageManagerDelegate.unregisterAppUsageLimitObserver(usm, prefs.getInt(uoid, -1));
		} else {
			PackageManagerDelegate.unregisterAppUsageObserver(usm, prefs.getInt(uoid, -1));
		}
	}

	private void resetupAppTimerPreference(String packageName) {
		String[] s = new String[]{ packageName };
		int i = config.getInt(packageName, -1);
		Duration m = Duration.ofMinutes(i).minus(Utils.getTimeUsed(usm, s));
		if (i <= 0)
			return;
		if (m.isNegative() || m.isZero())
			onAppTimeout(new String[]{packageName}); //time already over on boot/updating pref, make sure app sleeps
		else
			setAppTimer(s, m, Utils.getTimeUsed(usm, s));
	}

	private void onAppTimeout(String[] packageNames) {
		int dialogBreakTime = 1; //TODO
		for (String packageName : packageNames) {
			String[] failed = new String[0];
			try {
				failed = pmd.setPackagesSuspended(new String[] { packageName }, true, null, null, new PackageManagerDelegate.SuspendDialogInfo.Builder()
						.setTitle(R.string.app_timers)
						.setMessage(ctx.getString(R.string.app_timer_exceed_f, pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0))))
						.setNeutralButtonText(dialogBreakTime == -1 ? R.string.dialog_btn_settings : ctx.getResources().getIdentifier("break_dialog_" + dialogBreakTime, "string", ctx.getPackageName()))
						.setNeutralButtonAction(dialogBreakTime == -1 ? PackageManagerDelegate.SuspendDialogInfo.BUTTON_ACTION_MORE_DETAILS : PackageManagerDelegate.SuspendDialogInfo.BUTTON_ACTION_UNSUSPEND)
						.setIcon(R.drawable.ic_focus_mode).build());
			} catch (PackageManager.NameNotFoundException e) {
				e.printStackTrace();
			}
			for (String f : failed) {
				Log.e("OpenWellbeing", "failed to suspend " + f);
			}
		}
	}

	private void endBreak(String[] packageNames) {
		ParsedUoid u = new ParsedUoid("AppBreak", 0, packageNames);
		if (!prefs.contains(u.toString()))
			return;
		Log.i("AppTimersInternal", "end break for " + Arrays.toString(packageNames));
		onAppTimeout(packageNames);
	}

	//public api:

	public void onUpdateAppTimerPreference(String packageName, Duration oldLimit, Duration limit) {
		String[] s = new String[]{ packageName };
		ParsedUoid u = new ParsedUoid("AppTimer", oldLimit.toMillis(), s);
		if (prefs.contains(u.toString()))
			dropAppTimer(u);
		u = new ParsedUoid("AppLimit", oldLimit.toMillis(), s);
		if (prefs.contains(u.toString()))
			dropAppTimer(u);
		u = new ParsedUoid("AppBreak", 0, s);
		if (prefs.contains(u.toString()))
			dropAppTimer(u);
		// unsuspend app if needed
		WellbeingStateClient client = new WellbeingStateClient(ctx);
		if (client.isServiceRunning())
			client.doBindService(state -> {
				GlobalWellbeingState.REASON r = state.reasonMap.getOrDefault(packageName, GlobalWellbeingState.REASON.REASON_UNKNOWN);
				if (r == null || r == GlobalWellbeingState.REASON.REASON_UNKNOWN)
					return;
				state.appTimerBlacklist.add(packageName);
			}, true);
		pmd.setPackagesSuspended(new String[]{packageName}, false, null, null, null);
		//Utils.clearUsageStatsCache(usm, true); moved out for threading
		if (limit.minus(Utils.getTimeUsed(usm, s)).toMinutes() > 0) //inexact on purpose. min to avoid crash in android is 1min
			setAppTimer(s, limit, Utils.getTimeUsed(usm, s));
		else if (!limit.isZero())
			onAppTimeout(s);
	}

	public void onBootRecieved() {
		prefs.edit().clear().apply();
		for (String pkgName : config.getAll().keySet()) {
			resetupAppTimerPreference(pkgName);
		}
	}

	//return false -> normal execution of service handlers
	//start break for app
	public boolean appTimerSuspendHook(String packageName) {
		int dialogBreakTime = 1; //TODO
		String u = new ParsedUoid("AppBreak", 0, new String[]{packageName}).toString();
		if (!(Utils.getTimeUsed(usm, new String[]{packageName}).toMinutes() >= config.getInt(packageName, Integer.MAX_VALUE) - 1 && !isAppOnBreak(packageName)))
			return false;
		Duration breakDuration = Duration.ofMinutes(dialogBreakTime);
		pmd.setPackagesSuspended(new String[]{packageName}, false, null, null, null);
		if (!prefs.contains(u)) {
			updatePrefs(u, makeOid());
		}
		setAppTimerInternal(u, new String[]{packageName}, breakDuration, Duration.ZERO);
		return true;
	}

	public boolean isAppOnBreak(String packageName) {
		String u = new ParsedUoid("AppBreak", 0, new String[]{packageName}).toString();
		return prefs.contains(u);
	}

	public void onBroadcastRecieve(Integer oid, String uoid) {
		String msg;
		if (!Objects.equals(prefs.getInt(uoid, -2), oid)) {
			msg = "Warning: unknown oid/uoid - " + oid + " / " + uoid + " - this might be an bug? Trying to recover.";
			//Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show(); this should really be shown. but the underlying problem lies in android code :(
			Log.e("AppTimersInternal", msg);
			// Attempt to recover, in doubt always trust the oid. Because android is fucking dumb. Thank you.
			uoid = prefs.getAll().entrySet().stream().filter(a -> oid.equals(a.getValue())).findAny().get().getKey();
		}
		ParsedUoid parsed = ParsedUoid.from(uoid);
		msg = "AppTimersInternal: success oid:" + oid + " action:" + parsed.action + " timeMillis:" + parsed.timeMillis + " pkgs:" + String.join(",", parsed.pkgs);
		Log.i("AppTimersInternal", msg);
		// Actual logic starting here please
		if (parsed.action.equals("AppTimer") || parsed.action.equals("AppLimit"))
			onAppTimeout(parsed.pkgs);
		else if (parsed.action.equals("AppBreak"))
			endBreak(parsed.pkgs);
		else
			Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();

		dropAppTimer(parsed);
	}
	// end AppTimer feature
}