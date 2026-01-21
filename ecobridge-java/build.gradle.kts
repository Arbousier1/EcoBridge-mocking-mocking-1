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
        // ASM 9.9.1: 支持 Java 25 字节码，确保 shadowJar 正常工作
        classpath("org.ow2.asm:asm-commons:9.9.1")
    }
}

plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.3.1"
    idea
}

group = "top.ellan"

// --- [版本号动态生成逻辑] ---
fun getGitHash(): String {
    return try {
        val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD").start()
        process.inputStream.bufferedReader().readText().trim()
    } catch (e: Exception) {
        "dev" // 如果没有 git 环境，显示 dev
    }
}

fun getBuildTime(): String {
    return SimpleDateFormat("yyyyMMdd").format(Date())
}

// 格式示例: 1.0.0-20260120-a1b2c
version = "1.0.0-${getBuildTime()}-${getGitHash()}"

println("Building EcoBridge version: $version")

// --- [jextract 自动化配置逻辑] ---
val rustHeaderFile = file("${projectDir}/../ecobridge-rust/ecobridge_rust.h")
val generatedSourceDir = layout.buildDirectory.dir("generated/sources/jextract")
val targetPackage = "top.ellan.ecobridge.gen"

fun findJextract(): String {
    val os = org.gradle.internal.os.OperatingSystem.current()
    val binaryName = if (os.isWindows) "jextract.bat" else "jextract"
    
    val envHome = System.getenv("JEXTRACT_HOME")
    if (!envHome.isNullOrBlank()) {
        val path = file("$envHome/bin/$binaryName")
        if (path.exists()) return path.absolutePath
    }

    val localPropsFile = file("local.properties")
    if (localPropsFile.exists()) {
        val props = Properties()
        localPropsFile.inputStream().use { props.load(it) }
        val localHome = props.getProperty("jextract.home")
        if (localHome != null) {
            val possiblePaths = listOf(
                file("$localHome/bin/$binaryName"), 
                file("$localHome/$binaryName")
            )
            for (path in possiblePaths) if (path.exists()) return path.absolutePath
        }
    }
    return binaryName 
}

    fun executableFound(name: String): Boolean {
        val f = file(name)
        if (f.exists()) return true
        val pathEnv = System.getenv("PATH") ?: return false
        val sep = File.pathSeparator
        val fileSep = File.separator
        pathEnv.split(sep).forEach { entry ->
            val candidate = File(entry + fileSep + name)
            if (candidate.exists()) return true
        }
        return false
    }

val generateBindings = tasks.register<Exec>("generateBindings") {
    group = "build"
    description = "使用 jextract 自动从 Rust 头文件生成 Java FFM 绑定。"
    doFirst {
        if (!rustHeaderFile.exists()) {
            throw GradleException("❌ 错误：未找到 Rust 头文件: ${rustHeaderFile.absolutePath}")
        }
        generatedSourceDir.get().asFile.mkdirs()
    }
        // 仅在找到 jextract 可执行文件时才执行；否则跳过并由 prepareGeneratedDir 创建占位目录
        onlyIf { executableFound(findJextract()) }
    isIgnoreExitValue = true
    commandLine(
        findJextract(),
        "--output", generatedSourceDir.get().asFile.absolutePath,
        "--target-package", targetPackage,
        "--header-class-name", "ecobridge_rust_h",
        rustHeaderFile.absolutePath
    )
    doLast {
        println("⚠️ 如果 jextract 不可用，将跳过绑定生成（如需绑定请安装配置 JEXTRACT_HOME）。")
        generatedSourceDir.get().asFile.mkdirs()
    }
    inputs.file(rustHeaderFile)
    outputs.dir(generatedSourceDir)
}

    // 始终准备生成目录（当 jextract 不可用或被跳过时代替生成空目录）
    val prepareGeneratedDir = tasks.register("prepareGeneratedDir") {
        doLast { generatedSourceDir.get().asFile.mkdirs() }
    }

idea {
    module {
        generatedSourceDirs.add(generatedSourceDir.get().asFile)
    }
}

// --- [Java 环境与工具链] ---
java {
    toolchain { 
        languageVersion.set(JavaLanguageVersion.of(25)) 
    }
}

sourceSets {
    main {
        java.srcDir(generateBindings)
    }
}

