package com.camview.monitor;

import android.app.Application;

public class App extends Application {

    private static App instance;
    private AppConfig config;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        config = new AppConfig(this);
    }

    public static AppConfig cfg() {
        return instance.config;
    }

    public static AppConfig reload() {
        instance.config.load();
        return instance.config;
    }
}
