import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.Properties
import java.text.SimpleDateFormat
import java.util.Date
import java.io.File

buildscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.ow2.asm:asm-commons:9.9.1")
    }
}

plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.3.1"
    idea
}

group = "top.ellan"

// --- [版本号动态生成] ---
fun getGitHash(): String = try {
    val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD").start()
    process.inputStream.bufferedReader().readText().trim()
} catch (e: Exception) { "dev" }

fun getBuildTime(): String = SimpleDateFormat("yyyyMMdd").format(Date())
version = "1.0.0-${getBuildTime()}-${getGitHash()}"

println("Building EcoBridge version: $version")

// --- [jextract 核心配置] ---
val rustHeaderFile = file("${projectDir}/../ecobridge-rust/ecobridge_rust.h")
// [修复] 明确生成的根目录
val genRoot = layout.buildDirectory.dir("generated/sources/jextract")
val targetPackage = "top.ellan.ecobridge.gen"

fun findJextract(): String {
    val os = org.gradle.internal.os.OperatingSystem.current()
    val binaryName = if (os.isWindows) "jextract.bat" else "jextract"
    val envHome = System.getenv("JEXTRACT_HOME")
    if (!envHome.isNullOrBlank()) {
        val path = file("$envHome/bin/$binaryName")
        if (path.exists()) return path.absolutePath
    }
    return binaryName 
}

fun executableFound(name: String): Boolean {
    if (file(name).exists()) return true
    return System.getenv("PATH")?.split(File.pathSeparator)?.any { File(it, name).exists() } ?: false
}

// [增强] 自动生成绑定任务
val generateBindings = tasks.register<Exec>("generateBindings") {
    group = "build"
    doFirst {
        if (!rustHeaderFile.exists()) throw GradleException("❌ 缺失 Rust 头文件: ${rustHeaderFile.absolutePath}")
        // 清理旧代码防止 duplicate class
        genRoot.get().asFile.deleteRecursively()
        genRoot.get().asFile.mkdirs()
    }
    
    val jextractBin = findJextract()
    onlyIf { executableFound(jextractBin) }
    
    commandLine(
        jextractBin,
        "--output", genRoot.get().asFile.absolutePath,
        "--target-package", targetPackage,
        "--header-class-name", "ecobridge_rust_h",
        rustHeaderFile.absolutePath
    )
    
    inputs.file(rustHeaderFile)
    outputs.dir(genRoot)
}

// --- [源码集配置] ---
sourceSets {
    main {
        java {
            // [关键修复] 将生成目录挂载到源码集
            srcDir(genRoot)
            // 排除手动误拷贝的测试类
            exclude("top/ellan/ecobridge/test/**")
        }
    }
}

// [关键修复] 强制 IDE 识别生成目录
idea {
    module {
        generatedSourceDirs.add(genRoot.get().asFile)
        isDownloadJavadoc = true
        isDownloadSources = true
    }
}

// --- [Java & 依赖] ---
java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.momirealms.net/releases/")
    maven("https://jitpack.io")
    flatDir { dirs("libs") }
}

dependencies {
    // 基础 API (不打包)
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly("cn.superiormc.ultimateshop:plugin:4.2.3")
    compileOnly("su.nightexpress.coinsengine:CoinsEngine:2.6.0")

    // 核心实现 (需打包)
    implementation("org.ow2.asm:asm:9.9.1")
    implementation("org.ow2.asm:asm-commons:9.9.1")
    implementation("net.bytebuddy:byte-buddy-agent:1.15.10")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("redis.clients:jedis:5.2.0")
    
    // 测试
    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.withType<JavaCompile> {
    // [修复] 确保编译前生成代码
    dependsOn(generateBindings)
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.add("--enable-preview") // 开启 FFM 预览特性（如果需要）
}

// --- [ShadowJar] ---
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    val prefix = "top.ellan.ecobridge.libs"
    
    manifest {
        attributes(
            "Premain-Class" to "top.ellan.ecobridge.EcoBridge",
            "Agent-Class" to "top.ellan.ecobridge.EcoBridge",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true"
        )
    }

    // 重定向冲突依赖
    relocate("org.objectweb.asm", "$prefix.asm")
    relocate("net.bytebuddy", "$prefix.bytebuddy")
    relocate("com.zaxxer.hikari", "$prefix.hikari")

    // 包含 Rust 库
    from("src/main/resources") { include("*.dll", "*.so", "*.dylib") }
    
    mergeServiceFiles()
    minimize {
        exclude(dependency("net.bytebuddy:byte-buddy-agent:.*"))
    }
}

// --- [资源处理] ---
tasks.withType<ProcessResources> {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching(listOf("plugin.yml", "paper-plugin.yml")) { expand(props) }
}