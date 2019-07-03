package com.frank.appupdatemanager;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.v4.content.FileProvider;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.Formatter;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class AppUpdateManager {

    private static final char[] HEX_CHAR_ARRAY = "0123456789abcdef".toCharArray();
    private static final String APP_UPDATE_TASK_ID = "app_update_task_id";
    private static final long NO_TASK = -1L;
    private static final int MSG_QUERY_PROGRESS = 1;
    private static final int MSG_SHOW_PROGRESS = 2;
    private static final int MSG_ERROR = 3;
    private volatile static AppUpdateManager instance;

    private WeakReference<Activity> mActivityRef;
    private Version mVersion;
    private DownloadManager mDownloadManager;
    private long mDownloadTaskId;
    private SharedPreferences mSharedPreferences;
    private CompleteBroadcastReceiver mCompleteBroadcastReceiver;
    private ProgressHandler mProgressHandler;
    private ExecutorService mThreadPool;
    private WeakReference<Dialog> mConfirmDialogRef;
    private WeakReference<Dialog> mDisableDialogRef;
    private WeakReference<Dialog> mProgressDialogRef;
    private WeakReference<View> mProgressViewRef;
    private WeakReference<Dialog> mInstallDialogRef;
    private WeakReference<Dialog> mRetryDialogRef;
    private List<WeakReference<Dialog>> mDialogList;
    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "AppUpdateManager #" + mCount.getAndIncrement());
        }
    };

    public static AppUpdateManager getInstance(Activity activity, Version version) {
        if (instance == null) {
            synchronized (AppUpdateManager.class) {
                if (instance == null) {
                    instance = new AppUpdateManager(activity, version);
                }
            }
        }
        return instance;
    }

    private AppUpdateManager(Activity activity, Version version) {
        mActivityRef = new WeakReference<>(activity);
        mVersion = version;
        mDownloadManager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(activity);
        mProgressHandler = new ProgressHandler(this);
        mThreadPool = new ThreadPoolExecutor(1, 1, 0L,
                TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(1024),
                sThreadFactory, new ThreadPoolExecutor.AbortPolicy());
    }

    public AppUpdateManager update() {
        if (!checkDownloadManagerService()) {
            showDisableDialog();
            return this;
        }
        mDownloadTaskId = mSharedPreferences.getLong(APP_UPDATE_TASK_ID, NO_TASK);
        if (mDownloadTaskId != NO_TASK) {
            mProgressHandler.sendEmptyMessage(MSG_QUERY_PROGRESS);
            return this;
        }
        showConfirmDialog();
        return this;
    }

    public void clear() {
        Activity activity = mActivityRef.get();
        if (activity != null && mCompleteBroadcastReceiver != null) {
            activity.unregisterReceiver(mCompleteBroadcastReceiver);
            mCompleteBroadcastReceiver = null;
        }
        mProgressHandler.removeCallbacksAndMessages(null);
        mThreadPool.shutdown();
        clearDialog();
        instance = null;
    }

    private void startDownload() {
        Activity activity = mActivityRef.get();
        if (activity == null) {
            return;
        }
        if (mCompleteBroadcastReceiver == null) {
            mCompleteBroadcastReceiver = new CompleteBroadcastReceiver();
            activity.registerReceiver(mCompleteBroadcastReceiver,
                    new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }
        String filename = activity.getString(R.string.app_name) + mVersion.getName() + ".apk";
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(mVersion.getUrl()))
                .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, filename);
        mDownloadTaskId = mDownloadManager.enqueue(request);
        mSharedPreferences.edit().putLong(APP_UPDATE_TASK_ID, mDownloadTaskId).apply();
        mProgressHandler.sendEmptyMessage(MSG_QUERY_PROGRESS);
    }

    private void onDownloadCompleted() {
        Activity activity = mActivityRef.get();
        if (activity == null) {
            return;
        }
        if (mCompleteBroadcastReceiver != null) {
            activity.unregisterReceiver(mCompleteBroadcastReceiver);
            mCompleteBroadcastReceiver = null;
        }
        mProgressHandler.removeCallbacksAndMessages(null);
        dismissProgressDialog();
        mSharedPreferences.edit().putLong(APP_UPDATE_TASK_ID, NO_TASK).apply();
        if (mThreadPool.isShutdown()) {
            return;
        }
        mThreadPool.execute(new Runnable() {
            @Override
            public void run() {
                DownloadManager.Query query = new DownloadManager.Query().setFilterById(mDownloadTaskId);
                Cursor cursor = null;
                Activity activity = mActivityRef.get();
                if (activity == null) {
                    return;
                }
                try {
                    cursor = mDownloadManager.query(query);
                    if (cursor != null && cursor.moveToFirst()) {
                        String localUri = cursor.getString(cursor.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_LOCAL_URI));
                        Intent installIntent = new Intent(Intent.ACTION_VIEW);
                        File apkFile = new File(new URI(localUri));
                        String checksum = getChecksum(apkFile);
                        if (checksum == null || !checksum.equals(mVersion.getChecksum())) {
                            Message message = mProgressHandler.obtainMessage(MSG_ERROR);
                            message.obj = activity.getString(R.string.check_files_failed_tips);
                            mProgressHandler.sendMessage(message);
                            return;
                        }
                        Uri contentUri = FileProvider.getUriForFile(activity,
                                "com.frank.appupdatemanager.fileprovider", apkFile);
                        installIntent.setDataAndType(contentUri,
                                "application/vnd.android.package-archive");
                        installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        if (installIntent.resolveActivity(activity.getPackageManager()) != null) {
                            activity.startActivity(installIntent);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    Message message = mProgressHandler.obtainMessage(MSG_ERROR);
                    message.obj = activity.getString(R.string.download_apk_failed_tips);
                    mProgressHandler.sendMessage(message);
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
            }
        });
    }

    private void queryProgress() {
        mThreadPool.execute(new Runnable() {
            @Override
            public void run() {
                DownloadManager.Query query = new DownloadManager.Query().setFilterById(mDownloadTaskId);
                Cursor cursor = null;
                try {
                    cursor = mDownloadManager.query(query);
                    if (cursor == null) {
                        return;
                    }
                    if (cursor.moveToFirst()) {
                        int bytes = cursor.getInt(cursor.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        int total = cursor.getInt(cursor.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        int status = cursor.getInt(cursor.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_STATUS));
                        Message message = mProgressHandler.obtainMessage(MSG_SHOW_PROGRESS);
                        message.arg1 = bytes;
                        message.arg2 = total;
                        message.obj = status;
                        mProgressHandler.sendMessage(message);
                        if (DownloadManager.STATUS_PENDING == status
                                || DownloadManager.STATUS_RUNNING == status
                                || DownloadManager.STATUS_PAUSED == status) {
                            mProgressHandler.sendEmptyMessageDelayed(MSG_QUERY_PROGRESS, 500);
                        } else if (DownloadManager.STATUS_SUCCESSFUL == status) {
                            mProgressHandler.removeMessages(MSG_QUERY_PROGRESS);
                        } else if (DownloadManager.STATUS_FAILED == status) {
                            int errorCode = cursor.getInt(cursor.getColumnIndexOrThrow(
                                    DownloadManager.COLUMN_REASON));
                            mProgressHandler.removeMessages(MSG_QUERY_PROGRESS);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
            }
        });
    }

    private void showProgress(int status, int bytes, int total) {
        if (DownloadManager.STATUS_PENDING == status
                || DownloadManager.STATUS_RUNNING == status
                || DownloadManager.STATUS_PAUSED == status) {
            showProgressDialog(bytes, total);
        } else if (DownloadManager.STATUS_SUCCESSFUL == status) {
            dismissProgressDialog();
            showInstallDialog();
        } else if (DownloadManager.STATUS_FAILED == status) {
            Activity activity = mActivityRef.get();
            if (activity != null) {
                dismissProgressDialog();
                showRetryDialog(activity.getString(R.string.download_manager_failed_tips));
            }
        }
    }

    private void showConfirmDialog() {
        Activity activity = mActivityRef.get();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        if (mConfirmDialogRef == null || mConfirmDialogRef.get() == null) {
            Dialog confirmDialog = new AlertDialog.Builder(activity)
                    .setTitle(R.string.new_version_ready)
                    .setMessage(mVersion.getDescription())
                    .setPositiveButton(R.string.update, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            startDownload();
                            dialog.dismiss();
                        }
                    })
                    .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    })
                    .create();
            mConfirmDialogRef = new WeakReference<>(confirmDialog);
            addDialog(mConfirmDialogRef);
        }
        Dialog dialog = mConfirmDialogRef.get();
        if (dialog != null) {
            dialog.show();
        }
    }

    private void showProgressDialog(int bytes, int total) {
        Activity activity = mActivityRef.get();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        if (mProgressDialogRef == null || mProgressDialogRef.get() == null) {
            View progressView = LayoutInflater.from(activity).inflate(
                    R.layout.layout_download_progress, null);
            mProgressViewRef = new WeakReference<>(progressView);
            progressView.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            Dialog progressDialog = new AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.download_app))
                    .setView(progressView)
                    .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            mDownloadManager.remove(mDownloadTaskId);
                            mSharedPreferences.edit().putLong(APP_UPDATE_TASK_ID, NO_TASK).apply();
                            dialog.dismiss();
                        }
                    })
                    .setCancelable(false)
                    .create();
            mProgressDialogRef = new WeakReference<>(progressDialog);
            addDialog(mProgressDialogRef);
        }
        float percent;
        if (bytes == -1 || total == -1) {
            percent = 0;
        } else {
            percent = bytes * 1.0f / total;
        }
        View progressView = mProgressViewRef != null ? mProgressViewRef.get() : null;
        if (progressView != null) {
            String bytesDesc = bytes == -1 ? "--" : Formatter.formatFileSize(activity, bytes);
            String totalDesc = total == -1 ? "--" : Formatter.formatFileSize(activity, total);
            ProgressBar progressBar = (ProgressBar) progressView.findViewById(R.id.pb_progress);
            TextView progressDescTextView = (TextView) progressView.findViewById(R.id.tv_progress_desc);
            progressBar.setProgress((int) (percent * 100));
            SpannableStringBuilder progressDesc = new SpannableStringBuilder();
            progressDesc.append(bytesDesc);
            String totalSize = String.format("/%s", totalDesc);
            progressDesc.append(totalSize);
            progressDesc.setSpan(new ForegroundColorSpan(
                            activity.getResources().getColor(R.color.gray)),
                    progressDesc.length() - totalSize.length(),
                    progressDesc.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            progressDescTextView.setText(progressDesc);
        }
        Dialog dialog = mProgressDialogRef.get();
        if (dialog != null) {
            dialog.show();
        }
    }

    private void dismissProgressDialog() {
        Activity activity = mActivityRef.get();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        Dialog progressDialog = mProgressDialogRef != null ? mProgressDialogRef.get() : null;
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
    }

    private void showRetryDialog(String reason) {
        Activity activity = mActivityRef.get();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        if (mRetryDialogRef == null || mRetryDialogRef.get() == null) {
            Dialog retryDialog = new AlertDialog.Builder(activity)
                    .setTitle(R.string.prompt)
                    .setMessage(reason)
                    .setPositiveButton(R.string.retry, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            mDownloadManager.remove(mDownloadTaskId);
                            mSharedPreferences.edit().putLong(APP_UPDATE_TASK_ID, NO_TASK).apply();
                            update();
                            dialog.dismiss();
                        }
                    })
                    .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    })
                    .create();
            mRetryDialogRef = new WeakReference<>(retryDialog);
            addDialog(mRetryDialogRef);
        }
        Dialog dialog = mRetryDialogRef.get();
        if (dialog != null) {
            dialog.show();
        }
    }

    private void showInstallDialog() {
        Activity activity = mActivityRef.get();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        if (mInstallDialogRef == null || mInstallDialogRef.get() == null) {
            Dialog installDialog = new AlertDialog.Builder(activity)
                    .setTitle(String.format(activity.getString(R.string.update_app_tips_title),
                            mVersion.getName()))
                    .setMessage(R.string.update_app_tips_desc)
                    .setPositiveButton(R.string.install, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            onDownloadCompleted();
                            dialog.dismiss();
                        }
                    })
                    .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    })
                    .create();
            mInstallDialogRef = new WeakReference<>(installDialog);
            addDialog(mInstallDialogRef);
        }
        Dialog dialog = mInstallDialogRef.get();
        if (dialog != null) {
            dialog.show();
        }
    }

    private void showDisableDialog() {
        Activity activity = mActivityRef.get();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        if (mDisableDialogRef == null || mDisableDialogRef.get() == null) {
            Dialog disableDialog = new AlertDialog.Builder(activity)
                    .setTitle(R.string.prompt)
                    .setMessage(R.string.download_manager_disable_tips)
                    .setPositiveButton(R.string.go_to_settings, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Activity context = mActivityRef.get();
                            if (context == null) {
                                return;
                            }
                            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                            intent.setData(Uri.fromParts("package",
                                    "com.android.providers.downloads", null));
                            if (intent.resolveActivity(context.getPackageManager()) != null) {
                                context.startActivity(intent);
                            } else {
                                Toast.makeText(context, context.getString(R.string.download_apk_failed_tips),
                                        Toast.LENGTH_SHORT).show();
                            }
                            dialog.dismiss();
                        }
                    })
                    .setNegativeButton(R.string.download_by_browser, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Activity context = mActivityRef.get();
                            if (context == null) {
                                return;
                            }
                            Intent intent = new Intent(Intent.ACTION_VIEW);
                            intent.setData(Uri.parse(mVersion.getUrl()));
                            if (intent.resolveActivity(context.getPackageManager()) != null) {
                                context.startActivity(intent);
                            } else {
                                Toast.makeText(context, context.getString(R.string.download_apk_failed_tips),
                                        Toast.LENGTH_SHORT).show();
                            }
                            dialog.dismiss();
                        }
                    })
                    .create();
            mDisableDialogRef = new WeakReference<>(disableDialog);
            addDialog(mDisableDialogRef);
        }
        Dialog dialog = mDisableDialogRef.get();
        if (dialog != null) {
            dialog.show();
        }
    }

    private boolean checkDownloadManagerService() {
        Activity activity = mActivityRef.get();
        if (activity != null) {
            int state = activity.getPackageManager().getApplicationEnabledSetting(
                    "com.android.providers.downloads");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                return !(state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                        state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                        || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED);
            } else {
                return !(state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                        state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER);
            }
        }
        return true;
    }

    private String getChecksum(File file) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            InputStream fis = new FileInputStream(file);
            byte[] buffer = new byte[1024];
            int numRead;
            do {
                numRead = fis.read(buffer);
                if (numRead > 0) {
                    md.update(buffer, 0, numRead);
                }
            } while (numRead != -1);
            fis.close();
            return bytesToHex(md.digest());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_CHAR_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_CHAR_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    private void addDialog(WeakReference<Dialog> dialogRef) {
        if (mDialogList == null) {
            mDialogList = new ArrayList<>();
        }
        if (dialogRef != null) {
            mDialogList.add(dialogRef);
        }
    }

    private void clearDialog() {
        if (mDialogList != null) {
            for (WeakReference<Dialog> dialogRef : mDialogList) {
                Dialog dialog = dialogRef.get();
                if (dialog != null && dialog.isShowing()) {
                    dialog.dismiss();
                }
            }
            mDialogList.clear();
            mDialogList = null;
        }
    }

    private static class ProgressHandler extends Handler {

        private final WeakReference<AppUpdateManager> mAppUpdateManagerRef;

        public ProgressHandler(AppUpdateManager appUpdateManager) {
            mAppUpdateManagerRef = new WeakReference<>(appUpdateManager);
        }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            final AppUpdateManager appUpdateManager = mAppUpdateManagerRef.get();
            if (appUpdateManager != null) {
                switch (msg.what) {
                    case MSG_QUERY_PROGRESS:
                        appUpdateManager.queryProgress();
                        break;
                    case MSG_SHOW_PROGRESS:
                        appUpdateManager.showProgress((Integer) msg.obj, msg.arg1, msg.arg2);
                        break;
                    case MSG_ERROR:
                        appUpdateManager.showRetryDialog((String) msg.obj);
                        break;
                    default:
                        break;
                }
            }
        }
    }

    private class CompleteBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            long taskId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, NO_TASK);
            if (taskId == mDownloadTaskId) {
                onDownloadCompleted();
            }
        }
    }

    public interface Version {
        String getName();

        String getDescription();

        String getUrl();

        String getChecksum();
    }

}
