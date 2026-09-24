package com.android.server.droiduse;

import android.content.Context;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.UserManager;
import android.provider.Settings;
import java.util.List;

/** Device-global controls, reachable only through a session's explicit settings opt-in. */
final class DroidUseSettings {
    private final Context context;
    DroidUseSettings(Context context) { this.context = context; }
    private boolean restricted(String restriction) {
        UserManager users = context.getSystemService(UserManager.class);
        return users == null || users.hasUserRestriction(restriction);
    }
    void observe(DroidUseSystemActions actions) {
        volume(actions);
        brightness(actions);
        wifi(actions);
    }
    private void volume(DroidUseSystemActions actions) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null || audio.isVolumeFixed() || restricted(UserManager.DISALLOW_ADJUST_VOLUME)) return;
        int stream = AudioManager.STREAM_MUSIC;
        int current = audio.getStreamVolume(stream);
        int min = audio.getStreamMinVolume(stream), max = audio.getStreamMaxVolume(stream);
        actions.fact("整机媒体音量：" + current + "/" + max);
        for (int percent : new int[] {0, 25, 50, 75, 100}) {
            int value = min + Math.round((max - min) * percent / 100f);
            if (value == current) continue;
            actions.offer("set_volume", "整机设置：媒体音量改为 " + percent + "%", ignored -> {
                if (audio.getStreamVolume(stream) != current || audio.getStreamMaxVolume(stream) != max
                        || audio.isVolumeFixed() || restricted(UserManager.DISALLOW_ADJUST_VOLUME))
                    throw DroidUseSystemActions.stale();
                audio.setStreamVolume(stream, value, 0);
                if (audio.getStreamVolume(stream) != value) throw new IllegalStateException("VOLUME_NOT_CHANGED");
            });
        }
    }
    private int setting(String name, int fallback) {
        return Settings.System.getIntForUser(context.getContentResolver(), name, fallback, 0);
    }
    private void brightness(DroidUseSystemActions actions) {
        if (restricted(UserManager.DISALLOW_CONFIG_BRIGHTNESS)) return;
        String brightness = Settings.System.SCREEN_BRIGHTNESS;
        String modeKey = Settings.System.SCREEN_BRIGHTNESS_MODE;
        int current = setting(brightness, 128), mode = setting(modeKey, 0);
        actions.fact("整机亮度：" + current + "/255，自动亮度=" + (mode == 1));
        for (int percent : new int[] {10, 25, 50, 75, 100}) {
            int value = Math.round(255 * percent / 100f);
            if (value == current && mode == 0) continue;
            actions.offer("set_brightness", "整机设置：关闭自动亮度，屏幕亮度改为 " + percent + "%", ignored -> {
                if (setting(brightness, -1) != current || setting(modeKey, -1) != mode
                        || restricted(UserManager.DISALLOW_CONFIG_BRIGHTNESS)) throw DroidUseSystemActions.stale();
                if (!Settings.System.putIntForUser(context.getContentResolver(), modeKey, 0, 0))
                    throw new IllegalStateException("BRIGHTNESS_MODE_FAILED");
                if (!Settings.System.putIntForUser(context.getContentResolver(), brightness, value, 0)) {
                    Settings.System.putIntForUser(context.getContentResolver(), modeKey, mode, 0);
                    throw new IllegalStateException("BRIGHTNESS_NOT_CHANGED");
                }
            });
        }
    }
    private void wifi(DroidUseSystemActions actions) {
        WifiManager wifi = context.getSystemService(WifiManager.class);
        if (wifi == null || restricted(UserManager.DISALLOW_CONFIG_WIFI)
                || restricted(UserManager.DISALLOW_CHANGE_WIFI_STATE)) return;
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities network = connectivity == null ? null
                : connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
        int state = wifi.getWifiState();
        actions.fact("整机 Wi-Fi 状态=" + state + "；默认网络通过系统联网验证="
                + (network != null && network.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                + "；默认网络使用 Wi-Fi=" + (network != null && network.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)));
        android.net.wifi.WifiInfo connection = wifi.getConnectionInfo();
        if (connection != null) actions.fact("当前 Wi-Fi SSID=" + connection.getSSID()
                + "；networkId=" + connection.getNetworkId());
        if (state != WifiManager.WIFI_STATE_ENABLED && state != WifiManager.WIFI_STATE_DISABLED) return;
        boolean enabled = state == WifiManager.WIFI_STATE_ENABLED;
        actions.offer("set_wifi", "整机设置：" + (enabled ? "关闭 Wi-Fi（可能中断联网任务）" : "打开 Wi-Fi"), ignored -> {
            if (wifi.getWifiState() != state || restricted(UserManager.DISALLOW_CHANGE_WIFI_STATE)
                    || restricted(UserManager.DISALLOW_CONFIG_WIFI)) throw DroidUseSystemActions.stale();
            if (!wifi.setWifiEnabled(!enabled)) throw new IllegalStateException("WIFI_REQUEST_REJECTED");
        });
        if (!enabled) return;
        List<WifiConfiguration> saved = wifi.getConfiguredNetworks();
        if (saved == null) return;
        int count = 0;
        for (WifiConfiguration config : saved) {
            if (config.networkId < 0 || config.SSID == null || count++ >= 8) continue;
            int id = config.networkId;
            String ssid = config.SSID;
            // Never retain or serialize configuration objects, keys or credentials.
            actions.offer("connect_wifi", "整机设置：请求连接已保存的 Wi-Fi " + ssid + "；需再观察验证联网", ignored -> {
                if (!wifi.isWifiEnabled() || restricted(UserManager.DISALLOW_CONFIG_WIFI)
                        || restricted(UserManager.DISALLOW_CHANGE_WIFI_STATE)) throw DroidUseSystemActions.stale();
                List<WifiConfiguration> current = wifi.getConfiguredNetworks();
                if (current == null || current.stream().noneMatch(row -> row.networkId == id && ssid.equals(row.SSID)))
                    throw DroidUseSystemActions.stale();
                // Accepted is not connected; subsequent observation reports validated default network.
                wifi.connect(id, null);
            });
        }
    }
}
