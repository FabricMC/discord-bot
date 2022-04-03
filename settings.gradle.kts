rootProject.name = "thimble"

include("core")
include("tag-parser")

["automod", "fabricversion", "mapping", "mcversion", "tag", "test"].each {
    include(it)

    def module = project(":$it")
    module.projectDir = file("module/$it")
}
