plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.autocrop.album"
    compileSdk = 34

    // Android 15 兼容性：允许不安全的 native 代码执行（仅用于个人工具）
    packagingOptions {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    defaultConfig {
        applicationId = "com.autocrop.album"
        minSdk = 24
        targetSdk = 33  // 降低到 33 避免 Android 15 兼容性问题
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters.add("arm64-v8a")
        }

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // Material Design 3
    implementation("com.google.android.material:material:1.11.0")

    // ViewPager2 for image swiping
    implementation("androidx.viewpager2:viewpager2:1.0.0")

    // RecyclerView for gallery
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Lifecycle components
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Glide for image loading
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // MediaPipe for pose detection
    implementation("com.google.mediapipe:tasks-vision:0.10.9")

    // ExifInterface for preserving image metadata
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // CardView
    implementation("androidx.cardview:cardview:1.0.0")
}
