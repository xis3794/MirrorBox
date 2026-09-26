pluginManagement {
    repositories {
        // Canonical repositories come FIRST on purpose: Gradle only falls through to the next
        // repository when a module is *not found* (404). A mirror that answers 5xx (Bad Gateway,
        // rate limit, outage) aborts resolution instead, which is exactly how CI broke once with
        // "Received status code 502 from server: Bad Gateway" on maven.aliyun.com. Mirrors are
        // therefore kept as a fallback only.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://repo.huaweicloud.com/repository/gradle-plugin/")
        maven("https://repo.huaweicloud.com/repository/maven/")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // See the note in pluginManagement above: canonical first, mirrors last.
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://repo.huaweicloud.com/repository/maven/")
    }
}

rootProject.name = "MirrorBox"
include(":app")
include(":qcow2")
 
