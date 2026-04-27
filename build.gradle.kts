plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.test) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.aboutlibraries) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.buildkonfig) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
}

extra.apply {
    val upstreamVersionFile = file("version.txt")
    val forkVersionFile = file("version-fork.txt")
    val upstreamVerCode = upstreamVersionFile.readText().trim().toInt()
    val forkVerCode = forkVersionFile.readText().trim().toInt()
    val upstreamVerName = "${upstreamVerCode / 100}.${upstreamVerCode % 100}"

    set("UPSTREAM_VER_CODE", upstreamVerCode)
    set("UPSTREAM_VER_NAME", upstreamVerName)
    set("VER_CODE", forkVerCode)
    set("VER_NAME", "$upstreamVerName-etd.$forkVerCode")
    set("PACKAGE_VER_NAME", "$upstreamVerName.$forkVerCode")
    set("APP_ID", "dev.etorix.panoscrobbler")
    set("APP_NAME", "Pano Scrobbler ETD")
    set("APP_NAME_NO_SPACES", "pano-scrobbler-etd")
}
