package com.fakeethernet;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Module settings activity.
 * Uses SharedPreferences (MODE_PRIVATE) for persistence — compatible with Android 16+.
 */
public class SettingsActivity extends Activity {

    private static final String PREFS_NAME = "fakeethernet_prefs";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_FAKE_BANDWIDTH = "fake_bandwidth";

    private SharedPreferences prefs;
    private Switch enabledSwitch;
    private RadioGroup bandwidthGroup;

    private static final String[] BANDWIDTH_LABELS = {
            "50 Mbps",
            "100 Mbps",
            "500 Mbps",
            "1 Gbps"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));

        // Title
        TextView title = new TextView(this);
        title.setText("FakeEthernet");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(16));
        root.addView(title);

        // Description
        TextView desc = new TextView(this);
        desc.setText("将 WiFi 网络伪装为有线（Ethernet）接入");
        desc.setTextSize(14);
        desc.setGravity(Gravity.CENTER);
        desc.setPadding(0, 0, 0, dp(24));
        root.addView(desc);

        // Enable switch
        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        switchRow.setPadding(0, 0, 0, dp(8));

        TextView switchLabel = new TextView(this);
        switchLabel.setText("启用模块");
        switchLabel.setTextSize(16);
        switchRow.addView(switchLabel);

        enabledSwitch = new Switch(this);
        enabledSwitch.setChecked(prefs.getBoolean(KEY_ENABLED, true));
        enabledSwitch.setPadding(dp(16), 0, 0, 0);
        switchRow.addView(enabledSwitch);
        root.addView(switchRow);

        // Divider
        TextView divider = new TextView(this);
        divider.setText("─────────────────────");
        divider.setGravity(Gravity.CENTER);
        divider.setPadding(0, dp(16), 0, dp(8));
        root.addView(divider);

        // Bandwidth label
        TextView bwLabel = new TextView(this);
        bwLabel.setText("伪装带宽");
        bwLabel.setTextSize(16);
        bwLabel.setPadding(0, dp(8), 0, dp(8));
        root.addView(bwLabel);

        // Bandwidth radio group
        bandwidthGroup = new RadioGroup(this);
        int currentBw = prefs.getInt(KEY_FAKE_BANDWIDTH, 1);
        for (int i = 0; i < BANDWIDTH_LABELS.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(BANDWIDTH_LABELS[i]);
            rb.setId(i);
            if (i == currentBw) {
                rb.setChecked(true);
            }
            rb.setPadding(0, dp(4), 0, dp(4));
            bandwidthGroup.addView(rb);
        }
        root.addView(bandwidthGroup);

        // Info
        TextView info = new TextView(this);
        info.setText("\n提示：修改设置后需重启目标应用生效");
        info.setTextSize(13);
        info.setPadding(0, dp(16), 0, 0);
        root.addView(info);

        // Note about LSPosed
        TextView note = new TextView(this);
        note.setText("请在 LSPosed 管理器中选择模块作用域");
        note.setTextSize(13);
        note.setPadding(0, dp(4), 0, 0);
        root.addView(note);

        scrollView.addView(root);
        setContentView(scrollView);

        // Save on change
        enabledSwitch.setOnCheckedChangeListener((view, checked) -> saveSettings());
        bandwidthGroup.setOnCheckedChangeListener((group, checkedId) -> saveSettings());
    }

    private void saveSettings() {
        prefs.edit()
                .putBoolean(KEY_ENABLED, enabledSwitch.isChecked())
                .putInt(KEY_FAKE_BANDWIDTH, bandwidthGroup.getCheckedRadioButtonId())
                .apply();
        Toast.makeText(this, "设置已保存，重启目标应用生效", Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