// 避免将位于 main 下的测试/兼容性检查类参与主编译（这些应在 src/test 里）
sourceSets {
    main {
        java {
            exclude("top/ellan/ecobridge/test/**")
        }
    }
}

// --- [仓库配置] ---
repositories {
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots/")
    maven("https://oss.sonatype.org/content/repositories/releases/")
    maven("https://jitpack.io")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.nightexpressdev.com/releases")
    maven("https://repo.lanink.cn/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://repo.momirealms.net/releases/")
    
    flatDir { dirs("libs") }
}

dependencies {
    // Paper & 核心依赖
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("su.nightexpress.nightcore:main:2.13.0")
    compileOnly("su.nightexpress.coinsengine:CoinsEngine:2.6.0")
    compileOnly("cn.superiormc.ultimateshop:plugin:4.2.3")
    
    // CraftEngine API 依赖
    compileOnly("net.momirealms:craft-engine-core:0.0.66") 
    compileOnly("net.momirealms:craft-engine-bukkit:0.0.66")

    // 本地库依赖
    compileOnly(fileTree(mapOf("dir" to "libs", "include" to listOf("**/*.jar"))))

    // 数据处理与数据库 (需打包进入 ShadowJar)
    implementation(platform("tools.jackson:jackson-bom:3.0.0"))
    implementation("tools.jackson.core:jackson-databind")
    implementation("tools.jackson.core:jackson-core")
    implementation("com.fasterxml.jackson.core:jackson-annotations")

    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.7")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.3")
    implementation("redis.clients:jedis:5.2.0")

    // ASM: 用于字节码重定向逻辑
    implementation("org.ow2.asm:asm:9.9.1")
    implementation("org.ow2.asm:asm-commons:9.9.1")
    
    // ByteBuddy Agent: 用于动态获取 Instrumentation 实例
    implementation("net.bytebuddy:byte-buddy-agent:1.15.10")

    compileOnly("com.google.code.gson:gson:2.11.0")

    // 测试框架
    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.withType<JavaCompile> {
    dependsOn(generateBindings, prepareGeneratedDir)
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.addAll(listOf(
        "-Xlint:unchecked",
        "-Xlint:-preview"
    ))
}

// --- [ShadowJar 配置] ---
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    val prefix = "top.ellan.ecobridge.libs"
    
    // MANIFEST.MF 配置: 允许插件作为 Java Agent 并执行类转换
    manifest {
        attributes(
            "Premain-Class" to "top.ellan.ecobridge.EcoBridge",
            "Agent-Class" to "top.ellan.ecobridge.EcoBridge",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true"
        )
    }

    // 重定向配置：防止与其他插件的依赖版本冲突
    relocate("tools.jackson", "$prefix.jackson")
    relocate("com.fasterxml.jackson.annotation", "$prefix.jackson.annotations")
    relocate("com.zaxxer.hikari", "$prefix.hikari")
    relocate("org.mariadb.jdbc", "$prefix.mariadb")
    relocate("com.github.benmanes.caffeine", "$prefix.caffeine")
    relocate("redis.clients", "$prefix.jedis")
    relocate("org.objectweb.asm", "$prefix.asm")
    relocate("net.bytebuddy", "$prefix.bytebuddy")
    
    // 打包 Rust 动态库文件
    from("src/main/resources") {
        include("*.dll", "*.so", "*.dylib", "natives/**")
    }
    
    mergeServiceFiles()
    
    minimize {
        exclude(dependency("org.mariadb.jdbc:.*"))
        exclude(dependency("com.github.ben-manes.caffeine:caffeine:.*"))
        // 确保 ByteBuddy 不被 minimize 误删
        exclude(dependency("net.bytebuddy:byte-buddy-agent:.*"))
    }
}

// --- [资源处理：版本号替换] ---
tasks.withType<ProcessResources> {
    // 1. 定义替换规则：将 YAML 中的 ${version} 替换为 project.version
    val props = mapOf("version" to project.version)
    inputs.properties(props)

    // 2. 设置字符集，防止中文注释乱码
    filteringCharset = "UTF-8"

    // 3. 仅对配置文件执行替换
    filesMatching(listOf("plugin.yml", "paper-plugin.yml")) {
        expand(props)
    }
}