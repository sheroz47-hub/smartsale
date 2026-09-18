plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "uz.smartsale.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "uz.smartsale.agent"
        // Android 7.0. Ниже опускаться незачем, а у агентов встречаются
        // очень простые аппараты — задирать планку тоже нельзя.
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Адрес сервера для отладки. Боевой задаётся на экране входа:
            // зашивать его в сборку значит пересобирать приложение при
            // каждом переезде сервера.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // Room выгружает схему в JSON при каждой сборке. Она нужна для миграций:
    // без снимка прежней схемы обновление приложения либо потребует стирать
    // базу, либо будет писаться вслепую. В базе лежат неотправленные заказы,
    // терять их нельзя.
    ksp { arg("room.schemaLocation", "$projectDir/schemas") }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.work.runtime.ktx)
    implementation(libs.datastore.preferences)

    // Сканер QR для онбординга агента (самодостаточный сканер + запрос камеры).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
