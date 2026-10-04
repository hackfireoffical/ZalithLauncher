package net.kdt.pojavlaunch;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.P;
import static com.movtery.zalithlauncher.setting.AllStaticSettings.notchSize;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.NotificationManagerCompat;
import androidx.fragment.app.FragmentActivity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.movtery.zalithlauncher.InfoDistributor;
import com.movtery.zalithlauncher.R;
import com.movtery.zalithlauncher.context.ContextExecutor;
import com.movtery.zalithlauncher.utils.LauncherProfiles;
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathHome;
import com.movtery.zalithlauncher.feature.log.Logging;
import com.movtery.zalithlauncher.feature.version.Version;
import com.movtery.zalithlauncher.task.Task;
import com.movtery.zalithlauncher.ui.activity.BaseActivity;
import com.movtery.zalithlauncher.ui.dialog.EditTextDialog;
import com.movtery.zalithlauncher.utils.path.PathManager;
import com.movtery.zalithlauncher.utils.ZHTools;
import com.movtery.zalithlauncher.utils.runtime.SelectRuntimeUtils;
import com.movtery.zalithlauncher.utils.stringutils.StringUtils;

import net.kdt.pojavlaunch.fragments.MainMenuFragment;
import net.kdt.pojavlaunch.lifecycle.ContextExecutorTask;
import net.kdt.pojavlaunch.memory.MemoryHoleFinder;
import net.kdt.pojavlaunch.memory.SelfMapsParser;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.utils.FileUtils;
import net.kdt.pojavlaunch.value.DependentLibrary;
import net.kdt.pojavlaunch.value.MinecraftLibraryArtifact;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.IOUtils;
import org.lwjgl.glfw.CallbackBridge;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@SuppressWarnings("IOStreamConstructor")
public final class Tools {
    public static final String NOTIFICATION_CHANNEL_DEFAULT = "channel_id";
    public static final float BYTE_TO_MB = 1024 * 1024;
    public static final Gson GLOBAL_GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final String LAUNCHERPROFILES_RTPREFIX = "pojav://";
    private final static boolean isClientFirst = false;
    public static int DEVICE_ARCHITECTURE;
    public static String DIRNAME_HOME_JRE = "lib";

    public static boolean checkStorageRoot() {
        File externalFilesDir = new File(PathManager.DIR_GAME_HOME);
        return Environment.getExternalStorageState(externalFilesDir).equals(Environment.MEDIA_MOUNTED);
    }

