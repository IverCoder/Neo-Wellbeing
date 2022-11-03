package org.eu.droid_ng.wellbeing.prefs;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.recyclerview.widget.RecyclerView;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.NumberPicker;
import android.widget.TextView;

import org.eu.droid_ng.wellbeing.R;
import org.eu.droid_ng.wellbeing.lib.Utils;
import org.eu.droid_ng.wellbeing.lib.WellbeingService;

import java.text.Collator;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class AppTimers extends AppCompatActivity {

	private WellbeingService ati;
	private Handler h;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		h = new Handler(getMainLooper());
		ati = WellbeingService.get();
		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.setDisplayHomeAsUpEnabled(true);
		}
		setContentView(R.layout.activity_app_timers);

		RecyclerView r = findViewById(R.id.appTimerPkgs);
		new Thread(() -> {
			AppTimersRecyclerViewAdapter a = new AppTimersRecyclerViewAdapter(this,
					getPackageManager().getInstalledApplications(PackageManager.GET_META_DATA));
			h.post(() -> {
				findViewById(R.id.appTimerLoading).setVisibility(View.GONE);
				r.setAdapter(a);
				r.setVisibility(View.VISIBLE);
			});
		}).start();
	}

	@Override
	public boolean onSupportNavigateUp() {
		finish();
		return true;
	}

	public class AppTimersRecyclerViewAdapter extends RecyclerView.Adapter<AppTimersRecyclerViewAdapter.AppTimerViewHolder> {
		private final LayoutInflater inflater;
		private final List<ApplicationInfo> mData;
		private final PackageManager pm;
		public final SharedPreferences prefs;
		public final Map<String, Integer> enabledMap = new HashMap<>();

		public AppTimersRecyclerViewAdapter(Context context, List<ApplicationInfo> mData) {
			this.inflater = LayoutInflater.from(context);
			this.pm = context.getPackageManager();
			prefs = context.getSharedPreferences("appTimers", 0);
			prefs.getAll().forEach((k, v) -> {
				if (!(v instanceof Integer)) {
					Log.e("OpenWellbeing", "Failed to parse " + k);
					return;
				}
				Integer v2 = (Integer) v;
				enabledMap.put(k, v2);
			});
			Collator collator = Collator.getInstance();
			// Sort alphabetically by display name
			Comparator<ApplicationInfo> nc = (a, b) -> {
				CharSequence displayA = pm.getApplicationLabel(a);
				CharSequence displayB = pm.getApplicationLabel(b);
				return collator.compare(displayA, displayB);
			};
			Intent mainIntent = new Intent(Intent.ACTION_MAIN, null)
					.addCategory(Intent.CATEGORY_LAUNCHER);

			List<String> hasLauncherIcon = pm.queryIntentActivities(mainIntent, 0).stream().map(a -> a.activityInfo.packageName).collect(Collectors.toList());
			final List<String> blacklist = List.of("com.android.settings", "com.android.dialer", "org.eu.droid_ng.wellbeing");
			this.mData = mData.stream().filter(i -> {
				// Filter out system apps without launcher icon and Settings, Dialer and Wellbeing
				boolean isUser = (i.flags & (ApplicationInfo.FLAG_UPDATED_SYSTEM_APP | ApplicationInfo.FLAG_SYSTEM)) < 1;
				return !blacklist.contains(i.packageName) && (isUser || hasLauncherIcon.contains(i.packageName));
			}).sorted((a, b) -> {
				// Enabled goes first
				boolean hasA = enabledMap.getOrDefault(a.packageName, 0) != 0;
				boolean hasB = enabledMap.getOrDefault(b.packageName, 0) != 0;
				if (hasA && hasB)
					return nc.compare(a, b);
				else if (hasA)
					return -1;
				else if (hasB)
					return 1;
				else
					return nc.compare(a, b);
			}).collect(Collectors.toList());
		}

		@NonNull
		@Override
		public AppTimerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			View view = inflater.inflate(R.layout.appitem, parent, false);
			return new AppTimerViewHolder(view);
		}

		@Override
		public int getItemCount() {
			return mData.size();
		}

		public ApplicationInfo getItem(int i) {
			return mData.get(i);
		}

		@Override
		public void onBindViewHolder(@NonNull AppTimerViewHolder holder, int position) {
			ApplicationInfo i = getItem(position);
			int mins = prefs.getInt(i.packageName, 0);
			holder.apply(i, mins);
		}

		public class AppTimerViewHolder extends RecyclerView.ViewHolder {
			private final ViewGroup container;
			private final ImageView appIcon;
			private final TextView appName;
			private final TextView appTimerInfo;
			private final ImageButton actionButton;

			public AppTimerViewHolder(@NonNull View itemView) {
				super(itemView);
				this.container = itemView.findViewById(R.id.container);
				this.appIcon = itemView.findViewById(R.id.appIcon);
				this.appName = itemView.findViewById(R.id.appName2);
				this.appTimerInfo = itemView.findViewById(R.id.pkgName);
				this.actionButton = new ImageButton(itemView.getContext());
				actionButton.setImageDrawable(AppCompatResources.getDrawable(itemView.getContext(), R.drawable.ic_focus_mode));
				actionButton.setBackground(null);
				CheckBox checkBox = itemView.findViewById(R.id.isChecked);
				ViewGroup parent = (ViewGroup) checkBox.getParent();
				int idx = parent.indexOfChild(checkBox);
				parent.removeView(checkBox);
				parent.addView(actionButton, idx);
			}

			public void apply(ApplicationInfo info, int mins) {
				appIcon.setImageDrawable(pm.getApplicationIcon(info));
				appName.setText(pm.getApplicationLabel(info));
				applyText(mins, Math.toIntExact(Objects.requireNonNull(Utils.getTimeUsed(ati.usm, new String[]{info.packageName})).toMinutes()));
				container.setOnClickListener(view -> {
					int realmins = enabledMap.getOrDefault(info.packageName, 0);
					NumberPicker numberPicker = new NumberPicker(AppTimers.this);
					numberPicker.setMinValue(0);
					numberPicker.setMaxValue(9999); //i mean why not
					numberPicker.setValue(realmins);
					new AlertDialog.Builder(AppTimers.this)
							.setTitle(pm.getApplicationLabel(info))
							.setView(numberPicker)
							.setNegativeButton(R.string.cancel, (d,i) -> d.dismiss())
							.setPositiveButton(R.string.ok, (d,i) -> {
								updateMins(info.packageName, realmins, numberPicker.getValue());
								d.dismiss();
							})
							.show();
				});
			}

			private void updateMins(String pkgName, int oldmins, int mins) {
				enabledMap.put(pkgName, mins);
				prefs.edit().putInt(pkgName, mins).apply();
				applyText(mins, Math.toIntExact(Objects.requireNonNull(Utils.getTimeUsed(ati.usm, new String[]{pkgName})).toMinutes()));
				new Thread(() -> {
					Utils.clearUsageStatsCache(ati.usm, true);
					h.post(() -> {
						applyText(mins, Math.toIntExact(Objects.requireNonNull(Utils.getTimeUsed(ati.usm, new String[]{pkgName})).toMinutes()));
						ati.onUpdateAppTimerPreference(pkgName, Duration.ofMinutes(oldmins));
					});
				}).start();
			}

			private void applyText(int mins, int mins2) {
				appTimerInfo.setText(itemView.getContext().getString(R.string.desc_container, (mins == 0 ? itemView.getContext().getString(R.string.no_timer) : itemView.getContext().getResources().getQuantityString(R.plurals.break_mins, mins, mins)), (itemView.getContext().getResources().getQuantityString(R.plurals.break_mins, mins2, mins2))));
			}
		}
	}

}