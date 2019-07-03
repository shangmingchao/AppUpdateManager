package com.frank.appupdatemanager;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;

public class MainActivity extends AppCompatActivity {

    private AppUpdateManager appUpdateManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        findViewById(R.id.download).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                appUpdateManager = AppUpdateManager.getInstance(MainActivity.this,
                        new AppUpdateManager.Version() {
                            @Override
                            public String getName() {
                                return "V8.0.8";
                            }

                            @Override
                            public String getDescription() {
                                return "- 长按聊天窗口空白处，多窗口预览更方便\n- 群聊支持添加机器人，配置贴心小助理";
                            }

                            @Override
                            public String getUrl() {
                                return "https://";
                            }

                            @Override
                            public String getChecksum() {
                                return "be3575f52431fecb2b40d9c948bf33e6";
                            }
                        })
                        .update();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (appUpdateManager != null) {
            appUpdateManager.clear();
        }
    }
}
