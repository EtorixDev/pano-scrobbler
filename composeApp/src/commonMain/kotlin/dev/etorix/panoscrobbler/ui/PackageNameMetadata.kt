package dev.etorix.panoscrobbler.ui

expect object PackageNameMetadata {
    val PackageName.englishLabel: String?
    val PackageName.version: String?
}