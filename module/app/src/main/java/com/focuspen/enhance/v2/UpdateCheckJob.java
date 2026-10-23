package com.focuspen.enhance.v2;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Daily, network-constrained release check in the module process. */
public final class UpdateCheckJob extends JobService {
    private static final String TAG = "FocusPenUpdates";
    private static final int JOB_ID = 0x46505550;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private JobParameters active;
    private Future<?> running;

    static void sync(Context context) {
        if (!BuildConfig.APPLICATION_ID.equals(context.getPackageName()))
            throw new SecurityException("Module process required");
        JobScheduler jobs = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (jobs == null) throw new IllegalStateException("JobScheduler unavailable");
        if (!UpdateChecker.isEnabled(context)) {
            jobs.cancel(JOB_ID);
            return;
        }
        if (jobs.getPendingJob(JOB_ID) != null) return;
        JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(context, UpdateCheckJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(DAY_MS)
                .setPersisted(true)
                .build();
        if (jobs.schedule(info) != JobScheduler.RESULT_SUCCESS)
            Log.w(TAG, "Could not schedule daily update check");
    }

    @Override public boolean onStartJob(JobParameters parameters) {
        if (!UpdateChecker.isEnabled(this)) return false;
        active = parameters;
        running = executor.submit(() -> {
            try { UpdateChecker.check(getApplicationContext(), false, parameters.getNetwork()); }
            catch (RuntimeException failure) { Log.w(TAG, "Daily update check failed", failure); }
            main.post(() -> {
                if (active != parameters) return;
                active = null;
                running = null;
                jobFinished(parameters, false);
            });
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        if (active != parameters) return false;
        active = null;
        if (running != null) running.cancel(true);
        running = null;
        return UpdateChecker.isEnabled(this);
    }

    @Override public void onDestroy() {
        active = null;
        if (running != null) running.cancel(true);
        executor.shutdownNow();
        super.onDestroy();
    }
}
