package com.android.server.droiduse;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.UserHandle;
import java.util.Set;

/** Restricted initial set; camera/mic/location and role-controlled permissions stay outside M3. */
final class DroidUsePermissions {
    private static final Set<String> MANAGEABLE = Set.of(
            "android.permission.POST_NOTIFICATIONS", "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO");
    private static final int FIXED = PackageManager.FLAG_PERMISSION_USER_FIXED
            | PackageManager.FLAG_PERMISSION_POLICY_FIXED | PackageManager.FLAG_PERMISSION_SYSTEM_FIXED
            | PackageManager.FLAG_PERMISSION_GRANTED_BY_ROLE | PackageManager.FLAG_PERMISSION_APPLY_RESTRICTION;
    private final PackageManager pm;
    private final String target;
    DroidUsePermissions(Context context, String target) { pm = context.getPackageManager(); this.target = target; }

    void observe(DroidUseSystemActions actions) throws Exception {
        PackageInfo pkg = pm.getPackageInfo(target, PackageManager.GET_PERMISSIONS);
        if (pkg.applicationInfo == null || pkg.applicationInfo.uid < 10000 || pkg.applicationInfo.targetSdkVersion < 33
                || pkg.requestedPermissions == null) return;
        int uid = pkg.applicationInfo.uid;
        String[] owners = pm.getPackagesForUid(uid);
        if (owners == null || owners.length != 1 || !target.equals(owners[0])) return;
        for (String permission : pkg.requestedPermissions) {
            if (!MANAGEABLE.contains(permission)) continue;
            PermissionInfo info = pm.getPermissionInfo(permission, 0);
            if (info.getProtection() != PermissionInfo.PROTECTION_DANGEROUS) continue;
            int flags = pm.getPermissionFlags(permission, target, UserHandle.SYSTEM);
            if ((flags & FIXED) != 0) continue;
            boolean granted = pm.checkPermission(permission, target) == PackageManager.PERMISSION_GRANTED;
            actions.offer(granted ? "permission_revoke" : "permission_grant",
                    "任务应用 " + target + "：" + (granted ? "撤销已授予的" : "授予未授权的") + permission,
                    value -> {
                        String[] currentOwners = pm.getPackagesForUid(uid);
                        if (currentOwners == null || currentOwners.length != 1 || !target.equals(currentOwners[0])
                                || pm.getPackageUid(target, 0) != uid
                                || pm.getPermissionFlags(permission, target, UserHandle.SYSTEM) != flags
                                || (pm.checkPermission(permission, target) == PackageManager.PERMISSION_GRANTED) != granted)
                            throw DroidUseSystemActions.stale();
                        if (granted) pm.revokeRuntimePermission(target, permission, UserHandle.SYSTEM);
                        else pm.grantRuntimePermission(target, permission, UserHandle.SYSTEM);
                        if ((pm.checkPermission(permission, target) == PackageManager.PERMISSION_GRANTED) == granted)
                            throw new IllegalStateException("PERMISSION_NOT_CHANGED");
                    });
        }
    }
}
