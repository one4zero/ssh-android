import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    kotlin("jvm") version "2.2.21"
}

// ---------------------------------------------------------------- configuration

val appDir: File = rootDir.resolve("../app").canonicalFile
val appProps = Properties().apply { appDir.resolve("version.properties").reader().use { load(it) } }

val applicationId = appProps.getProperty("applicationId")
val versionCode = appProps.getProperty("versionCode")
val versionName = appProps.getProperty("versionName")
val minSdk = appProps.getProperty("minSdk")
val targetSdk = appProps.getProperty("targetSdk")
val compileSdk = appProps.getProperty("compileSdk")

val sdkDir: File = run {
    val path = (findProperty("sdk.dir") as String?)
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: rootDir.resolve("../local.properties").takeIf { it.exists() }?.let { f ->
            Properties().apply { f.reader().use { load(it) } }.getProperty("sdk.dir")
        }
        ?: error("Android SDK not found: set ANDROID_HOME or sdk.dir in local.properties")
    File(path)
}
val androidJar: File = sdkDir.resolve("platforms/android-$compileSdk/android.jar")
val buildTools: File = (findProperty("buildTools") as String?)?.let { sdkDir.resolve("build-tools/$it") }
    ?: sdkDir.resolve("build-tools").listFiles()!!.filter { it.resolve("aapt2").exists() }.maxByOrNull { it.name }
    ?: error("No build-tools found in $sdkDir")

fun tool(name: String): String = buildTools.resolve(name).absolutePath

// ---------------------------------------------------------------- dependencies

dependencies {
    compileOnly(files(androidJar))
    implementation("com.github.mwiede:jsch:2.28.8")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")

    testImplementation("junit:junit:4.13.2")
    testCompileOnly(files(androidJar))
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

val buildDirFile: File = layout.buildDirectory.get().asFile
val generatedR = buildDirFile.resolve("generated/r")

sourceSets {
    main {
        java.srcDir(generatedR)
    }
}
kotlin.sourceSets["main"].kotlin.srcDir(appDir.resolve("src/main/java"))
kotlin.sourceSets["test"].kotlin.srcDir(appDir.resolve("src/test/java"))

// ---------------------------------------------------------------- resources

val compiledRes = buildDirFile.resolve("intermediates/res/compiled.zip")
val processedManifest = buildDirFile.resolve("intermediates/manifest/AndroidManifest.xml")
val resourcesApk = buildDirFile.resolve("intermediates/res/resources.ap_")
val aaptRules = buildDirFile.resolve("intermediates/res/aapt-rules.pro")

val compileResources by tasks.registering(Exec::class) {
    val resDir = appDir.resolve("src/main/res")
    inputs.dir(resDir)
    outputs.file(compiledRes)
    doFirst { compiledRes.parentFile.mkdirs() }
    commandLine(tool("aapt2"), "compile", "--dir", resDir.absolutePath, "-o", compiledRes.absolutePath)
}

val processManifest by tasks.registering {
    val src = appDir.resolve("src/main/AndroidManifest.xml")
    inputs.file(src)
    inputs.properties(mapOf("id" to applicationId, "vc" to versionCode, "vn" to versionName))
    outputs.file(processedManifest)
    doLast {
        val text = src.readText().replaceFirst(
            "<manifest ",
            "<manifest package=\"$applicationId\" android:versionCode=\"$versionCode\" " +
                "android:versionName=\"$versionName\" ",
        )
        processedManifest.parentFile.mkdirs()
        processedManifest.writeText(text)
    }
}

val linkResources by tasks.registering(Exec::class) {
    dependsOn(compileResources, processManifest)
    inputs.file(compiledRes)
    inputs.file(processedManifest)
    outputs.file(resourcesApk)
    outputs.file(aaptRules)
    outputs.dir(generatedR)
    doFirst {
        generatedR.deleteRecursively()
        generatedR.mkdirs()
    }
    commandLine(
        tool("aapt2"), "link",
        "-I", androidJar.absolutePath,
        "--manifest", processedManifest.absolutePath,
        "--min-sdk-version", minSdk,
        "--target-sdk-version", targetSdk,
        "--version-code", versionCode,
        "--version-name", versionName,
        "--java", generatedR.absolutePath,
        "--proguard", aaptRules.absolutePath,
        "--auto-add-overlay",
        "-o", resourcesApk.absolutePath,
        compiledRes.absolutePath,
    )
}

tasks.named("compileKotlin") { dependsOn(linkResources) }
tasks.named("compileJava") { dependsOn(linkResources) }

// ---------------------------------------------------------------- dex (R8)

val dexDir = buildDirFile.resolve("intermediates/dex")

val dex by tasks.registering(JavaExec::class) {
    dependsOn(tasks.jar, linkResources)
    val appJar = tasks.jar.flatMap { it.archiveFile }
    val runtime = configurations.runtimeClasspath.get()
    val rules = appDir.resolve("proguard-rules.pro")
    inputs.file(appJar)
    inputs.files(runtime)
    inputs.file(rules)
    inputs.file(aaptRules)
    outputs.dir(dexDir)
    classpath = files(buildTools.resolve("lib/d8.jar"))
    mainClass.set("com.android.tools.r8.R8")
    maxHeapSize = "2g"
    doFirst {
        dexDir.deleteRecursively()
        dexDir.mkdirs()
    }
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "--release",
            "--min-api", minSdk,
            "--lib", androidJar.absolutePath,
            "--output", dexDir.absolutePath,
            "--pg-conf", rules.absolutePath,
            "--pg-conf", aaptRules.absolutePath,
            "--pg-map-output", buildDirFile.resolve("outputs/mapping.txt").absolutePath,
            appJar.get().asFile.absolutePath,
        ) + runtime.map { it.absolutePath }
    })
}

