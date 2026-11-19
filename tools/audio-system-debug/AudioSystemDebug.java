import java.lang.reflect.Method;

/**
 * Root helper used by quasar.rubcut.openio to change the vendor audio policy
 * from app_process (the app itself cannot call hidden AudioSystem APIs).
 *
 * It marks the BLE "Omni Remote" voice input as disconnected so AudioPolicy
 * falls back to the physical built-in microphone; otherwise RcuVoiceServer
 * returns -1 with no BLE remote streaming and AudioFlinger emits silence.
 *
 * Build (paths from this machine):
 *   AJ="$ANDROID_SDK_ROOT/platforms/android-34/android.jar"
 *   javac -source 8 -target 8 -cp "$AJ" -d out AudioSystemDebug.java
 *   d8 --lib "$AJ" --output out out/AudioSystemDebug.class
 *   (cd out && jar cf audio-system-debug.jar classes.dex)
 *   cp audio-system-debug.jar \
 *       app/src/main/assets/audio/audio-system-debug.jar
 *
 * Run:
 *   CLASSPATH=/data/local/tmp/audio-system-debug.jar \
 *       app_process /system/bin AudioSystemDebug 0x88000000 0 remote4 OmniRemote
 */
public class AudioSystemDebug {
    public static void main(String[] args) throws Exception {
        Class<?> audioSystem = Class.forName("android.media.AudioSystem");
        if (args.length < 2) {
            for (Method method : audioSystem.getMethods()) {
                String name = method.getName().toLowerCase();
                if (name.contains("deviceconnection") || name.contains("forceuse")) {
                    System.out.println("method: " + method);
                }
            }
            return;
        }
        int device = (int) Long.decode(args[0]).longValue();
        int state = Integer.parseInt(args[1]);
        String address = args.length > 2 ? args[2] : "";
        String name = args.length > 3 ? args[3] : "";
        for (Method method : audioSystem.getMethods()) {
            if (!method.getName().equals("setDeviceConnectionState")) continue;
            Class<?>[] types = method.getParameterTypes();
            System.out.println("calling: " + method);
            if (types.length == 4) {
                method.invoke(null, device, state, address, name);
            } else if (types.length == 5 && types[4] == int.class) {
                method.invoke(null, device, state, address, name, 0);
            } else {
                System.out.println("unsupported signature");
                continue;
            }
            System.out.println("ok");
            return;
        }
        System.out.println("setDeviceConnectionState not found");
    }
}