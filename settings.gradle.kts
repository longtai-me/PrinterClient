pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PrinterClient"

// Vendor SDK demo (reference implementation of the NYX printer AIDL service)
include(":app")

// Shared modules
include(":core-common")
include(":core-hardware")
include(":core-ui")
include(":core-auth")

// App 1: retail checkout POS
include(":pos-domain")
include(":pos-app")

// App 2: ticket redemption / access control
include(":ticket-domain")
include(":ticket-app")

// App 3: SMS auto-forward (device-owner controlled, transparent)
include(":sms-forward-app")
