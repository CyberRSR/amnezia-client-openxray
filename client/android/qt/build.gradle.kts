plugins {
    id(libs.plugins.android.library.get().pluginId)
    id("property-delegate")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

val qtAndroidDir: String by gradleProperties
val qtMinSdkVersion: String by gradleProperties
val qtTargetAbiList: String by gradleProperties
val androidCompileSdkVersion: String by gradleProperties

val patchQtAndroid8Jar by tasks.registering {
    val sourceDir = layout.projectDirectory.dir("../compat/qt/android8")
    val generatedLibsXml = layout.projectDirectory.file("../res/values/libs.xml")
    val ckPluginLibrary = layout.projectDirectory.file("../libs/armeabi-v7a/libck-ovpn-plugin.so")
    val ckPluginStubLibrary = layout.projectDirectory.file("../../libck-ovpn-plugin.so")
    val wgGoLibrary = layout.projectDirectory.file("../libs/armeabi-v7a/libwg-go.so")
    val wgGoStagedLibrary = layout.projectDirectory.file("../../libwg-go.so")
    val qtAndroidJar = layout.projectDirectory.file("../libs/Qt6Android.jar")
    val classesDir = layout.buildDirectory.dir("qtAndroid8Compat/classes")

    onlyIf {
        qtTargetAbiList.split(",").contains("armeabi-v7a") && qtMinSdkVersion.toInt() <= 26
            && sourceDir.asFile.isDirectory && qtAndroidJar.asFile.isFile
    }

    outputs.upToDateWhen { false }

    doLast {
        val androidApi = androidCompileSdkVersion.substringAfter("android-")
        val sdkRoot = System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME")
            ?: error("ANDROID_SDK_ROOT or ANDROID_HOME must be set")
        val androidJar = file("$sdkRoot/platforms/android-$androidApi/android.jar")
        val outputDir = classesDir.get().asFile
        delete(outputDir)
        outputDir.mkdirs()

        exec {
            executable = javaToolchains.compilerFor {
                languageVersion.set(JavaLanguageVersion.of(17))
            }.get().executablePath.asFile.absolutePath
            val sourceFiles = fileTree(sourceDir).matching {
                include("**/*.java")
            }.files.sortedBy { it.absolutePath }
            args(
                "-source", "8",
                "-target", "8",
                "-classpath", "${qtAndroidJar.asFile}${File.pathSeparator}${androidJar}",
                "-d", outputDir
            )
            args(sourceFiles)
        }

        ant.withGroovyBuilder {
            "zip"(
                "destfile" to qtAndroidJar.asFile,
                "update" to true
            ) {
                "fileset"("dir" to outputDir) {
                    "include"("name" to "org/qtproject/qt/android/*.class")
                }
            }
        }

        val libsXmlFile = generatedLibsXml.asFile
        if (libsXmlFile.isFile) {
            val original = libsXmlFile.readText()
            val patched = original.lineSequence()
                .filterNot { it.contains(";ck-ovpn-plugin") || it.contains(";wg-go") }
                .joinToString(System.lineSeparator(), postfix = System.lineSeparator())
            if (patched != original)
                libsXmlFile.writeText(patched)
        }

        if (!ckPluginStubLibrary.asFile.isFile)
            error("Missing Android 8 ck-ovpn-plugin stub: ${ckPluginStubLibrary.asFile.absolutePath}")
        copy {
            from(ckPluginStubLibrary)
            into(ckPluginLibrary.asFile.parentFile)
        }

        if (!wgGoStagedLibrary.asFile.isFile)
            error("Missing Android 8 wg-go library: ${wgGoStagedLibrary.asFile.absolutePath}")
        copy {
            from(wgGoStagedLibrary)
            into(wgGoLibrary.asFile.parentFile)
        }
    }
}

android {
    namespace = "org.qtproject.qt.android.binding"

    sourceSets {
        getByName("main") {
            java.setSrcDirs(listOf("$qtAndroidDir/src"))
            res.setSrcDirs(listOf("$qtAndroidDir/res"))
        }
    }
}

dependencies {
    api(fileTree(mapOf("dir" to "../libs", "include" to listOf("*.jar"))))
}

tasks.configureEach {
    if (name == "preBuild" || name.endsWith("JavaWithJavac") || name.startsWith("compile")) {
        dependsOn(patchQtAndroid8Jar)
    }
}
