package com.movtery.zalithlauncher.launch

import androidx.collection.ArrayMap
import com.movtery.zalithlauncher.InfoDistributor
import com.movtery.zalithlauncher.feature.accounts.AccountUtils
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathHome
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathHome.Companion.getLibrariesHome
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.feature.version.Version
import com.movtery.zalithlauncher.setting.AllSettings
import com.movtery.zalithlauncher.utils.ZHTools
import com.movtery.zalithlauncher.utils.path.LibPath
import com.movtery.zalithlauncher.utils.path.PathManager
import net.kdt.pojavlaunch.AWTCanvasView
import net.kdt.pojavlaunch.JMinecraftVersionList
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.multirt.Runtime
import net.kdt.pojavlaunch.utils.JSONUtils
import net.kdt.pojavlaunch.value.MinecraftAccount
import org.jackhuang.hmcl.util.versioning.VersionNumber
import java.io.File
import java.io.FileInputStream

class LaunchArgs(
    private val account: MinecraftAccount,
    private val gameDirPath: File,
    private val minecraftVersion: Version,
    private val versionInfo: JMinecraftVersionList.Version,
    private val versionFileName: String,
    private val runtime: Runtime,
    private val launchClassPath: String
) {
    fun getAllArgs(): List<String> {
        val argsList: MutableList<String> = ArrayList()

        argsList.addAll(getJavaArgs())
        argsList.addAll(getMinecraftJVMArgs())
        argsList.add("-cp")
        val lwjglCp = withAndroidGlfwBridge(Tools.getLWJGL3ClassPath(minecraftVersion))
        argsList.add("$lwjglCp:$launchClassPath")

        if (isMinecraft26Native()) {
            Logging.i(TAG, "MC 26.x LWJGL classpath (bridge first): $lwjglCp")
        }

        if (runtime.javaVersion > 8) {
            argsList.add("--add-exports")
            val pkg: String = versionInfo.mainClass.substring(0, versionInfo.mainClass.lastIndexOf("."))
            argsList.add("$pkg/$pkg=ALL-UNNAMED")
        }

        argsList.add(versionInfo.mainClass)
        argsList.addAll(getGraphicsBackendArgs())
        argsList.addAll(getMinecraftClientArgs())

        return argsList
    }

    /**
     * Minecraft 26.x's per-version LWJGL stack contains the official LWJGL 3.4.1 GLFW classes,
     * which call into a real libglfw.so. On Android there is no such library: the launcher
     * implements GLFW itself (a Java GLFW that talks to libpojavexec.so).
     *
     * Put that bridge in front so its org.lwjgl.glfw.GLFW wins over the official one.
     *
     * IMPORTANT: use lwjgl-glfw-classes-26.jar here, NOT lwjgl-glfw-classes.jar. The legacy jar
     * bundles an entire LWJGL 3.3.6 (org.lwjgl.system.Callback, MemoryUtil, ...). Coming first on
     * the classpath it shadows the 3.4.1 core, and its Callback calls the native
     * Callback.getCallbackHandler(Method) that the 3.4.1 liblwjgl.so does not have
     * (UnsatisfiedLinkError in GLFW.<clinit>). The -26 jar holds only the launcher's own classes.
     *
     * Tools.getLWJGL3ClassPath often still injects the legacy jar (and sometimes a second
     * copy of the -26 bridge). Strip every lwjgl-glfw-classes*.jar entry, then put exactly
     * one -26 bridge at the front.
     */
    private fun withAndroidGlfwBridge(lwjglClassPath: String): String {
        if (!isMinecraft26Native()) return lwjglClassPath

        val candidates = listOf(
            File(PathManager.DIR_GAME_HOME, "lwjgl3/$BRIDGE_26_JAR"),
            File(PathManager.DIR_DATA, "components/lwjgl3/$BRIDGE_26_JAR")
        )
        val bridge = candidates.firstOrNull { it.isFile }
        if (bridge == null) {
            Logging.e(TAG, "MC 26.x: $BRIDGE_26_JAR not found (looked in " +
                candidates.joinToString { it.absolutePath } + "). Rebuild the launcher with the " +
                "Android CI workflow; without it GLFW cannot work on Android.")
            return lwjglClassPath
        }

        val cleaned = lwjglClassPath
            .split(':')
            .filter { it.isNotEmpty() && !it.substringAfterLast('/').startsWith("lwjgl-glfw-classes") }
            .joinToString(":")

        return if (cleaned.isEmpty()) bridge.absolutePath
        else "${bridge.absolutePath}:$cleaned"
    }

    private fun getJavaArgs(): List<String> {
        val argsList: MutableList<String> = ArrayList()

        if (AccountUtils.isOtherLoginAccount(account)) {
            if (account.otherBaseUrl.contains("auth.mc-user.com")) {
                argsList.add("-javaagent:${LibPath.NIDE_8_AUTH.absolutePath}=${account.otherBaseUrl.replace("https://auth.mc-user.com:233/", "")}")
                argsList.add("-Dnide8auth.client=true")
            } else {
                argsList.add("-javaagent:${LibPath.AUTHLIB_INJECTOR.absolutePath}=${account.otherBaseUrl}")
            }
        }

        argsList.addAll(getCacioJavaArgs(runtime.javaVersion == 8))

        val is7 = VersionNumber.compare(VersionNumber.asVersion(versionInfo.id ?: "0.0").canonical, "1.12") < 0
        val configFilePath = if (is7) LibPath.LOG4J_XML_1_7 else LibPath.LOG4J_XML_1_12
        argsList.add("-Dlog4j.configurationFile=${configFilePath.absolutePath}")

        if (isMinecraft26Native()) {
            val androidNativeDir = PathManager.DIR_NATIVE_LIB
            val lwjglExtractDir = File(
                PathManager.DIR_CACHE,
                "game-native/${minecraftVersion.getVersionName()}/lwjgl"
            )
            lwjglExtractDir.mkdirs()

            val lwjgl341Dir = prepareLwjgl341Natives()

            if (lwjgl341Dir != null) {
                Logging.i(TAG, "MC 26.x: using prepared Android LWJGL 3.4.1 JNI dir: $lwjgl341Dir")
            } else {
                Logging.w(TAG, "MC 26.x: Android LWJGL 3.4.1 JNI natives missing or invalid " +
                    "(libmc26_*.so not present / not ELF). Falling back to $androidNativeDir. " +
                    "Expect UnsatisfiedLinkError on Callback/GLFW if the wrong liblwjgl.so is loaded.")
            }

            argsList.add("-Dorg.lwjgl.system.SharedLibraryExtractPath=${lwjglExtractDir.absolutePath}")
            argsList.add("-Dorg.lwjgl.librarypath=${lwjgl341Dir ?: androidNativeDir}")
            argsList.add("-Dorg.lwjgl.spvc.libname=$androidNativeDir/libspirv-cross.so")
            argsList.add("-Dorg.lwjgl.shaderc.libname=$androidNativeDir/libshaderc.so")
            argsList.add("-Dorg.lwjgl.vma.libname=$androidNativeDir/libvma.so")

            argsList.add("-XX:+ErrorFileToStdout")
            argsList.add("-Dorg.lwjgl.util.DebugLoader=true")
        }
        val versionSpecificNativesDir = File(PathManager.DIR_CACHE, "natives/${minecraftVersion.getVersionName()}")
        if (versionSpecificNativesDir.exists()) {
            val dirPath = versionSpecificNativesDir.absolutePath
            argsList.add("-Djava.library.path=$dirPath:${PathManager.DIR_NATIVE_LIB}")
            argsList.add("-Djna.boot.library.path=$dirPath")
        }

        return argsList
    }

    /**
     * Copies Android CI libmc26_*.so into game-native/<ver>/lwjgl-jni under the
     * names LWJGL expects. Returns that dir only when every required library is
     * a present, non-empty, valid ELF shared object; otherwise null so we never
     * point org.lwjgl.librarypath at "unknown type" junk that causes
     * UnsatisfiedLinkError on Callback.getCallbackHandler / GLFW init.
     */
    private fun prepareLwjgl341Natives(): String? {
        val srcDir = File(PathManager.DIR_NATIVE_LIB)
        val destDir = File(
            PathManager.DIR_CACHE,
            "game-native/${minecraftVersion.getVersionName()}/lwjgl-jni"
        )

        val sources = LWJGL_341_JNI_LIBS.map { (packagedName, lwjglName) ->
            Triple(File(srcDir, packagedName), File(destDir, lwjglName), lwjglName)
        }

        val invalid = sources.filter { (src, _, name) ->
            !src.isFile || src.length() < MIN_SO_BYTES || !isElfSharedObject(src)
        }
        if (invalid.isNotEmpty()) {
            Logging.w(TAG, "MC 26.x: missing or non-ELF libmc26_* sources: " +
                invalid.joinToString { it.third })
            return null
        }

        if (!destDir.exists() && !destDir.mkdirs()) {
            Logging.w(TAG, "MC 26.x: cannot create $destDir")
            return null
        }

        try {
            for ((src, dest, name) in sources) {
                src.copyTo(dest, overwrite = true)
                if (!dest.isFile || dest.length() < MIN_SO_BYTES || !isElfSharedObject(dest)) {
                    Logging.w(TAG, "MC 26.x: copy of $name produced invalid ELF, aborting")
                    destDir.deleteRecursively()
                    return null
                }
            }
        } catch (e: Exception) {
            Logging.w(TAG, "MC 26.x: failed to prepare LWJGL 3.4.1 natives", e)
            runCatching { destDir.deleteRecursively() }
            return null
        }

        val core = File(destDir, "liblwjgl.so")
        return if (core.isFile && core.length() >= MIN_SO_BYTES && isElfSharedObject(core)) {
            destDir.absolutePath
        } else {
            runCatching { destDir.deleteRecursively() }
            null
        }
    }

    /** True if the file starts with the ELF magic and is at least a plausible shared object. */
    private fun isElfSharedObject(file: File): Boolean {
        return try {
            FileInputStream(file).use { ins ->
                val magic = ByteArray(4)
                if (ins.read(magic) != 4) return false
                // 0x7F 'E' 'L' 'F'
                magic[0] == 0x7F.toByte() &&
                    magic[1] == 'E'.code.toByte() &&
                    magic[2] == 'L'.code.toByte() &&
                    magic[3] == 'F'.code.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun getMinecraftJVMArgs(): Array<String> {
        val versionInfo = Tools.getVersionInfo(minecraftVersion, true)

        val varArgMap: MutableMap<String, String?> = android.util.ArrayMap()
        varArgMap["classpath_separator"] = ":"
        varArgMap["library_directory"] = getLibrariesHome()
        varArgMap["version_name"] = versionInfo.id
        varArgMap["natives_directory"] = PathManager.DIR_NATIVE_LIB

        val minecraftArgs: MutableList<String> = java.util.ArrayList()
        versionInfo.arguments?.let {
            fun String.addIgnoreListIfHas(): String {
                if (startsWith("-DignoreList=")) return "$this,$versionFileName.jar"
                return this
            }
            it.jvm?.forEach { arg ->
                if (arg is String) {
                    minecraftArgs.add(arg.addIgnoreListIfHas())
                }
            }
        }
        val result = JSONUtils.insertJSONValueList(minecraftArgs.toTypedArray<String>(), varArgMap).toMutableList()

        val nativeDir = File(
            PathManager.DIR_CACHE,
            "natives/${minecraftVersion.getVersionName()}"
        ).absolutePath
        val nativeWorkDir = File(
            PathManager.DIR_CACHE,
            "game-native/${minecraftVersion.getVersionName()}"
        ).absolutePath

        listOf("lwjgl", "lwjgl-jni", "jna", "netty").forEach { File(nativeWorkDir, it).mkdirs() }

        result.removeAll {
            it.startsWith("-Djava.library.path=") ||
            it.startsWith("-Djna.boot.library.path=") ||
            it.startsWith("-Djna.tmpdir=") ||
            it.startsWith("-Dorg.lwjgl.system.SharedLibraryExtractPath=") ||
            it.startsWith("-Dorg.lwjgl.librarypath=") ||
            it.startsWith("-Dio.netty.native.workdir=")
        }

        val lwjglJni = File(nativeWorkDir, "lwjgl-jni")
        val libraryPathParts = buildList {
            if (File(lwjglJni, "liblwjgl.so").isFile && isElfSharedObject(File(lwjglJni, "liblwjgl.so"))) {
                add(lwjglJni.absolutePath)
            }
            add("$nativeWorkDir/lwjgl")
            add(nativeDir)
            add(PathManager.DIR_NATIVE_LIB)
        }
        result.add("-Djava.library.path=${libraryPathParts.joinToString(":")}")
        result.add("-Djna.boot.library.path=$nativeDir")
        result.add("-Djna.tmpdir=$nativeWorkDir/jna")
        result.add("-Dorg.lwjgl.system.SharedLibraryExtractPath=$nativeWorkDir/lwjgl")
        result.add("-Dio.netty.native.workdir=$nativeWorkDir/netty")

        return result.toTypedArray()
    }

    private fun getGraphicsBackendArgs(): List<String> {
        if (!isMinecraft26Native()) return emptyList()

        val selected = minecraftVersion.getVersionConfig().getGraphicsApi()
            .ifEmpty { AllSettings.graphicsApi.getValue() }
        return when (selected) {
            "prefer_opengl" -> listOf("--graphicsBackend", "opengl")
            "prefer_vulkan" -> listOf("--graphicsBackend", "vulkan")
            else -> emptyList()
        }
    }

    private fun getMinecraftClientArgs(): Array<String> {
        val verArgMap: MutableMap<String, String> = ArrayMap()
        verArgMap["auth_session"] = account.accessToken
        verArgMap["auth_access_token"] = account.accessToken
        // Minecraft protocol limits the username in ServerboundHelloPacket to 16 chars.
        // Offline accounts can be longer in the launcher UI; clamp here so singleplayer works.
        val playerName = account.username.let { if (it.length > 16) it.take(16) else it }
        if (playerName != account.username) {
            Logging.w(TAG, "Username '${account.username}' exceeds 16 chars; using '$playerName' for the game")
        }
        verArgMap["auth_player_name"] = playerName
        verArgMap["auth_uuid"] = account.profileId.replace("-", "")
        verArgMap["auth_xuid"] = account.xuid
        verArgMap["assets_root"] = ProfilePathHome.getAssetsHome()
        verArgMap["assets_index_name"] = versionInfo.assets
        verArgMap["game_assets"] = ProfilePathHome.getAssetsHome()
        verArgMap["game_directory"] = gameDirPath.absolutePath
        verArgMap["user_properties"] = "{}"
        verArgMap["user_type"] = "msa"
        verArgMap["version_name"] = versionInfo.inheritsFrom ?: versionInfo.id

        setLauncherInfo(verArgMap)

        val minecraftArgs: MutableList<String> = ArrayList()
        versionInfo.arguments?.apply {
            game.forEach { if (it is String) minecraftArgs.add(it) }
        }

        return JSONUtils.insertJSONValueList(
            splitAndFilterEmpty(
                versionInfo.minecraftArguments ?:
                Tools.fromStringArray(minecraftArgs.toTypedArray())
            ), verArgMap
        )
    }

    private fun setLauncherInfo(verArgMap: MutableMap<String, String>) {
        verArgMap["launcher_name"] = InfoDistributor.LAUNCHER_NAME
        verArgMap["launcher_version"] = ZHTools.getVersionName()
        verArgMap["version_type"] = minecraftVersion.getCustomInfo()
            .takeIf { it.isNotEmpty() && it.isNotBlank() }
            ?: versionInfo.type
    }

    private fun splitAndFilterEmpty(arg: String): Array<String> {
        val list: MutableList<String> = ArrayList()
        arg.split(" ").forEach {
            if (it.isNotEmpty()) list.add(it)
        }
        return list.toTypedArray()
    }

    /**
     * True when this launch targets Minecraft 26.2 / 26.3 (vanilla or any loader profile).
     *
     * Custom-named profiles (e.g. "OptiMobile (Fabric)") do not match ^26\.[23] on the
     * folder name. The real game version is stored in VersionInfo.minecraftVersion and
     * usually also in versionInfo.inheritsFrom / versionInfo.id (fabric-loader-*-26.2).
     */
    private fun isMinecraft26Native(): Boolean {
        val zalithInfo = minecraftVersion.getVersionInfo()?.minecraftVersion
        val candidates = listOfNotNull(
            zalithInfo,
            minecraftVersion.getVersionName(),
            versionInfo.id,
            versionInfo.inheritsFrom
        )
        val hit = candidates.any { c ->
            MC_26_NATIVE_REGEX.matches(c) || MC_26_LOADER_REGEX.containsMatchIn(c)
        }
        if (!hit) {
            Logging.d(TAG, "isMinecraft26Native=false candidates=$candidates")
        }
        return hit
    }

    companion object {
        private const val TAG = "LaunchArgs"
        private const val MIN_SO_BYTES = 1024L

        /** Launcher-only GLFW bridge for 26.x (built by jre_lwjgl3glfw:bridgeJar26). */
        private const val BRIDGE_26_JAR = "lwjgl-glfw-classes-26.jar"

        // 26.2, 26.2.1, 26.2-snapshot-3, 26.3-pre1, ...
        private val MC_26_NATIVE_REGEX = Regex("""^26\.[23](?:[.\-].*)?$""")

        // Loader profile ids that end with the game version, e.g. fabric-loader-0.19.3-26.3
        private val MC_26_LOADER_REGEX = Regex("""-26\.[23](?:\.\d+)?(?:-[A-Za-z0-9._\-]+)?$""")

        // LWJGL 3.4.1 Android JNI libraries from Android CI (Mojo unilwjgl3-builder).
        private val LWJGL_341_JNI_LIBS = mapOf(
            "libmc26_lwjgl.so" to "liblwjgl.so",
            "libmc26_lwjgl_opengl.so" to "liblwjgl_opengl.so",
            "libmc26_lwjgl_stb.so" to "liblwjgl_stb.so",
            "libmc26_lwjgl_tinyfd.so" to "liblwjgl_tinyfd.so"
        )

        @JvmStatic
        fun getCacioJavaArgs(isJava8: Boolean): List<String> {
            val argsList: MutableList<String> = ArrayList()

            argsList.add("-Djava.awt.headless=false")
            argsList.add("-Dcacio.managed.screensize=" + AWTCanvasView.AWT_CANVAS_WIDTH + "x" + AWTCanvasView.AWT_CANVAS_HEIGHT)
            argsList.add("-Dcacio.font.fontmanager=sun.awt.X11FontManager")
            argsList.add("-Dcacio.font.fontscaler=sun.font.FreetypeFontScaler")
            argsList.add("-Dswing.defaultlaf=javax.swing.plaf.nimbus.NimbusLookAndFeel")
            if (isJava8) {
                argsList.add("-Dawt.toolkit=net.java.openjdk.cacio.ctc.CTCToolkit")
                argsList.add("-Djava.awt.graphicsenv=net.java.openjdk.cacio.ctc.CTCGraphicsEnvironment")
            } else {
                argsList.add("-Dawt.toolkit=com.github.caciocavallosilano.cacio.ctc.CTCToolkit")
                argsList.add("-Djava.awt.graphicsenv=com.github.caciocavallosilano.cacio.ctc.CTCGraphicsEnvironment")
                argsList.add("-javaagent:" + LibPath.CACIO_17_AGENT.getAbsolutePath())
                argsList.add("--add-exports=java.desktop/java.awt=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/java.awt.peer=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/sun.awt.image=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/sun.java2d=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/java.awt.dnd.peer=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/sun.awt=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/sun.awt.event=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/sun.awt.datatransfer=ALL-UNNAMED")
                argsList.add("--add-exports=java.desktop/sun.font=ALL-UNNAMED")
                argsList.add("--add-exports=java.base/sun.security.action=ALL-UNNAMED")
                argsList.add("--add-opens=java.base/java.util=ALL-UNNAMED")
                argsList.add("--add-opens=java.desktop/java.awt=ALL-UNNAMED")
                argsList.add("--add-opens=java.desktop/sun.font=ALL-UNNAMED")
                argsList.add("--add-opens=java.desktop/sun.java2d=ALL-UNNAMED")
                argsList.add("--add-opens=java.base/java.lang.reflect=ALL-UNNAMED")
                argsList.add("--add-opens=java.base/java.net=ALL-UNNAMED")
            }

            val cacioClassPath = StringBuilder()
            cacioClassPath.append("-Xbootclasspath/").append(if (isJava8) "p" else "a")
            val cacioFiles = if (isJava8) LibPath.CACIO_8 else LibPath.CACIO_17
            cacioFiles.listFiles()?.onEach {
                if (it.name.endsWith(".jar")) cacioClassPath.append(":").append(it.absolutePath)
            }

            argsList.add(cacioClassPath.toString())

            return argsList
        }
    }
}
