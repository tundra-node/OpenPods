package com.dosse.airpods.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.dosse.airpods.BuildConfig;
import com.dosse.airpods.R;
import com.dosse.airpods.pods.PodsService;
import com.dosse.airpods.receivers.StartupReceiver;
import com.dosse.airpods.utils.ThemeUtils;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate (Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings_container, new SettingsFragment())
                .commit();
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {

        @Override
        public void onCreatePreferences (Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preference_screen, rootKey);

            // Re-apply the theme immediately when the user changes it.
            getPreference("theme").setOnPreferenceChangeListener((preference, newValue) -> {
                ThemeUtils.applyThemeMode((String)newValue);
                return true;
            });

            // Noise control settings are forwarded to the running service,
            // which sends them over AACP to the AirPods.
            getPreference("conversationAwareness").setOnPreferenceChangeListener((preference, newValue) -> {
                startService(PodsService.ACTION_SET_CONVERSATION_AWARENESS)
                        .putExtra(PodsService.EXTRA_ENABLED, (Boolean)newValue);
                return true;
            });

            getPreference("adaptiveStrength").setOnPreferenceChangeListener((preference, newValue) -> {
                startService(PodsService.ACTION_SET_ADAPTIVE_STRENGTH)
                        .putExtra(PodsService.EXTRA_STRENGTH, (Integer)newValue);
                return true;
            });

            getPreference("rename").setOnPreferenceClickListener(preference -> {
                showRenameDialog();
                return true;
            });

            getPreference("batterySaver").setOnPreferenceClickListener(preference -> {
                StartupReceiver.restartPodsService(requireContext());
                return true;
            });

            getPreference("restartService").setOnPreferenceClickListener(preference -> {
                StartupReceiver.restartPodsService(requireContext());
                return true;
            });

            getPreference("about").setSummary(String.format("%s v%s", getString(R.string.app_name), BuildConfig.VERSION_NAME));

            getPreference("donate").setOnPreferenceClickListener(preference -> {
                Toast.makeText(requireContext(), "❤️", Toast.LENGTH_SHORT).show();
                return false;
            });
        }

        private Intent startService (String action) {
            Intent intent = new Intent(requireContext(), PodsService.class).setAction(action);
            requireContext().startService(intent);
            return intent;
        }

        private void showRenameDialog () {
            final EditText input = new EditText(requireContext());
            input.setHint(R.string.rename_desc);

            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.rename)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        String name = input.getText().toString().trim();
                        if (!name.isEmpty())
                            startService(PodsService.ACTION_RENAME).putExtra(PodsService.EXTRA_NAME, name);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }

        private Preference getPreference (String key) {
            return getPreferenceManager().findPreference(key);
        }

    }

}
