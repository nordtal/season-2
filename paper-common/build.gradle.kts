plugins {
    id("nordtal.paper-library")
}

dependencies {
    // Every command declared once and adapted per surface. `api` rather than `implementation`: the
    // Paper adapters here have NordtalUser on their signatures, so a plugin using one compiles
    // against it. :commands brings :common with it, which nordtal.paper-library already adds.
    api(project(":commands"))
}
