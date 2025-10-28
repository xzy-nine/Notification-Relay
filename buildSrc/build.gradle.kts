plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    // 使用一个常见的 JGit 版本（会在 Maven Central 上寻找）；如需要可替换为更近的版本
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.2.0.202206071550-r")
}
