import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import com.lagradost.cloudstream3.gradle.tasks.CompileDexTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) =
    extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo("Pablopinheiroo/ninjatv-telegram-repo")
        buildBranch = "main"
    }

    android {
        namespace = "com.pablopinheiroo.telegramninjaprobe"
        defaultConfig {
            minSdk = 23
            compileSdkVersion(35)
            targetSdk = 35
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations
        cloudstream("com.lagradost:cloudstream3:pre-release")
        implementation(kotlin("stdlib"))
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
        implementation("androidx.annotation:annotation:1.9.1")
    }

    // The CloudStream packager normally dexes Kotlin output. TDLib's generated
    // Java API must be included explicitly for JNI to be available in the plugin.
    tasks.withType<CompileDexTask>().configureEach {
        dependsOn("compileDebugJavaWithJavac")
        input.from(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes"))
    }
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