// ---------------------------------------------------------------- package & sign

val unsignedApk = buildDirFile.resolve("intermediates/apk/unsigned.apk")
val alignedApk = buildDirFile.resolve("intermediates/apk/aligned.apk")
val outputApk = buildDirFile.resolve("outputs/ssh-term-$versionName.apk")

val packageApk by tasks.registering {
    dependsOn(dex, linkResources)
    inputs.file(resourcesApk)
    inputs.dir(dexDir)
    outputs.file(unsignedApk)
    doLast {
        unsignedApk.parentFile.mkdirs()
        ZipOutputStream(unsignedApk.outputStream().buffered()).use { out ->
            ZipFile(resourcesApk).use { zip ->
                for (entry in zip.entries()) {
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val e = ZipEntry(entry.name)
                    if (entry.method == ZipEntry.STORED) {
                        e.method = ZipEntry.STORED
                        e.size = bytes.size.toLong()
                        e.compressedSize = bytes.size.toLong()
                        e.crc = CRC32().apply { update(bytes) }.value
                    }
                    out.putNextEntry(e)
                    out.write(bytes)
                    out.closeEntry()
                }
            }
            dexDir.listFiles { f -> f.name.endsWith(".dex") }!!.sortedBy { it.name }.forEach { f ->
                out.putNextEntry(ZipEntry(f.name))
                out.write(f.readBytes())
                out.closeEntry()
            }
        }
    }
}

val keystoreFile: File = (findProperty("signing.storeFile") as String?)?.let { File(it) }
    ?: rootDir.resolve("keystore/local-release.jks")
val keystorePassword: String = (findProperty("signing.storePassword") as String?) ?: "sshterm"
val keyAlias: String = (findProperty("signing.keyAlias") as String?) ?: "sshterm"
val keyPassword: String = (findProperty("signing.keyPassword") as String?) ?: keystorePassword

val createKeystore by tasks.registering(Exec::class) {
    onlyIf { !keystoreFile.exists() }
    doFirst { keystoreFile.parentFile.mkdirs() }
    commandLine(
        "keytool", "-genkeypair", "-noprompt",
        "-keystore", keystoreFile.absolutePath,
        "-storepass", keystorePassword,
        "-keypass", keyPassword,
        "-alias", keyAlias,
        "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
        "-dname", "CN=SSH Term",
    )
}

val alignApk by tasks.registering(Exec::class) {
    dependsOn(packageApk)
    inputs.file(unsignedApk)
    outputs.file(alignedApk)
    commandLine(tool("zipalign"), "-p", "-f", "4", unsignedApk.absolutePath, alignedApk.absolutePath)
}

val assembleApk by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the signed release APK"
    dependsOn(alignApk, createKeystore)
    inputs.file(alignedApk)
    outputs.file(outputApk)
    doFirst { outputApk.parentFile.mkdirs() }
    commandLine(
        tool("apksigner"), "sign",
        "--ks", keystoreFile.absolutePath,
        "--ks-pass", "pass:$keystorePassword",
        "--key-pass", "pass:$keyPassword",
        "--ks-key-alias", keyAlias,
        "--out", outputApk.absolutePath,
        alignedApk.absolutePath,
    )
    doLast { println("APK: ${outputApk.absolutePath}") }
}
