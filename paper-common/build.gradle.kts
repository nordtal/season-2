plugins {
    id("nordtal.paper-library")
    id("nordtal.message-spec")
}

dependencies {
    // `api`, since the Paper adapters here have NordtalUser on their signatures.
    api(project(":commands"))
}

messageSpec {
    specClass.set("eu.nordtal.s2.papercommon.PaperCommonMessages")
}
