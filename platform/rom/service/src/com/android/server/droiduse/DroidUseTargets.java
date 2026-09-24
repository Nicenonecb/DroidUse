package com.android.server.droiduse;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.MediaStore;
import com.android.server.wm.DroidUseWindowSnapshot;
import dev.droiduse.system.ActionTarget;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Issues launch handles for one observation; model input never supplies an Intent or package. */
final class DroidUseTargets {
    private final PackageManager packages;
    private final Set<String> pickerPackages = new LinkedHashSet<>();
    private final Set<String> admittedPackages = new LinkedHashSet<>();
    private final Map<String, String> handles = new HashMap<>();

    DroidUseTargets(PackageManager packages, String rootPackage) {
        this.packages = packages;
        admittedPackages.add(rootPackage);
        addPicker(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"));
        addPicker(new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"));
        admittedPackages.addAll(pickerPackages);
    }

    private void addPicker(Intent intent) {
        ResolveInfo resolved = packages.resolveActivity(intent, PackageManager.MATCH_SYSTEM_ONLY);
        if (resolved == null || resolved.activityInfo == null) return;
        ApplicationInfo app = resolved.activityInfo.applicationInfo;
        if (app.uid >= 10000 && (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                && !excluded(app.packageName)) pickerPackages.add(app.packageName);
    }

    List<String> initialPackages() { return new ArrayList<>(admittedPackages); }
    boolean allows(String name) { return admittedPackages.contains(name); }
    boolean isPicker(String name) { return pickerPackages.contains(name); }
    void invalidate() { handles.clear(); }

    boolean hasConflict(DroidUseWindowSnapshot window) {
        for (String name : admittedPackages) {
            try {
                if (window.isVisibleElsewhere(packages.getApplicationInfo(name, 0).uid)) return true;
            } catch (PackageManager.NameNotFoundException missing) {
                return true;
            }
        }
        return false;
    }

    ActionTarget[] observe(DroidUseWindowSnapshot window) {
        invalidate();
        if (window.secure || isPicker(window.packageName)) return new ActionTarget[0];
        List<ActionTarget> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<ResolveInfo> installed = packages.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        installed.sort(java.util.Comparator.comparing(info -> info.activityInfo.packageName));
        for (ResolveInfo info : installed) {
            String name = info.activityInfo.packageName;
            if (!seen.add(name) || name.equals(window.packageName) || !canLaunch(name, window)) continue;
            String id = UUID.randomUUID().toString();
            handles.put(id, name);
            ActionTarget target = new ActionTarget();
            target.targetId = id;
            target.kind = "open_app";
            String label = String.valueOf(info.loadLabel(packages));
            target.label = label.substring(0, Math.min(label.length(), 400)) + " (" + name + ")";
            if (target.label.length() > 500) target.label = name;
            result.add(target);
            if (result.size() == 100) break;
        }
        return result.toArray(new ActionTarget[0]);
    }

    String resolve(String handle, DroidUseWindowSnapshot window) {
        String name = handles.get(handle);
        return name != null && canLaunch(name, window) ? name : null;
    }

    void admitted(String name) { admittedPackages.add(name); }

    private boolean canLaunch(String name, DroidUseWindowSnapshot window) {
        if (excluded(name) || (!admittedPackages.contains(name) && admittedPackages.size() >= 32))
            return false;
        Intent launch = packages.getLaunchIntentForPackage(name);
        ResolveInfo resolved = launch == null ? null : packages.resolveActivity(launch, 0);
        if (resolved == null || resolved.activityInfo == null) return false;
        ActivityInfo activity = resolved.activityInfo;
        // Global/single-task launch modes can move an existing primary-screen task.
        return name.equals(activity.packageName) && activity.exported && activity.enabled
                && activity.applicationInfo.enabled && activity.applicationInfo.uid >= 10000
                && activity.launchMode == ActivityInfo.LAUNCH_MULTIPLE
                && !window.isVisibleElsewhere(activity.applicationInfo.uid);
    }

    private boolean excluded(String name) {
        return name == null || name.equals("dev.droiduse.assistant")
                || name.equals("dev.droiduse.executor")
                || name.equals(packages.getPermissionControllerPackageName())
                || name.equals("com.android.settings") || name.equals("com.android.systemui");
    }
}
