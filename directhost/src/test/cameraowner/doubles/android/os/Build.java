package android.os;
/** Host constants only; no device/API selection or compatibility proof. */
public final class Build {
    public static String FINGERPRINT = "HOST_MODEL_ONLY";
    public static final class VERSION { public static int SDK_INT = 36; }
    private Build() { }
}
