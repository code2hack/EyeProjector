package com.code2hack.eyeprojector.uitargetaccess;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** One read-only observation. No launches, input injection, node actions or UI text export. */
public final class UiTargetAccessInstrumentation extends Instrumentation {
    private Bundle arguments;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        arguments = args == null ? new Bundle() : new Bundle(args);
        start();
    }

    @Override public void onStart() {
        JSONObject report = new JSONObject();
        Bundle result = new Bundle();
        UiAutomation automation = null;
        AccessibilityServiceInfo original = null;
        int originalFlags = 0;
        boolean completed = false;
        try {
            report.put("schema", "eyeprojector.ui-target-access.v1");
            report.put("runId", arguments.getString("runId", "unspecified"));
            report.put("startedElapsedRealtimeMs", SystemClock.elapsedRealtime());
            report.put("sdkInt", Build.VERSION.SDK_INT);
            report.put("caller", getComponentName().flattenToString());
            report.put("callerUid", Process.myUid());
            report.put("callerPid", Process.myPid());
            report.put("nodeActionsInvoked", 0);
            report.put("coordinateSpace", "AccessibilityNodeInfo.getBoundsInScreen; no encoded-frame association");
            // These are required observation selectors, never a default-display fallback.
            int displayId = Integer.parseInt(arguments.getString("displayId"));
            String targetPackage = arguments.getString("targetPackage");
            if (targetPackage == null || targetPackage.isEmpty())
                throw new IllegalArgumentException("targetPackage required");
            report.put("requestedDisplayId", displayId);
            report.put("targetPackage", targetPackage);
            report.put("automationFlags", UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            if (automation == null) throw new IllegalStateException("UiAutomation unavailable");
            original = automation.getServiceInfo();
            if (original == null) throw new IllegalStateException("UiAutomation service info unavailable");
            originalFlags = original.flags;
            report.put("serviceCapabilities", original.getCapabilities());
            report.put("serviceFlagsBefore", originalFlags);
            original.flags = originalFlags | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            automation.setServiceInfo(original);
            report.put("serviceFlagsRequested", original.flags);
            AccessibilityServiceInfo observed = automation.getServiceInfo();
            report.put("serviceFlagsObserved", observed == null ? JSONObject.NULL : observed.flags);
            observe(automation, displayId, targetPackage, report);
            completed = true;
        } catch (Exception error) {
            // Exception messages/stack traces can contain framework objects or private UI text.
            putError(report, "observationErrorClass", error);
        } finally {
            if (automation != null && original != null) {
                try {
                    original.flags = originalFlags;
                    automation.setServiceInfo(original);
                    report.put("originalAutomationFlagsRestored", true);
                } catch (Exception error) {
                    putError(report, "restoreErrorClass", error);
                    completed = false;
                }
            }
            try {
                report.put("observationCompleted", completed);
                report.put("finishedElapsedRealtimeMs", SystemClock.elapsedRealtime());
                report.put("cleanup", "Instrumentation.finish releases this temporary caller; verify shell completion externally");
                AtomicFile output = new AtomicFile(new File(getContext().getFilesDir(), "ui-target-access.json"));
                FileOutputStream stream = output.startWrite();
                try {
                    stream.write((report.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
                    output.finishWrite(stream);
                } catch (Exception error) {
                    output.failWrite(stream);
                    throw error;
                }
                result.putString("reportFile", "files/ui-target-access.json");
                result.putString("runId", arguments.getString("runId", "unspecified"));
            } catch (Exception error) {
                result.putString("reportWriteErrorClass", error.getClass().getName());
                completed = false;
            }
            // Completion is not an assertion that the display, package or actionable nodes exist.
            finish(completed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
        }
    }

    private static void observe(UiAutomation automation, int displayId, String targetPackage,
                                JSONObject report) throws Exception {
        SparseArray<List<AccessibilityWindowInfo>> all = automation.getWindowsOnAllDisplays();
        JSONArray inventory = new JSONArray();
        JSONArray selected = new JSONArray();
        report.put("displayInventory", inventory);
        report.put("windows", selected);
        report.put("requestedDisplayReported", all.indexOfKey(displayId) >= 0);
        try {
            for (int i = 0; i < all.size(); i++) {
                List<AccessibilityWindowInfo> windows = all.valueAt(i);
                inventory.put(new JSONObject().put("displayId", all.keyAt(i)).put("windowCount", windows.size()));
                if (all.keyAt(i) != displayId) continue; // Do not fetch other displays' roots.
                for (AccessibilityWindowInfo window : windows) {
                    Rect bounds = new Rect();
                    window.getBoundsInScreen(bounds);
                    JSONObject entry = new JSONObject().put("windowId", window.getId())
                            .put("displayId", window.getDisplayId()).put("type", window.getType())
                            .put("layer", window.getLayer()).put("active", window.isActive())
                            .put("focused", window.isFocused()).put("boundsInScreen", rect(bounds));
                    selected.put(entry);
                    JSONArray nodes = new JSONArray();
                    entry.put("actionableNodes", nodes);
                    AccessibilityNodeInfo root = window.getRoot();
                    entry.put("rootAvailable", root != null);
                    if (root == null) continue;
                    PackageNodeWalk.Counts counts = PackageNodeWalk.collect(new AndroidNode(root), targetPackage,
                            (item, ordinal) -> {
                                AccessibilityNodeInfo node = ((AndroidNode) item).node;
                                JSONArray actions = new JSONArray();
                                boolean actionable = node.isClickable() || node.isLongClickable();
                                for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
                                    int id = action.getId();
                                    actions.put(id); // Deliberately do not read custom action labels.
                                    actionable |= id == AccessibilityNodeInfo.ACTION_CLICK
                                            || id == AccessibilityNodeInfo.ACTION_LONG_CLICK
                                            || id == AccessibilityNodeInfo.AccessibilityAction.ACTION_PRESS_AND_HOLD.getId();
                                }
                                if (!actionable) return;
                                Rect screen = new Rect();
                                node.getBoundsInScreen(screen);
                                nodes.put(new JSONObject().put("observedNodeOrdinal", ordinal)
                                        .put("windowId", node.getWindowId())
                                        .put("viewIdResourceName", nullable(node.getViewIdResourceName()))
                                        .put("className", nullable(node.getClassName()))
                                        .put("boundsInScreen", rect(screen)).put("actionIds", actions)
                                        .put("clickable", node.isClickable()).put("longClickable", node.isLongClickable())
                                        .put("enabled", node.isEnabled()).put("visibleToUser", node.isVisibleToUser()));
                            });
                    entry.put("selectedPackageNodesVisited", counts.selectedNodes);
                    entry.put("excludedPackageRoots", counts.excludedPackageRoots);
                    entry.put("nullChildren", counts.nullChildren);
                }
            }
        } finally {
            for (int i = 0; i < all.size(); i++)
                for (AccessibilityWindowInfo window : all.valueAt(i)) window.recycle();
        }
    }

    private static final class AndroidNode implements PackageNodeWalk.Node {
        final AccessibilityNodeInfo node;
        AndroidNode(AccessibilityNodeInfo node) { this.node = node; }
        public String packageName() { return node.getPackageName() == null ? null : node.getPackageName().toString(); }
        public int childCount() { return node.getChildCount(); }
        public PackageNodeWalk.Node child(int index) {
            AccessibilityNodeInfo child = node.getChild(index);
            return child == null ? null : new AndroidNode(child);
        }
        public void close() { node.recycle(); }
    }

    private static Object nullable(CharSequence value) { return value == null ? JSONObject.NULL : value.toString(); }
    private static JSONArray rect(Rect value) {
        return new JSONArray().put(value.left).put(value.top).put(value.right).put(value.bottom);
    }
    private static void putError(JSONObject report, String key, Exception error) {
        try { report.put(key, error.getClass().getName()); } catch (Exception ignored) { /* constant key */ }
    }
}
