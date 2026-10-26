package me.trinitrix.mirax.shell;

import android.content.Context;
import android.os.Build;
import java.lang.reflect.Field;

/** Creates the shell-attributed Context shared by standalone helper lookup and WFD startup. */
public final class ShellContext {
    private ShellContext() {}

    /**
     * Bootstrap the framework Context once before sharing it with helper workers.
     *
     * <p>The app_process entry has no Application Context; non-SDK startup and attribution
     * dependencies remain isolated here while callers use public Context interfaces.
     *
     * @return system Context attributed to the existing shell package
     * @throws ReflectiveOperationException if the platform bootstrap or attribution is unavailable
     */
    public static Context create() throws ReflectiveOperationException {
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Object activityThread = activityThreadClass.getMethod("systemMain").invoke(null);
        Context context = (Context) activityThreadClass.getMethod("getSystemContext").invoke(activityThread);
        // Android 11 has only package-name attribution; retain the existing API31+ setup.
        if (Build.VERSION.SDK_INT >= 31) {
            Class<?> builderClass = Class.forName("android.content.AttributionSource$Builder");
            Object builder = builderClass.getConstructor(int.class).newInstance(2000);
            builderClass.getMethod("setPackageName", String.class).invoke(builder, "com.android.shell");
            Object shellSource = builderClass.getMethod("build").invoke(builder);
            setField(context, "mAttributionSource", shellSource);
        }
        setField(context, "mOpPackageName", "com.android.shell");
        setField(context, "mBasePackageName", "com.android.shell");
        return context;
    }

    /**
     * Set an existing framework attribution field, preserving bootstrap failure on incompatible ROMs.
     * @param target framework Context
     * @param name attribution field name
     * @param value shell attribution value
     * @throws ReflectiveOperationException if the field is missing or cannot be set
     */
    private static void setField(Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * Find the attribution field across the framework's Context inheritance hierarchy.
     * @param type concrete framework Context type
     * @param name field name
     * @return matching declared field
     * @throws NoSuchFieldException if no ancestor declares the field
     */
    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
