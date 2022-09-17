
buildscript {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://oss.jfrog.org/artifactory/oss-snapshot-local")
    }
    dependencies {
        classpath("com.android.tools.build:gradle:7.0.0-beta04")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${Versions.kotlin}")
        classpath("org.jetbrains.kotlin:kotlin-serialization:${Versions.kotlin}")
        classpath("io.realm.kotlin:gradle-plugin:${Versions.realm}")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://kotlin.bintray.com/kotlinx/")
        maven(url = "https://oss.jfrog.org/artifactory/oss-snapshot-local")
    }
}


