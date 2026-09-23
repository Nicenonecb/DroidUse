pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "DroidUse"
include(":assistant", ":executor", ":ipc", ":runtime", ":system-api")
project(":assistant").projectDir = file("apps/assistant")
project(":executor").projectDir = file("platform/executor/app")
project(":ipc").projectDir = file("contracts/ipc")
project(":runtime").projectDir = file("platform/executor/runtime")
project(":system-api").projectDir = file("contracts/system-api")
include(":system-client")
project(":system-client").projectDir = file("contracts/system-client")

include(":agent")
project(":agent").projectDir = file("core/agent")

include(":paddleocr")
project(":paddleocr").projectDir = file("third_party/paddleocr")
