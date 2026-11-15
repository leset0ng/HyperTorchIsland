plugins { id("com.android.application") }

android {
    namespace = "io.github.leset0ng.hypertorchisland"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.leset0ng.hypertorchisland"
        minSdk = 33
        targetSdk = 37
        versionCode = providers.gradleProperty("torchIslandVersionCode").orElse("1").get().toInt()
        versionName = providers.gradleProperty("torchIslandVersionName").orElse("0.1.0").get()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        val keystorePath = providers.environmentVariable("TORCH_ISLAND_KEYSTORE_PATH").orNull
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("TORCH_ISLAND_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("TORCH_ISLAND_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("TORCH_ISLAND_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Local builds without a release keystore stay installable with the debug key.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    testImplementation("io.github.libxposed:api:102.0.0")
    testImplementation("junit:junit:4.13.2")
}
