package org.jarsi.arkstore.data

/** A [StoreApp] for tests, with only the fields a test cares about spelled out. */
fun testApp(
    fullName: String = "owner/app",
    packageName: String? = "org.example",
    versionCode: Long = 1,
    prerelease: Boolean = false,
    auto: Boolean = false
) = StoreApp(
    fullName = fullName,
    description = "",
    stars = 0,
    category = Categories.OTHER,
    license = "MIT",
    downloads = 0,
    repoUrl = "https://github.com/$fullName",
    prerelease = prerelease,
    auto = auto,
    tag = "v$versionCode",
    releaseName = "",
    releaseNotes = "",
    releaseUrl = "",
    publishedAt = "",
    apkName = "app.apk",
    apkUrl = "https://example.invalid/app.apk",
    apkSize = 1,
    assetId = versionCode,
    packageName = packageName,
    versionCode = versionCode,
    versionName = null
)
