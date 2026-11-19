package com.mdproject.dfrtools;

import android.app.Application;
import android.content.Context;

/** Holds the application context for code that has none of its own (the built-in demo relay). */
public class DfrApp extends Application {
    private static Context appContext;

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();
    }

    public static Context get() {
        return appContext;
    }
}
