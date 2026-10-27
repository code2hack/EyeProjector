package android.content;
/** Permission observation shape for the product adapter; no grant or real package manager. */
public class Context {
    public int permissionResult;
    public RuntimeException permissionFault;
    public int permissionChecks;
    public String lastPermission;
    public Context getApplicationContext() { return this; }
    public int checkSelfPermission(String permission) {
        permissionChecks++;
        lastPermission = permission;
        if (permissionFault != null) throw permissionFault;
        return permissionResult;
    }
}
