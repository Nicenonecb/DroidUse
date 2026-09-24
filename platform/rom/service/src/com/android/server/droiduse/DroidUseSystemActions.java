package com.android.server.droiduse;

import android.content.Context;
import com.android.server.wm.DroidUseWindowSnapshot;
import dev.droiduse.system.ActionTarget;
import dev.droiduse.system.DeviceOperation;
import dev.droiduse.system.DroidUseContract;
import dev.droiduse.system.SystemActionPolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Session-local, single-frame authority for non-input operations. Never accepts arbitrary IDs. */
final class DroidUseSystemActions {
    interface Action { void run(String value) throws Exception; }
    private record Entry(String kind, Action action) {}
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final ArrayList<ActionTarget> targets = new ArrayList<>();
    private final ArrayList<String> facts = new ArrayList<>();
    private final DroidUseNotifications notifications;
    private final DroidUsePermissions permissions;
    private final DroidUseSettings settings;
    private final String targetPackage;
    private final boolean allowGlobalSettings;

    DroidUseSystemActions(Context context, String targetPackage, boolean allowGlobalSettings) {
        this.targetPackage = targetPackage;
        this.allowGlobalSettings = allowGlobalSettings;
        notifications = new DroidUseNotifications(context, targetPackage);
        permissions = new DroidUsePermissions(context, targetPackage);
        settings = new DroidUseSettings(context);
    }

    ActionTarget[] observe(DroidUseWindowSnapshot window, int displayId) throws Exception {
        invalidate();
        if (window.secure) return new ActionTarget[0];
        // Cross-app navigation does not extend notification or permission authority.
        if (targetPackage.equals(window.packageName)) {
            notifications.observe(this, window, displayId);
            permissions.observe(this);
        }
        if (allowGlobalSettings) settings.observe(this);
        return targets.toArray(new ActionTarget[0]);
    }

    void offer(String kind, String label, Action action) {
        if (targets.size() >= 64) return;
        ActionTarget target = new ActionTarget();
        target.targetId = UUID.randomUUID().toString();
        target.kind = kind;
        target.label = label.substring(0, Math.min(500, label.length()));
        entries.put(target.targetId, new Entry(kind, action));
        targets.add(target);
    }

    void execute(int domain, DeviceOperation operation) throws Exception {
        if (!SystemActionPolicy.validRequest(domain, operation)) throw stale();
        Entry entry = entries.get(operation.targetId);
        if (entry == null || SystemActionPolicy.kind(entry.kind) != operation.kind) throw stale();
        if ((domain == DroidUseContract.DOMAIN_DEVICE || domain == DroidUseContract.DOMAIN_CONNECTIVITY)
                && !allowGlobalSettings) throw stale();
        invalidate();
        entry.action.run(operation.stringValue);
    }

    void fact(String text) { if (facts.size() < 24) facts.add(text.substring(0, Math.min(500, text.length()))); }
    String context() { return String.join("\n", facts); }
    void invalidate() { entries.clear(); targets.clear(); facts.clear(); }
    static DroidUsePlatform.Failure stale() {
        return new DroidUsePlatform.Failure(DroidUseContract.ERROR_INVALID_REQUEST, DroidUseContract.STALE_OBSERVATION);
    }
}
