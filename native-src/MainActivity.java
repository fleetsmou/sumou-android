package com.sumou.fleet;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(SumouTrackerPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
