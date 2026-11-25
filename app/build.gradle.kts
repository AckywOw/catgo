plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val identityDirectory = providers.gradleProperty("catgoIdentityDir").orNull?.let { file(it) }
    ?: rootProject.file("identity")
require(!identityDirectory.canonicalFile.toPath().startsWith(layout.buildDirectory.get().asFile.canonicalFile.toPath())) {
    "请将配件认证材料存放在 Gradle 构建输出目录之外"
}
for (name in listOf("identity.pk8", "certificate.p7b")) {
    require(identityDirectory.resolve(name).isFile && identityDirectory.resolve(name).length() > 0) {
        "配件认证材料目录 ${identityDirectory.absolutePath} 缺少文件 $name，或文件为空。请自行准备材料后重新构建。"
    }
}

android {
    namespace = "com.catgo"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.catgo"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }

dependencies {
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation("androidx.compose.foundation:foundation:1.7.8")
    implementation("androidx.compose.material3:material3:1.2.1")
}

androidComponents.onVariants { variant ->
    val stage = tasks.register<StageIdentity>("stage${variant.name.replaceFirstChar { it.uppercase() }}Identity") {
        key.set(identityDirectory.resolve("identity.pk8"))
        certificate.set(identityDirectory.resolve("certificate.p7b"))
        output.set(layout.buildDirectory.dir("generated/identity/${variant.name}"))
    }
    variant.sources.assets?.addGeneratedSourceDirectory(stage, StageIdentity::output)
}

abstract class StageIdentity : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val key: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val certificate: RegularFileProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun stage() {
        val directory = output.get().asFile.resolve("accessory")
        directory.mkdirs()
        key.get().asFile.copyTo(directory.resolve("identity.pk8"), overwrite = true)
        certificate.get().asFile.copyTo(directory.resolve("certificate.p7b"), overwrite = true)
    }
}
