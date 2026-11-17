package dev.etorix.panoscrobbler

interface VariantStuffInterface {
    val githubApiUrl: String?
    val isNonPlay: Boolean
        get() = true
}