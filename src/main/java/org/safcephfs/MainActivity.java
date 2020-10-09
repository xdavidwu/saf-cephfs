package org.safcephfs;

import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.net.Uri;
import android.preference.EditTextPreference;
import android.preference.PreferenceActivity;
import android.provider.DocumentsContract;
import android.os.Bundle;
import android.util.Log;

import java.util.Arrays;

public class MainActivity extends PreferenceActivity
	implements OnSharedPreferenceChangeListener {
	private EditTextPreference monText, pathText, idText, keyText;

	private void notifyRootChanges(){
		Uri uri = DocumentsContract.buildRootsUri("org.safcephfs");
		getContentResolver().notifyChange(uri, null);
	}
	
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		addPreferencesFromResource(R.xml.main_pre);

		monText = (EditTextPreference) findPreference("mon");
		pathText = (EditTextPreference) findPreference("path");
		idText = (EditTextPreference) findPreference("id");
		keyText = (EditTextPreference) findPreference("key");

		SharedPreferences settings=getPreferenceScreen().getSharedPreferences();
		settings.registerOnSharedPreferenceChangeListener(this);
		if (!settings.getString("mon", "").equals(""))
			monText.setSummary(settings.getString("mon", ""));
		if (!settings.getString("path", "").equals(""))
			pathText.setSummary(settings.getString("path", ""));
		if (!settings.getString("id", "").equals(""))
			idText.setSummary(settings.getString("id", ""));
		if (!settings.getString("key", "").equals(""))
			keyText.setSummary(getString(R.string.key_filled));
	}

	@Override
	public void onSharedPreferenceChanged(SharedPreferences settings,
			String key) {
		notifyRootChanges();
		switch (key) {
		case "mon":
			if (settings.getString("mon", "").equals(""))
				monText.setSummary(getString(R.string.mon_summary));
			else
				monText.setSummary(settings.getString("mon", ""));
			break;
		case "path":
			if (settings.getString("path", "").equals(""))
				pathText.setSummary(getString(R.string.path_summary));
			else
				pathText.setSummary(settings.getString("path", ""));
			break;
		case "id":
			if (settings.getString("id", "").equals(""))
				idText.setSummary(getString(R.string.id_summary));
			else
				idText.setSummary(settings.getString("id", ""));
			break;
		case "key":
			if (settings.getString("key", "").equals(""))
				keyText.setSummary(getString(R.string.key_summary));
			else
				keyText.setSummary(getString(R.string.key_filled));
			break;
		}
	}
}
