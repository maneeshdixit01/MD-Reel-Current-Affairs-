package com.md.mdreelcurrentaffair;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(NativeTtsPlugin.class);
        registerPlugin(VideoFixerPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
