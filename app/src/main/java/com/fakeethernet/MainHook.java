package com.fakeethernet;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * FakeEthernet - LSPosed Module
 * Makes WiFi appear as Ethernet to all apps.
 *
 * Hook strategy (3 layers):
 *   Layer 1: ConnectivityManager entry points → return faked objects
 *   Layer 2: NetworkInfo method-level hooks → safety net
 *   Layer 3: NetworkCapabilities.hasTransport → swap transport types
 *
 * Thread safety:
 *   - ThreadLocal recursion guard for hasTransport hook
 *   - XSharedPreferences is thread-safe by design
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "FakeEthernet";
    private static final String PREFS_NAME = "fakeethernet_prefs";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_FAKE_BANDWIDTH = "fake_bandwidth";

    private static final int TYPE_WIFI = ConnectivityManager.TYPE_WIFI;         // 1
    private static final int TYPE_ETHERNET = ConnectivityManager.TYPE_ETHERNET; // 9

    private static final int TRANSPORT_WIFI = NetworkCapabilities.TRANSPORT_WIFI;           // 1
    private static final int TRANSPORT_ETHERNET = NetworkCapabilities.TRANSPORT_ETHERNET;   // 3

    /** Recursion guard for hasTransport hook — prevents infinite loop when hook calls hasTransport */
    private static final ThreadLocal<Boolean> inHook = ThreadLocal.withInitial(() -> false);

    /** Known NetworkCapabilities capability constants for iteration fallback */
    private static final int[] KNOWN_CAPABILITIES = {
            NetworkCapabilities.NET_CAPABILITY_MMS,
            NetworkCapabilities.NET_CAPABILITY_SUPL,
            NetworkCapabilities.NET_CAPABILITY_DUN,
            NetworkCapabilities.NET_CAPABILITY_FOTA,
            NetworkCapabilities.NET_CAPABILITY_IMS,
            NetworkCapabilities.NET_CAPABILITY_CBS,
            NetworkCapabilities.NET_CAPABILITY_WIFI_P2P,
            NetworkCapabilities.NET_CAPABILITY_IA,
            NetworkCapabilities.NET_CAPABILITY_RCS,
            NetworkCapabilities.NET_CAPABILITY_XCAP,
            NetworkCapabilities.NET_CAPABILITY_EIMS,
            NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
            NetworkCapabilities.NET_CAPABILITY_INTERNET,
            NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED,
            NetworkCapabilities.NET_CAPABILITY_TRUSTED,
            16, // NET_CAPABILITY_NOT_VPN
            17, // NET_CAPABILITY_VALIDATED
            18, // NET_CAPABILITY_CAPTIVE_PORTAL
            19, // NET_CAPABILITY_NOT_ROAMING
            20, // NET_CAPABILITY_NOT_CONGESTED
            21, // NET_CAPABILITY_NOT_SUSPENDED
            23, // NET_CAPABILITY_FOREGROUND (API 29+)
    };

    private XSharedPreferences prefs;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if ("com.fakeethernet".equals(lpparam.packageName)) return;

        prefs = new XSharedPreferences("com.fakeethernet", PREFS_NAME);

        XposedBridge.log(TAG + ": loaded in " + lpparam.packageName);

        hookConnectivityManager();
        hookNetworkInfoMethods();
        hookNetworkCapabilities();
    }

    private boolean isEnabled() {
        prefs.reload();
        return prefs.getBoolean(KEY_ENABLED, true);
    }

    private int getFakeBandwidthKbps() {
        prefs.reload();
        int level = prefs.getInt(KEY_FAKE_BANDWIDTH, 1);
        switch (level) {
            case 0: return 50000;    // 50 Mbps
            case 1: return 100000;   // 100 Mbps
            case 2: return 500000;   // 500 Mbps
            case 3: return 1000000;  // 1 Gbps
            default: return 100000;
        }
    }

    // ============================================================
    // Layer 1: ConnectivityManager hooks
    // ============================================================

    private void hookConnectivityManager() {

        // getActiveNetworkInfo()
        XposedHelpers.findAndHookMethod(ConnectivityManager.class, "getActiveNetworkInfo",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isEnabled()) return;
                        NetworkInfo info = (NetworkInfo) param.getResult();
                        if (isWifiNetworkInfo(info)) {
                            param.setResult(buildFakeNetworkInfo(info));
                        }
                    }
                });

        // getActiveNetworkInfo(int)
        XposedHelpers.findAndHookMethod(ConnectivityManager.class, "getActiveNetworkInfo",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isEnabled()) return;
                        NetworkInfo info = (NetworkInfo) param.getResult();
                        if (isWifiNetworkInfo(info)) {
                            param.setResult(buildFakeNetworkInfo(info));
                        }
                    }
                });

        // getAllNetworkInfo()
        XposedHelpers.findAndHookMethod(ConnectivityManager.class, "getAllNetworkInfo",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isEnabled()) return;
                        NetworkInfo[] infos = (NetworkInfo[]) param.getResult();
                        if (infos == null) return;
                        for (int i = 0; i < infos.length; i++) {
                            if (isWifiNetworkInfo(infos[i])) {
                                infos[i] = buildFakeNetworkInfo(infos[i]);
                            }
                        }
                        param.setResult(infos);
                    }
                });

        // getNetworkInfo(Network)
        XposedHelpers.findAndHookMethod(ConnectivityManager.class, "getNetworkInfo",
                Network.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isEnabled()) return;
                        NetworkInfo info = (NetworkInfo) param.getResult();
                        if (isWifiNetworkInfo(info)) {
                            param.setResult(buildFakeNetworkInfo(info));
                        }
                    }
                });

        // getNetworkInfo(int)
        XposedHelpers.findAndHookMethod(ConnectivityManager.class, "getNetworkInfo",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isEnabled()) return;
                        int type = (int) param.args[0];
                        if (type == TYPE_ETHERNET) {
                            // App asks for Ethernet → redirect to WiFi query
                            param.args[0] = TYPE_WIFI;
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isEnabled()) return;
                        NetworkInfo info = (NetworkInfo) param.getResult();
                        if (isWifiNetworkInfo(info)) {
                            param.setResult(buildFakeNetworkInfo(info));
                        }
                    }
                });

        // getNetworkCapabilities(Network)
        XposedHelpers.findAndHookMethod(ConnectivityManager.class, "getNetworkCapabilities",
                Network.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isEnabled()) return;
                        NetworkCapabilities nc = (NetworkCapabilities) param.getResult();
                        if (nc != null && isWifiCapabilities(nc)) {
                            param.setResult(buildFakeNetworkCapabilities(nc));
                        }
                    }
                });
    }

    // ============================================================
    // Layer 2: NetworkInfo method hooks (safety net)
    // ============================================================

    private void hookNetworkInfoMethods() {

        // getType() → replace TYPE_WIFI with TYPE_ETHERNET
        XposedHelpers.findAndHookMethod(NetworkInfo.class, "getType",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isEnabled() || inHook.get()) return;
                        inHook.set(true);
                        try {
                            if ((int) param.getResult() == TYPE_WIFI) {
                                param.setResult(TYPE_ETHERNET);
                            }
                        } finally {
                            inHook.set(false);
                        }
                    }
                });

        // getTypeName() → "WIFI" → "ETHERNET"
        XposedHelpers.findAndHookMethod(NetworkInfo.class, "getTypeName",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isEnabled()) return;
                        if ("WIFI".equals(param.getResult())) {
                            param.setResult("ETHERNET");
                        }
                    }
                });

        // getSubtypeName() → clear for Ethernet type
        XposedHelpers.findAndHookMethod(NetworkInfo.class, "getSubtypeName",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isEnabled()) return;
                        // Use typeName string check instead of getType() to avoid recursion
                        NetworkInfo info = (NetworkInfo) param.thisObject;
                        String typeName = (String) XposedHelpers.callMethod(info, "getTypeName");
                        if ("ETHERNET".equals(typeName)) {
                            param.setResult("");
                        }
                    }
                });

        // getExtraInfo() → clear SSID
        XposedHelpers.findAndHookMethod(NetworkInfo.class, "getExtraInfo",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isEnabled()) return;
                        NetworkInfo info = (NetworkInfo) param.thisObject;
                        String typeName = (String) XposedHelpers.callMethod(info, "getTypeName");
                        if ("ETHERNET".equals(typeName)) {
                            param.setResult(null);
                        }
                    }
                });
    }

    // ============================================================
    // Layer 3: NetworkCapabilities hooks
    // ============================================================

    private void hookNetworkCapabilities() {

        // hasTransport(int) → swap WIFI ↔ ETHERNET
        XposedHelpers.findAndHookMethod(NetworkCapabilities.class, "hasTransport",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isEnabled()) return;
                        if (inHook.get()) return;
                        inHook.set(true);
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!inHook.get()) return;
                        try {
                            int transport = (int) param.args[0];
                            boolean result = (boolean) param.getResult();

                            if (transport == TRANSPORT_WIFI && result) {
                                // Was WiFi → report as NOT WiFi
                                param.setResult(false);
                            } else if (transport == TRANSPORT_ETHERNET && !result) {
                                // Not originally Ethernet → check if it was WiFi via raw bitmask
                                if (isWifiByBitmask((NetworkCapabilities) param.thisObject)) {
                                    param.setResult(true);
                                }
                            }
                        } finally {
                            inHook.set(false);
                        }
                    }
                });

        // getLinkDownstreamBandwidthKbps() → fake bandwidth
        XposedHelpers.findAndHookMethod(NetworkCapabilities.class,
                "getLinkDownstreamBandwidthKbps",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isEnabled()) return;
                        int original = (int) param.getResult();
                        if (original > 0) {
                            param.setResult(getFakeBandwidthKbps());
                        }
                    }
                });

        // getLinkUpstreamBandwidthKbps() → fake bandwidth
        XposedHelpers.findAndHookMethod(NetworkCapabilities.class,
                "getLinkUpstreamBandwidthKbps",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isEnabled()) return;
                        int original = (int) param.getResult();
                        if (original > 0) {
                            param.setResult(getFakeBandwidthKbps() / 2);
                        }
                    }
                });
    }

    // ============================================================
    // Helper: check network type
    // ============================================================

    /** Check if NetworkInfo represents WiFi — uses getTypeName to avoid hook recursion */
    private boolean isWifiNetworkInfo(NetworkInfo info) {
        if (info == null) return false;
        // Use getTypeName() which is less likely to be in a recursive chain
        return "WIFI".equals(info.getTypeName());
    }

    /** Check if NetworkCapabilities has WiFi transport — with recursion guard */
    private boolean isWifiCapabilities(NetworkCapabilities nc) {
        if (nc == null) return false;
        if (inHook.get()) return false;
        inHook.set(true);
        try {
            return nc.hasTransport(TRANSPORT_WIFI);
        } finally {
            inHook.set(false);
        }
    }

    /** Check WiFi transport by reading raw internal bitmask (bypasses hasTransport hook) */
    private boolean isWifiByBitmask(NetworkCapabilities nc) {
        try {
            long transportTypes = XposedHelpers.getLongField(nc, "mTransportTypes");
            return (transportTypes & (1L << TRANSPORT_WIFI)) != 0;
        } catch (Exception e) {
            XposedBridge.log(TAG + ": isWifiByBitmask failed: " + e.getMessage());
            return false;
        }
    }

    // ============================================================
    // Helper: build faked objects
    // ============================================================

    private NetworkInfo buildFakeNetworkInfo(NetworkInfo original) {
        try {
            NetworkInfo fake = new NetworkInfo(TYPE_ETHERNET, 0, "ETHERNET", "");
            // Use reflection for @hide / @SystemApi methods
            try {
                XposedHelpers.callMethod(fake, "setState", original.getState());
            } catch (Exception ignored) {}
            fake.setDetailedState(original.getDetailedState(), null, null);
            try {
                XposedHelpers.callMethod(fake, "setIsAvailable", original.isAvailable());
            } catch (Exception ignored) {}
            try {
                XposedHelpers.callMethod(fake, "setRoaming", original.isRoaming());
            } catch (Exception ignored) {}
            return fake;
        } catch (Exception e) {
            XposedBridge.log(TAG + ": buildFakeNetworkInfo failed: " + e.getMessage());
            return original;
        }
    }

    private NetworkCapabilities buildFakeNetworkCapabilities(NetworkCapabilities original) {
        try {
            // Clone via public constructor, then modify internal fields via reflection
            NetworkCapabilities fake = new NetworkCapabilities(original);

            // Swap transport bits: clear WIFI, set ETHERNET
            long transportTypes = XposedHelpers.getLongField(fake, "mTransportTypes");
            transportTypes &= ~(1L << TRANSPORT_WIFI);   // clear WIFI bit
            transportTypes |= (1L << TRANSPORT_ETHERNET); // set ETHERNET bit
            XposedHelpers.setLongField(fake, "mTransportTypes", transportTypes);

            // Set bandwidth via reflection on internal fields
            int bw = getFakeBandwidthKbps();
            try {
                XposedHelpers.setIntField(fake, "mLinkDownBandwidth", bw);
                XposedHelpers.setIntField(fake, "mLinkUpBandwidth", bw / 2);
            } catch (Exception e) {
                XposedBridge.log(TAG + ": set bandwidth via reflection failed: " + e.getMessage());
            }

            return fake;
        } catch (Exception e) {
            XposedBridge.log(TAG + ": buildFakeNetworkCapabilities failed: " + e.getMessage());
            return original;
        }
    }
}
