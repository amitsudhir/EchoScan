package com.aitd.qrscannertts;

import android.content.Context;
import android.content.SharedPreferences;

public class OnboardingPref {

    private static final String PREF_NAME = "OnboardingPref";
    private static final String ONBOARDING_SEEN = "onboarding_seen";

    public static boolean hasOnboardingBeenSeen(Context context) {
        SharedPreferences sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sharedPreferences.getBoolean(ONBOARDING_SEEN, false);
    }

    public static void setOnboardingSeen(Context context, boolean seen) {
        SharedPreferences.Editor editor = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
        editor.putBoolean(ONBOARDING_SEEN, seen);
        editor.apply();
    }
}
