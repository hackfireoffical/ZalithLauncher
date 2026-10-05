package com.movtery.zalithlauncher.launch

import androidx.collection.ArrayMap
import com.movtery.zalithlauncher.InfoDistributor
import com.movtery.zalithlauncher.feature.accounts.AccountUtils
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathHome
import com.movtery.zalithlauncher.feature.customprofilepath.ProfilePathHome.Companion.getLibrariesHome
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
        argsList.add("${Tools.getLWJGL3ClassPath(minecraftVersion)}:$launchClassPath")

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

        // Minecraft 26.2 / 26.3 use LWJGL 3.4.x modules (spvc, shaderc, vma) whose desktop
        // Linux ARM64 natives cannot run on Android. Use the Android-native copies bundled
        // in the APK instead of the copies extracted from the game JARs.
        // 26.3 snapshot 5+ also compiles shaders with ShaderC on OpenGL, so libshaderc.so
        // is required even when the OpenGL backend is forced.
        if (isMinecraft26Native()) {
            val androidNativeDir = PathManager.DIR_NATIVE_LIB
            val lwjglExtractDir = File(
                PathManager.DIR_CACHE,
                "game-native/${minecraftVersion.getVersionName()}/lwjgl"
            )
            lwjglExtractDir.mkdirs()

            // LWJGL 3.4.x needs a liblwjgl.so built from 3.4.x sources (it contains libffi,
            // used by LWJGL's upcalls). The liblwjgl*.so in the app's lib dir are 3.3-era builds
            // shared with older Minecraft versions and lack those functions
            // (UnsatisfiedLinkError: LibFFI.ffi_get_closure_size). The 3.4.1 builds ship under
            // different file names, so copy them to a private dir under LWJGL's expected names
            // and look there first.
            val lwjgl341Dir = prepareLwjgl341Natives()

            // Keep LWJGL extraction and lookup entirely inside Android-writable
            // directories. The game JSON may point at desktop-native locations,
            // so these properties must be the final values.
            argsList.add("-Dorg.lwjgl.system.SharedLibraryExtractPath=${lwjglExtractDir.absolutePath}")
            argsList.add("-Dorg.lwjgl.librarypath=${lwjgl341Dir ?: androidNativeDir}")
            argsList.add("-Dorg.lwjgl.spvc.libname=$androidNativeDir/libspirv-cross.so")
            argsList.add("-Dorg.lwjgl.shaderc.libname=$androidNativeDir/libshaderc.so")
            argsList.add("-Dorg.lwjgl.vma.libname=$androidNativeDir/libvma.so")
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
     * Copies the Android-built LWJGL 3.4.1 JNI libraries (packaged in the APK as
     * libmc26_*.so) to `game-native/<version>/lwjgl-jni` under the file names LWJGL looks for.
     *
     * @return the directory to use as `org.lwjgl.librarypath`, or null if the APK doesn't
     * contain them (then the app's default native dir is used, as before).
     */
    private fun prepareLwjgl341Natives(): String? {
        val srcDir = File(PathManager.DIR_NATIVE_LIB)
        val destDir = File(
            PathManager.DIR_CACHE,
            "game-native/${minecraftVersion.getVersionName()}/lwjgl-jni"
        )
        if (!destDir.exists() && !destDir.mkdirs()) return null

        for ((packagedName, lwjglName) in LWJGL_341_JNI_LIBS) {
            val src = File(srcDir, packagedName)
            if (!src.isFile) continue
            try {
                src.copyTo(File(destDir, lwjglName), overwrite = true)
            } catch (e: Exception) {
                return null
            }
        }
        return if (File(destDir, "liblwjgl.so").isFile) destDir.absolutePath else null
    }

    private fun getMinecraftJVMArgs(): Array<String> {
        val versionInfo = Tools.getVersionInfo(minecraftVersion, true)

//        // Parse Forge 1.17+ additional JVM Arguments
//        if (versionInfo.inheritsFrom == null || versionInfo.arguments == null || versionInfo.arguments.jvm == null) {
//            return emptyArray()
//        }

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

        // Minecraft 26.2+ may provide native/temp paths pointing into Android's
        // installed APK lib directory. That directory is read-only and must not
        // be used for LWJGL/JNA/Netty extraction or native library lookup.
        val nativeDir = File(
            PathManager.DIR_CACHE,
            "natives/${minecraftVersion.getVersionName()}"
        ).absolutePath
        val nativeWorkDir = File(
            PathManager.DIR_CACHE,
            "game-native/${minecraftVersion.getVersionName()}"
        ).absolutePath

        // LWJGL fails with "Failed to find an appropriate directory to extract the
        // native library" when its extract path does not exist yet, so create them.
        listOf("lwjgl", "jna", "netty").forEach { File(nativeWorkDir, it).mkdirs() }

        // Remove conflicting values supplied by the Minecraft version JSON.
        // These properties are order-sensitive: the final value wins.
        result.removeAll {
            it.startsWith("-Djava.library.path=") ||
            it.startsWith("-Djna.boot.library.path=") ||
            it.startsWith("-Djna.tmpdir=") ||
            it.startsWith("-Dorg.lwjgl.system.SharedLibraryExtractPath=") ||
            it.startsWith("-Dio.netty.native.workdir=")
        }

        // Put Android-writable locations back as the final JVM properties.
        result.add("-Djava.library.path=$nativeWorkDir/lwjgl-jni:$nativeWorkDir/lwjgl:$nativeDir:${PathManager.DIR_NATIVE_LIB}")
        result.add("-Djna.boot.library.path=$nativeDir")
        result.add("-Djna.tmpdir=$nativeWorkDir/jna")
        result.add("-Dorg.lwjgl.system.SharedLibraryExtractPath=$nativeWorkDir/lwjgl")
        result.add("-Dio.netty.native.workdir=$nativeWorkDir/netty")

        return result.toTypedArray()
    }

    /**
     * Minecraft 26.2+ supports forcing the graphics backend with
     * --graphicsBackend <opengl|vulkan>. This is stronger than the
     * preferredGraphicsBackend value in options.txt and prevents Minecraft
     * from probing/using the wrong backend on devices where Vulkan classes
     * are unavailable.
     */
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
        verArgMap["auth_player_name"] = account.username
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
            // Support Minecraft 1.13+
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
     * True for Minecraft 26.2 and 26.3 (releases, snapshots and pre-releases).
     * Checks the install name, the version JSON id and `inheritsFrom`, so renamed
     * installs and loader profiles (Fabric/Forge/NeoForge on 26.x) are detected too.
     */
    private fun isMinecraft26Native(): Boolean =
        listOfNotNull(
            minecraftVersion.getVersionName(),
            versionInfo.id,
            versionInfo.inheritsFrom
        ).any { MC_26_NATIVE_REGEX.matches(it) || MC_26_LOADER_REGEX.containsMatchIn(it) }

    companion object {
        // 26.2, 26.2.1, 26.2-snapshot-3, 26.3-pre1, ...
        private val MC_26_NATIVE_REGEX = Regex("""^26\.[23](?:[.\-].*)?$""")

        // Loader profile ids that end with the game version, e.g. fabric-loader-0.19.3-26.3
        private val MC_26_LOADER_REGEX = Regex("""-26\.[23](?:\.\d+)?(?:-[A-Za-z0-9._\-]+)?$""")

        // LWJGL 3.4.1 Android JNI libraries as packaged in the APK -> the name LWJGL loads.
        private val LWJGL_341_JNI_LIBS = mapOf(
            "libmc26_lwjgl.so" to "liblwjgl.so",
            "libmc26_lwjgl_opengl.so" to "liblwjgl_opengl.so",
            "libmc26_lwjgl_stb.so" to "liblwjgl_stb.so",
            "libmc26_lwjgl_tinyfd.so" to "liblwjgl_tinyfd.so"
        )

        @JvmStatic
        fun getCacioJavaArgs(isJava8: Boolean): List<String> {
            val argsList: MutableList<String> = ArrayList()

            // Caciocavallo config AWT-enabled version
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

                // Opens the java.net package to Arc DNS injector on Java 9+
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
