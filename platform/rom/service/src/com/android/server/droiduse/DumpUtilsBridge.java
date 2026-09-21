package com.android.server.droiduse;

import android.content.Context;

import com.android.internal.util.DumpUtils;

import java.io.PrintWriter;

final class DumpUtilsBridge {
    private DumpUtilsBridge() {}

    static boolean checkDumpPermission(Context context, PrintWriter out) {
        return DumpUtils.checkDumpPermission(context, DroidUseManagerService.SERVICE_NAME, out);
    }
}
