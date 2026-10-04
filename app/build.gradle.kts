import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing is read from keystore.properties (gitignored, never committed). On a machine
// without the key file the release build is simply left unsigned instead of failing.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "org.jarsi.arkstore"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.jarsi.arkstore"
        minSdk = 26
        targetSdk = 37
        versionCode = 24
        versionName = "1.6.0-beta.2"

        // The GitHub account that is a source on every new installation.
        buildConfigField("String", "GITHUB_OWNER", "\"jrs8205\"")
        // The GitHub topic with which developers publish a repository to the store.
        buildConfigField("String", "STORE_TOPIC", "\"arkstore\"")
        // Where the list of published apps, rebuilt by the index workflow, is downloaded from.
        buildConfigField(
            "String",
            "INDEX_URL",
            "\"https://raw.githubusercontent.com/jrs8205/ARK-Store/index/index.json\""
        )
        // The list of automatically found apps, downloaded only by users who ask for them.
        buildConfigField(
            "String",
            "AUTO_INDEX_URL",
            "\"https://raw.githubusercontent.com/jrs8205/ARK-Store/index/auto.json\""
        )
        // Apps published to the store from somewhere else than GitHub, shown to everyone.
        buildConfigField(
            "String",
            "FORGE_INDEX_URL",
            "\"https://raw.githubusercontent.com/jrs8205/ARK-Store/index/forge.json\""
        )
        // Apps found by searching Codeberg, downloaded only by users who ask for them.
        buildConfigField(
            "String",
            "CODEBERG_INDEX_URL",
            "\"https://raw.githubusercontent.com/jrs8205/ARK-Store/index/codeberg.json\""
        )
        // Likewise for GitLab.
        buildConfigField(
            "String",
            "GITLAB_INDEX_URL",
            "\"https://raw.githubusercontent.com/jrs8205/ARK-Store/index/gitlab.json\""
        )
        // The lists of the other catalogues, each downloaded only by users who turn it on.
        buildConfigField(
            "String",
            "IZZY_INDEX_URL",
            "\"https://raw.githubusercontent.com/jrs8205/ARK-Store/index/izzy.json\""
        )
        buildConfigField(
            "String",
            "FDROID_INDEX_URL",
            "\"https://raw.githubusercontent.com/jrs8205/ARK-Store/index/fdroid.json\""
        )
        // The store's own repository, whose download count is shown in the app.
        buildConfigField("String", "STORE_REPO", "\"jrs8205/ARK-Store\"")
    }

    androidResources {
        localeFilters += listOf("en", "fi")
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    // Android's own org.json is not there in unit tests; this stands in for it.
    testImplementation(libs.json)
}
