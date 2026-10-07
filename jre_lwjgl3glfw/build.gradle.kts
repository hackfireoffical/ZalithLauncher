plugins {
    java
}

group = "org.lwjgl.glfw"

configurations.getByName("default").isCanBeResolved = true

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveBaseName.set("lwjgl-glfw-classes")
    destinationDirectory.set(file("../ZalithLauncher/src/main/assets/components/lwjgl3/"))
    // Auto update the version with a timestamp so the project jar gets updated by Pojav
    doLast {
        val versionFile = file("../ZalithLauncher/src/main/assets/components/lwjgl3/version")
        versionFile.writeText(System.currentTimeMillis().toString())
    }
    from({
        configurations.getByName("default").map {
            println(it.name)
            if (it.isDirectory) it else zipTree(it)
        }
    })
    exclude("net/java/openjdk/cacio/ctc/**")
    // Replace LWJGL 3.4.1 desktop VMA loader with the Android-patched source.
    exclude("org/lwjgl/util/vma/LibVma.class")
    manifest {
        attributes("Manifest-Version" to "3.3.6")
        attributes("Automatic-Module-Name" to "org.lwjgl")
    }
}

// Minecraft 26.x bridge.
//
// The jar above bundles a complete LWJGL 3.3.6 Java stack (org.lwjgl.system.*, MemoryUtil,
// Callback, opengl, stb, ...). That is right for Minecraft <= 1.21 but fatal for 26.x: the
// per-version LWJGL 3.4.1 jars + 3.4.1 JNI natives are on the classpath too, and since the
// bridge comes first, its 3.3.6 org.lwjgl.system.Callback wins and calls the native method
// Callback.getCallbackHandler(Method) - which the 3.4.1 liblwjgl.so does not export (3.4.x
// uses Upcalls.getCallbackHandler). Result: UnsatisfiedLinkError while loading GLFW.
//
// So for 26.x ship ONLY the launcher's own classes (the Java GLFW bridge talking to
// libpojavexec.so, CallbackBridge, android.util stubs, patched LibVma, ...) and let every
// other org.lwjgl class come from the matching 3.4.1 jars.
val bridgeJar26 = tasks.register<Jar>("bridgeJar26") {
    group = "build"
    description = "Launcher-only GLFW bridge classes for Minecraft 26.x (no bundled LWJGL core)"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveFileName.set("lwjgl-glfw-classes-26.jar")
    destinationDirectory.set(file("../ZalithLauncher/src/main/assets/components/lwjgl3/"))
    from(sourceSets["main"].output)
    exclude("net/java/openjdk/cacio/ctc/**")
    doLast {
        // Guard: fail the CI build if LWJGL core classes ever leak back into this jar.
        val jarFile = archiveFile.get().asFile
        java.util.zip.ZipFile(jarFile).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            check("org/lwjgl/glfw/GLFW.class" in names) {
                "${jarFile.name}: org/lwjgl/glfw/GLFW.class is missing"
            }
            val leaked = names.filter {
                it.startsWith("org/lwjgl/system/") || it == "org/lwjgl/Version.class"
            }
            check(leaked.isEmpty()) {
                "${jarFile.name} must not contain LWJGL core classes (they would shadow 3.4.1): " +
                    leaked.take(10).joinToString()
            }
            println("${jarFile.name}: ${names.size} entries, no LWJGL core classes")
        }

        val versionFile = file("../ZalithLauncher/src/main/assets/components/lwjgl3/version")
        versionFile.writeText(System.currentTimeMillis().toString())
    }
}

tasks.named("assemble") {
    dependsOn(bridgeJar26)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
}
