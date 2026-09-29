import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.jlz.presence"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.jlz.presence"
        minSdk = 26
        targetSdk = 35
        versionCode = 2026092923
        versionName = "0.4.12-cycle-font-layout-preview"
    }

    buildTypes {
        debug {
        }
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
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Pin Google Fonts source revision and Git blob SHA-1 for deterministic,
// offline-at-runtime typography. No font or personal data is fetched by the app.
// The files are downloaded only if/when an Android build is explicitly run.
val worldFontResDir = layout.buildDirectory.dir("generated/world-fonts/res")
val worldFontSourceRevision = "23e54b51ddffbc7713c583748e3bd86f62b1fa4a"
val worldFontSpecs = listOf(
    Triple("playfair_display.ttf", "playfairdisplay/PlayfairDisplay%5Bwght%5D.ttf", "7a09eb71f10622fd083134ac9ed46f59b21bee18"),
    Triple("great_vibes.ttf", "greatvibes/GreatVibes-Regular.ttf", "a1327ff3b862246f1f253639040edcc473e5f3a9"),
    Triple("inter.ttf", "inter/Inter%5Bopsz%2Cwght%5D.ttf", "047c92f6e2212473dc436020afed689527076d44"),
    Triple("noto_serif_sc.ttf", "notoserifsc/NotoSerifSC%5Bwght%5D.ttf", "eab063faf229160a52d3760f5555150e4eb9e5bf")
)
val prepareWorldFonts by tasks.registering {
    group = "resources"
    description = "Prepare pinned, OFL-licensed Playfair / Great Vibes / Inter / Noto Serif SC font resources."
    outputs.dir(worldFontResDir)
    doLast {
        val fontDir = worldFontResDir.get().asFile.resolve("font")
        fontDir.mkdirs()
        worldFontSpecs.forEach { (name, upstream, expectedGitBlobSha) ->
            val file = fontDir.resolve(name)
            if (file.exists()) return@forEach
            val url = URI(
                "https://raw.githubusercontent.com/google/fonts/$worldFontSourceRevision/ofl/$upstream"
            ).toURL()
            val bytes = url.openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 120_000
            }.getInputStream().use { it.readBytes() }
            val digest = MessageDigest.getInstance("SHA-1").apply {
                update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
                update(bytes)
            }.digest().joinToString("") { "%02x".format(it) }
            check(digest == expectedGitBlobSha) { "Font checksum mismatch: $name" }
            file.writeBytes(bytes)
        }
    }
}
android.sourceSets.getByName("main").res.srcDir(worldFontResDir)
// Gradle 9 validates all consumers of generated source directories.
// Variant tasks (resources, deeplinks, Kotlin and packaging) must depend
// explicitly on the font materialization; standalone clean/help do not.
tasks.matching {
    it.name.contains("Debug") || it.name.contains("Release")
}.configureEach { dependsOn(prepareWorldFonts) }
