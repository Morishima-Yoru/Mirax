plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "me.trinitrix.mirax"
    compileSdk = 36

    defaultConfig {
        applicationId = "me.trinitrix.mirax"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "0.0.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        aidl = true
    }

    sourceSets {
        getByName("main") {
            // Shared shell-UID WFD beacon and helper entry (also packaged via helper/build.ps1).
            java.srcDir("${rootDir}/helper/src")
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

    // DataStore and RxJava3 for ConnectionHistory
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.datastore:datastore-preferences-rxjava3:1.1.1")
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
