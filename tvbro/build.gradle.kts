plugins { id("com.android.library"); id("org.jetbrains.kotlin.android"); id("com.google.devtools.ksp") }
android {
    namespace = "com.phlox.tvwebbrowser"; compileSdk = 35
    defaultConfig {
        minSdk = 23; targetSdk = 35
        buildConfigField("Boolean", "BUILT_IN_AUTO_UPDATE", "false")
        buildConfigField("String", "FLAVOR_appstore", "\"generic\"")
        buildConfigField("String", "FLAVOR_webengine", "\"webview\"")
        buildConfigField("int", "VERSION_CODE", "69")
        buildConfigField("String", "VERSION_NAME", "\"2.1.6\"")
    }
    buildFeatures { viewBinding = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":tvbrocommon"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.github.truefedex:segmented-button:v1.0.0")
    implementation("de.halfbit:pinned-section-listview:1.0.0")
}
