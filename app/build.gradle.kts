import java.security.MessageDigest

// Trial opt-in only: Shizuku-only privilege policy. Production default stays false.
val trialShizukuOnlyBuild: Boolean = providers.gradleProperty("miraxTrialShizukuOnly").orNull == "true"

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Bind original prototype bytes only into instrumentation assets; existing assets stay untouched.
val prototypeDesignDirectory = rootProject.layout.projectDirectory.dir("design")
val prototypeAssetPaths = providers.provider {
    val html = prototypeDesignDirectory.file("big-standby-prototype.html").asFile
    val references = Regex("""(?:src|href)=["']([^"']+\.(?:css|js)(?:\?[^"']*)?)["']""")
        .findAll(html.readText()).map { it.groupValues[1].substringBefore('?') }.toList()
    (listOf(html.name) + references).distinct().sorted().onEach { path ->
        require(path.matches(Regex("""[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*\.(?:html|css|js)"""))) {
            "Prototype asset must be an allowlisted relative source: $path"
        }
        require(prototypeDesignDirectory.file(path).asFile.isFile) { "Missing original design/$path" }
    }
}
val bindStandbyPrototypeAssets = tasks.register<Sync>("bindStandbyPrototypeAssets") {
    from(prototypeDesignDirectory) { include(prototypeAssetPaths.get()) }
    into(layout.buildDirectory.dir("generated/androidTestAssets/standbyPrototype/standby-prototype"))
    doLast {
        val hashes = prototypeAssetPaths.get().associateWith { path ->
            val original = prototypeDesignDirectory.file(path).asFile.readBytes()
            check(original.contentEquals(destinationDir.resolve(path).readBytes())) {
                "Instrumentation binding changed original design/$path"
            }
            MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }
        }
        destinationDir.resolve("sources.sha256.json").writeText(groovy.json.JsonOutput.toJson(hashes))
    }
}

tasks.configureEach {
    val prototypeConsumer = Regex("compile.*AndroidTestKotlin|merge.*AndroidTestAssets|generate.*AndroidTestLintModel|lintAnalyze.*AndroidTest")
    if (name.matches(prototypeConsumer)) {
        dependsOn(bindStandbyPrototypeAssets)
    }
}

android {
    namespace = "me.trinitrix.mirax"
    compileSdk = 36

    defaultConfig {
        applicationId = "me.trinitrix.mirax"
        minSdk = 34
        targetSdk = 34
        versionCode = 5
        versionName = "0.0.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "TRIAL_SHIZUKU_ONLY", trialShizukuOnlyBuild.toString())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Sideload / GitHub Release only; not a Play Store key.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = true
    }

    sourceSets {
        getByName("main") {
            // Shared shell-UID WFD beacon and helper entry (also packaged via helper/build.ps1).
            java.srcDir("${rootDir}/helper/src")
        }
        getByName("androidTest") {
            assets.srcDir(bindStandbyPrototypeAssets.map { it.destinationDir.parentFile })
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // Shizuku user-services extend Binder stubs, not android.app.Service.
        disable += "Instantiatable"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // DataStore for ConnectionHistory
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("io.reactivex.rxjava3:rxjava:3.1.6")
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.4")

    // Android Instrumentation Tests (E2E)
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("com.google.truth:truth:1.4.4")
    androidTestImplementation("junit:junit:4.13.2")
}