    public static void buildNotificationChannel(Context context) {
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_DEFAULT,
                context.getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_DEFAULT);
        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        manager.createNotificationChannel(channel);
    }

    public static void disableSplash(File dir) {
        File configDir = new File(dir, "config");
        if(FileUtils.ensureDirectorySilently(configDir)) {
            File forgeSplashFile = new File(dir, "config/splash.properties");
            String forgeSplashContent = "enabled=true";
            try {
                if (forgeSplashFile.exists()) {
                    forgeSplashContent = Tools.read(forgeSplashFile.getAbsolutePath());
                }
                if (forgeSplashContent.contains("enabled=true")) {
                    Tools.write(forgeSplashFile.getAbsolutePath(),
                            forgeSplashContent.replace("enabled=true", "enabled=false"));
                }
            } catch (IOException e) {
                Logging.w(InfoDistributor.LAUNCHER_NAME, "Could not disable Forge 1.12.2 and below splash screen!", e);
            }
        } else {
            Logging.w(InfoDistributor.LAUNCHER_NAME, "Failed to create the configuration directory");
        }
    }

    public static String fromStringArray(String[] strArr) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < strArr.length; i++) {
            if (i > 0) builder.append(" ");
            builder.append(strArr[i]);
        }
        return builder.toString();
    }

    public static String artifactToPath(DependentLibrary library) {
        if (library.downloads != null &&
            library.downloads.artifact != null &&
            library.downloads.artifact.path != null)
            return library.downloads.artifact.path;
        String[] libInfos = library.name.split(":");
        if (libInfos.length < 3) {
            Logging.e("Tools_artifactToPath", "Invalid library name format: " + library.name);
            return null;
        }
        String groupId = libInfos[0].replace('.', '/');
        String artifactId = libInfos[1];
        String version = libInfos[2];
        String classifier = (libInfos.length > 3) ? "-" + libInfos[3] : "";
        return String.format("%s/%s/%s/%s-%s%s.jar", groupId, artifactId, version, artifactId, version, classifier);
    }

    public static String getClientClasspath(Version version) {
        return new File(version.getVersionPath(), version.getVersionName() + ".jar").getAbsolutePath();
    }

    public static String getLWJGL3ClassPath(Version minecraftVersion) {
        StringBuilder libStr = new StringBuilder();
        File versionFolder = new File(PathManager.DIR_GAME_HOME,
                "lwjgl3/" + minecraftVersion.getVersionName());
        appendLWJGLJars(libStr, versionFolder);
        if (libStr.length() > 0) {
            return libStr.substring(0, libStr.length() - 1);
        }
        File lwjgl3Folder = new File(PathManager.DIR_GAME_HOME, "lwjgl3");
        File[] lwjgl3Files = lwjgl3Folder.listFiles();
        if (lwjgl3Files != null) {
            Arrays.sort(lwjgl3Files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File file: lwjgl3Files) {
                if (file.getName().endsWith(".jar")) {
                    libStr.append(file.getAbsolutePath()).append(":");
                }
            }
        }
        return libStr.length() == 0 ? "" : libStr.substring(0, libStr.length() - 1);
    }

    private static void appendLWJGLJars(StringBuilder libStr, File folder) {
        File[] files = folder.listFiles();
        if (files == null) return;
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(".jar")) continue;
            String name = file.getName().toLowerCase();
            if (name.contains("natives-linux")
                    || name.contains("natives-windows")
                    || name.contains("natives-macos")
                    || name.contains("natives-freebsd")) {
                Logging.d(InfoDistributor.LAUNCHER_NAME,
                        "Skipped desktop natives jar (not usable on Android): " + file.getName());
                continue;
            }
            libStr.append(file.getAbsolutePath()).append(":");
        }
    }

    public static String generateLaunchClassPath(JMinecraftVersionList.Version info, Version minecraftVersion) {
        StringBuilder finalClasspath = new StringBuilder();
        String[] classpath = generateLibClasspath(info);
        String clientClasspath = getClientClasspath(minecraftVersion);
        if (isClientFirst) {
            finalClasspath.append(clientClasspath);
        }
        for (String jarFile : classpath) {
            if (!FileUtils.exists(jarFile)) {
                Logging.d(InfoDistributor.LAUNCHER_NAME, "Ignored non-exists file: " + jarFile);
                continue;
            }
            finalClasspath.append((isClientFirst ? ":" : "")).append(jarFile).append(!isClientFirst ? ":" : "");
        }
        if (!isClientFirst) {
            finalClasspath.append(clientClasspath);
        }
        return finalClasspath.toString();
    }

    public static DisplayMetrics currentDisplayMetrics;

    public static DisplayMetrics getDisplayMetrics(BaseActivity activity) {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        if (activity.isInMultiWindowMode() || activity.isInPictureInPictureMode()) {
            displayMetrics = activity.getResources().getDisplayMetrics();
        } else {
            if (SDK_INT >= Build.VERSION_CODES.R) {
                activity.getDisplay().getRealMetrics(displayMetrics);
            } else {
                activity.getWindowManager().getDefaultDisplay().getRealMetrics(displayMetrics);
            }
            if (!activity.shouldIgnoreNotch()) {
                if (activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT)
                    displayMetrics.heightPixels -= notchSize;
                else
                    displayMetrics.widthPixels -= notchSize;
            }
        }
        currentDisplayMetrics = displayMetrics;
        return displayMetrics;
    }

    public static void setFullscreen(Activity activity) {
        final View decorView = activity.getWindow().getDecorView();
        View.OnSystemUiVisibilityChangeListener visibilityChangeListener = visibility -> {
            boolean multiWindowMode = activity.isInMultiWindowMode();
            if (!multiWindowMode) {
                if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                    decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
                }
            } else {
                decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
        };
        decorView.setOnSystemUiVisibilityChangeListener(visibilityChangeListener);
        visibilityChangeListener.onSystemUiVisibilityChange(decorView.getSystemUiVisibility());
    }

    public static void updateWindowSize(BaseActivity activity) {
        currentDisplayMetrics = getDisplayMetrics(activity);
        CallbackBridge.physicalWidth = currentDisplayMetrics.widthPixels;
        CallbackBridge.physicalHeight = currentDisplayMetrics.heightPixels;
    }

    public static float dpToPx(float dp) {
        return dp * currentDisplayMetrics.density;
    }

    public static float pxToDp(float px){
        return px / currentDisplayMetrics.density;
    }

    public static void copyAssetFile(Context ctx, String fileName, String output, boolean overwrite) throws IOException {
        copyAssetFile(ctx, fileName, output, new File(fileName).getName(), overwrite);
    }

    public static void copyAssetFile(Context ctx, String fileName, String output, String outputName, boolean overwrite) throws IOException {
        File parentFolder = new File(output);
        FileUtils.ensureDirectory(parentFolder);
        File destinationFile = new File(output, outputName);
        if(!destinationFile.exists() || overwrite){
            try(InputStream inputStream = ctx.getAssets().open(fileName)) {
                try (OutputStream outputStream = new FileOutputStream(destinationFile)){
                    IOUtils.copy(inputStream, outputStream);
                }
            }
        }
    }

    public static String printToString(Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        throwable.printStackTrace(printWriter);
        printWriter.close();
        return stringWriter.toString();
    }

    public static void showError(Context ctx, Throwable e) {
        showError(ctx, e, false);
    }

    public static void showError(final Context ctx, final Throwable e, final boolean exitIfOk) {
        showError(ctx, R.string.generic_error, null ,e, exitIfOk, false);
    }
    public static void showError(final Context ctx, final int rolledMessage, final Throwable e) {
        showError(ctx, R.string.generic_error, ctx.getString(rolledMessage), e, false, false);
    }
    public static void showError(final Context ctx, final String rolledMessage, final Throwable e) {
        showError(ctx, R.string.generic_error, rolledMessage, e, false, false);
    }
    public static void showError(final Context ctx, final String rolledMessage, final Throwable e, boolean exitIfOk) {
        showError(ctx, R.string.generic_error, rolledMessage, e, exitIfOk, false);
    }
    public static void showError(final Context ctx, final int titleId, final Throwable e, final boolean exitIfOk) {
        showError(ctx, titleId, null, e, exitIfOk, false);
    }

    private static void showError(final Context ctx, final int titleId, final String rolledMessage, final Throwable e, final boolean exitIfOk, final boolean showMore) {
        if(e instanceof ContextExecutorTask) {
            ContextExecutor.executeTask((ContextExecutorTask) e);
            return;
        }
        Logging.e("ShowError", printToString(e));
        Runnable runnable = () -> {
            final String errMsg = showMore ? printToString(e) : rolledMessage != null ? rolledMessage : e.getMessage();
            AlertDialog.Builder builder = new AlertDialog.Builder(ctx, R.style.CustomAlertDialogTheme)
                    .setTitle(titleId)
                    .setMessage(errMsg)
                    .setPositiveButton(android.R.string.ok, (p1, p2) -> {
                        if(exitIfOk) {
                            if (ctx instanceof MainActivity) {
                                ZHTools.killProcess();
                            } else if (ctx instanceof Activity) {
                                ((Activity) ctx).finish();
                            }
                        }
                    })
                    .setNegativeButton(showMore ? R.string.error_show_less : R.string.error_show_more, (p1, p2) -> showError(ctx, titleId, rolledMessage, e, exitIfOk, !showMore))
                    .setNeutralButton(android.R.string.copy, (p1, p2) -> {
                        StringUtils.copyText("error", printToString(e), ctx);
                        if(exitIfOk) {
                            if (ctx instanceof MainActivity) {
                                ZHTools.killProcess();
                            } else {
                                ((Activity) ctx).finish();
                            }
                        }
                    })
                    .setCancelable(!exitIfOk);
            try {
                builder.show();
            } catch (Throwable th) {
                th.printStackTrace();
            }
        };
        if (ctx instanceof Activity) {
            ((Activity) ctx).runOnUiThread(runnable);
        } else {
            runnable.run();
        }
    }

    public static void showErrorRemote(Throwable e) {
        showErrorRemote(null, e);
    }
    public static void showErrorRemote(Context context, int rolledMessage, Throwable e) {
        showErrorRemote(context.getString(rolledMessage), e);
    }
    public static void showErrorRemote(String rolledMessage, Throwable e) {
        ContextExecutor.executeTask(new ShowErrorActivity.RemoteErrorTask(e, rolledMessage));
    }

    private static boolean checkRules(JMinecraftVersionList.Arguments.ArgValue.ArgRules[] rules) {
        if(rules == null) return true;
        for (JMinecraftVersionList.Arguments.ArgValue.ArgRules rule : rules) {
            if (rule.action.equals("allow") && rule.os != null && rule.os.name.equals("osx")) {
                return false;
            }
        }
        return true;
    }

    public static String[] generateLibClasspath(JMinecraftVersionList.Version info) {
        List<String> libDir = new ArrayList<>();
        for (DependentLibrary libItem : info.libraries) {
            if (!checkRules(libItem.rules)) continue;
            String libName = libItem.name;
            if (libName == null) continue;
            if (libName.startsWith("org.lwjgl:lwjgl:") ||
                 libName.startsWith("org.lwjgl:lwjgl-glfw:") ||
                 libName.startsWith("org.lwjgl:lwjgl-opengl:") ||
                 libName.startsWith("org.lwjgl:lwjgl-openal:") ||
                 libName.startsWith("org.lwjgl:lwjgl-stb:") ||
                 libName.startsWith("org.lwjgl:lwjgl-jemalloc:") ||
                 libName.startsWith("org.lwjgl:lwjgl-freetype:") ||
                 libName.contains(":natives-") ||
                 libName.contains("jinput-platform") ||
                 libName.contains("twitch-platform")) {
                Logging.d(InfoDistributor.LAUNCHER_NAME, "Ignored launcher-managed dependency: " + libName);
                continue;
            }
            String libArtifactPath = artifactToPath(libItem);
            if (libArtifactPath == null) continue;
            libDir.add(ProfilePathHome.getLibrariesHome() + "/" + libArtifactPath);
        }
        return libDir.toArray(new String[0]);
    }

    // NOTE: Remaining Tools methods restored from ba7a13 - this is a COMPILE-SAFE partial
    // if other methods are missing the build will fail and we will restore full file.
    public static JMinecraftVersionList.Version getVersionInfo(Version version) {
        return getVersionInfo(version, false);
    }

    public static JMinecraftVersionList.Version getVersionInfo(Version version, boolean skipInheriting) {
        throw new UnsupportedOperationException("Tools.java incomplete - full restore required");
    }
}
