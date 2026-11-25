package com.homework.mobile;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

public final class ScanJob extends JobService {
    static final String ACTION_UPDATED = "com.homework.mobile.SCAN_UPDATED";
    static final String UPDATE_PERMISSION = "com.homework.mobile.permission.SCAN_UPDATED";
    static final int PERIODIC = 110;
    static final int MANUAL = 111;
    static final int CATCH_UP = 112;
    private static final long CATCH_UP_COOLDOWN_MS = 5 * 60_000L;
    private static int requestedSweep;
    private static boolean queued;
    private static String activePlatform;
    private static String activeStage;
    private int activeSweep;
    private final Map<Integer, JobParameters> pending = new LinkedHashMap<>();
    private Runnable cancelScan;
    private PasswordLogin login;
    private List<String> platforms;
    private int index;
    private Store store;

    static void configure(Context context) {
        Store store = new Store(context);
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        int interval = store.settings().interval();
        JobInfo existing = scheduler.getPendingJob(PERIODIC);
        if (!store.settings().background()) {
            if (existing != null) scheduler.cancel(PERIODIC);
            scheduler.cancel(CATCH_UP);
            return;
        }
        if (!store.hasConnectedPlatforms()) scheduler.cancel(CATCH_UP);
        if (existing != null && existing.getIntervalMillis() == interval * 60_000L) return;
        schedule(scheduler, job(context, PERIODIC).setPeriodic(interval * 60_000L).setPersisted(true).build());
    }

    static boolean runNow(Context context) {
        if (busy()) return true;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        boolean scheduled = scheduler.getPendingJob(MANUAL) != null
                || schedule(scheduler, job(context, MANUAL).setOverrideDeadline(0).build());
        if (scheduled) {
            scheduler.cancel(CATCH_UP);
            queued = true;
            publish(context);
        }
        return scheduled;
    }

    static void catchUp(Context context) {
        Store store = new Store(context);
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (!store.settings().background() || !store.hasConnectedPlatforms()) {
            scheduler.cancel(CATCH_UP);
            return;
        }
        long now = System.currentTimeMillis();
        if (!catchUpDue(store, now)) {
            if (!busy()) scheduler.cancel(CATCH_UP);
            return;
        }
        if (!online(context) || busy()) return;
        if (scheduler.getPendingJob(CATCH_UP) != null
                || scheduler.getPendingJob(MANUAL) != null) return;
        schedule(scheduler, job(context, CATCH_UP).build());
    }

    static boolean catchUpDue(Store store, long now) {
        long interval = store.settings().interval() * 60_000L;
        long cooldown = Math.min(interval, CATCH_UP_COOLDOWN_MS);
        for (String platform : Store.PLATFORMS) {
            if (!store.connected(platform)
                    || !Store.stale(store.lastSuccess(platform), now, interval)) continue;
            long attempted = store.lastAttempt(platform);
            if (attempted <= 0L || attempted > now || now - attempted >= cooldown) return true;
        }
        return false;
    }

    static void accountChanged(Context context) {
        requestedSweep++;
        runNow(context);
    }

    static boolean busy() {
        return queued || activePlatform != null;
    }

    static String progress(String platform) {
        return platform.equals(activePlatform) ? activeStage : null;
    }

    private void updateProgress(String platform, String stage) {
        queued = false;
        activePlatform = platform;
        activeStage = stage;
        publish(this);
    }

    private static JobInfo.Builder job(Context context, int id) {
        return new JobInfo.Builder(id, new ComponentName(context, ScanJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
    }

    private static boolean schedule(JobScheduler scheduler, JobInfo job) {
        boolean scheduled = scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS;
        if (!scheduled) Log.w("AgendaScan", "Android deferred schedule request " + job.getId());
        return scheduled;
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (params.getJobId() == CATCH_UP && (busy() || !autoScanWanted())) return false;
        if (cancelScan != null || login != null) {
            pending.put(params.getJobId(), params);
            return true;
        }
        store = new Store(this);
        if (!store.hasConnectedPlatforms()) {
            updateProgress(null, null);
            return false;
        }
        pending.put(params.getJobId(), params);
        beginSweep();
        return true;
    }

    private boolean autoScanWanted() {
        Store candidate = new Store(this);
        return candidate.settings().background() && candidate.hasConnectedPlatforms()
                && online(this) && catchUpDue(candidate, System.currentTimeMillis());
    }

    private static boolean online(Context context) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (manager == null) return false;
        Network network = manager.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private void beginSweep() {
        activeSweep = requestedSweep;
        platforms = new ArrayList<>();
        for (String platform : Store.PLATFORMS) if (store.connected(platform)) platforms.add(platform);
        index = 0;
        scanNext();
    }

    private void scanNext() {
        if (index == platforms.size()) {
            if (activeSweep != requestedSweep) {
                beginSweep();
                return;
            }
            for (JobParameters params : pending.values()) jobFinished(params, false);
            pending.clear();
            Reminders.schedule(this);
            updateProgress(null, null);
            return;
        }
        String platform = platforms.get(index++);
        String profile = store.accountProfile(platform);
        if (profile == null) {
            onScanResult(platform, null, authIssue("请先保存该平台的账号和密码"));
            return;
        }
        AccountVault.Account account;
        try {
            account = new AccountVault(this).read(platform);
        } catch (IllegalStateException ignored) {
            account = null;
        }
        if (account == null) {
            onScanResult(platform, profile, authIssue("无法读取已保存的凭据，请重新填写"));
            return;
        }
        updateProgress(platform, "正在用密码重新登录…");
        login = new PasswordLogin(this, platform, account, profile, issue -> {
            login = null;
            if (!Objects.equals(profile, store.accountProfile(platform))) {
                PasswordLogin.deleteProfile(profile);
                scanNext();
            } else if (issue == null) {
                startScan(platform, profile);
            } else {
                onScanResult(platform, profile, authIssue(issue));
            }
        });
        login.start();
    }

    private void startScan(String platform, String profile) {
        updateProgress(platform, "正在读取任务…");
        Consumer<JSONObject> completed = result -> {
            cancelScan = null;
            onScanResult(platform, profile, result);
        };
        ApiScan scanner = new ApiScan(platform, profile, store.storedRows(platform), completed);
        cancelScan = scanner::close;
        scanner.start();
    }

    private void onScanResult(String platform, String profile, JSONObject result) {
        if (Objects.equals(profile, store.accountProfile(platform))) {
            store.applyScan(platform, result);
        } else {
            PasswordLogin.deleteProfile(profile);
        }
        scanNext();
    }

    private static JSONObject authIssue(String issue) {
        try {
            return new JSONObject().put("status", "login_required").put("issues", new JSONArray().put(issue));
        } catch (JSONException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void publish(Context context) {
        context.sendBroadcast(new Intent(ACTION_UPDATED).setPackage(context.getPackageName()), UPDATE_PERMISSION);
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        pending.remove(params.getJobId());
        if (pending.isEmpty()) {
            closeActive();
            updateProgress(null, null);
        }
        return pending.isEmpty() && params.getJobId() == MANUAL;
    }

    @Override
    public void onDestroy() {
        closeActive();
        if (activePlatform != null) updateProgress(null, null);
        super.onDestroy();
    }

    private void closeActive() {
        if (cancelScan != null) cancelScan.run();
        if (login != null) login.close();
        cancelScan = null;
        login = null;
    }
}
