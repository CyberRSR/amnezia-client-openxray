import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

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

        // Qt 6.10's showKeyboard() passes the API-30 callback directly to
        // View.setWindowInsetsAnimationCallback(). The Android-8 placeholder
        // above intentionally does not extend that unavailable API class, so
        // ART rejects QtInputDelegate even on newer devices. Keep the
        // API-26-safe placeholder and replace only showKeyboard() with the
        // legacy InputMethodManager overload (available since API 3).
        val patchedJar = qtAndroidJar.asFile.resolveSibling("${qtAndroidJar.asFile.name}.patched")
        JarFile(qtAndroidJar.asFile).use { inputJar ->
            JarOutputStream(patchedJar.outputStream().buffered()).use { outputJar ->
                val entries = inputJar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    outputJar.putNextEntry(JarEntry(entry.name))
                    val originalBytes = inputJar.getInputStream(entry).use { it.readBytes() }
                    if (entry.name == "org/qtproject/qt/android/QtInputDelegate.class") {
                        val reader = ClassReader(originalBytes)
                        val writer = ClassWriter(reader, 0)
                        reader.accept(object : ClassVisitor(Opcodes.ASM9, writer) {
                            override fun visitMethod(
                                access: Int,
                                name: String,
                                descriptor: String,
                                signature: String?,
                                exceptions: Array<out String>?
                            ): MethodVisitor? {
                                val visitor = super.visitMethod(
                                    access, name, descriptor, signature, exceptions
                                )
                                if (name != "showKeyboard"
                                    || descriptor != "(Landroid/app/Activity;IIIIII)V") {
                                    return visitor
                                }

                                val hasInputMethodManager = Label()
                                visitor.visitCode()
                                visitor.visitVarInsn(Opcodes.ALOAD, 0)
                                visitor.visitFieldInsn(
                                    Opcodes.GETFIELD,
                                    "org/qtproject/qt/android/QtInputDelegate",
                                    "m_imm",
                                    "Landroid/view/inputmethod/InputMethodManager;"
                                )
                                visitor.visitJumpInsn(Opcodes.IFNONNULL, hasInputMethodManager)
                                visitor.visitInsn(Opcodes.RETURN)
                                visitor.visitLabel(hasInputMethodManager)
                                visitor.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
                                visitor.visitVarInsn(Opcodes.ALOAD, 0)
                                visitor.visitFieldInsn(
                                    Opcodes.GETFIELD,
                                    "org/qtproject/qt/android/QtInputDelegate",
                                    "m_imm",
                                    "Landroid/view/inputmethod/InputMethodManager;"
                                )
                                visitor.visitVarInsn(Opcodes.ALOAD, 0)
                                visitor.visitFieldInsn(
                                    Opcodes.GETFIELD,
                                    "org/qtproject/qt/android/QtInputDelegate",
                                    "m_currentEditText",
                                    "Lorg/qtproject/qt/android/QtEditText;"
                                )
                                visitor.visitInsn(Opcodes.ICONST_0)
                                visitor.visitMethodInsn(
                                    Opcodes.INVOKEVIRTUAL,
                                    "android/view/inputmethod/InputMethodManager",
                                    "showSoftInput",
                                    "(Landroid/view/View;I)Z",
                                    false
                                )
                                visitor.visitInsn(Opcodes.POP)
                                visitor.visitInsn(Opcodes.RETURN)
                                visitor.visitMaxs(3, 8)
                                visitor.visitEnd()
                                return null
                            }
                        }, 0)
                        outputJar.write(writer.toByteArray())
                    } else {
                        outputJar.write(originalBytes)
                    }
                    outputJar.closeEntry()
                }
            }
        }
        Files.move(
            patchedJar.toPath(),
            qtAndroidJar.asFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )

        val libsXmlFile = generatedLibsXml.asFile
        if (libsXmlFile.isFile) {
            val original = libsXmlFile.readText()
            val lines = original.lineSequence()
                .filterNot {
                    it.contains(";ck-ovpn-plugin")
                        || it.contains(";wg-go")
                        || it.contains(";android8compat")
                }
                .toMutableList()
            val qtLibsIndex = lines.indexOfFirst { it.contains("<array name=\"qt_libs\">") }
            if (qtLibsIndex < 0)
                error("Missing qt_libs array in ${libsXmlFile.absolutePath}")

            // androiddeployqt classifies QT_ANDROID_EXTRA_LIBS as bundled libraries,
            // which Qt loads only after Qt6Core. Android 8 does not provide
            // getentropy(), so the compatibility library must be the first Qt
            // library and therefore visible while Qt6Core is loaded.
            lines.add(qtLibsIndex + 1, "        <item>armeabi-v7a;android8compat</item>")
            val patched = lines.joinToString(
                System.lineSeparator(),
                postfix = System.lineSeparator()
            )
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
