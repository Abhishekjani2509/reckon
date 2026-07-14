// command-service is its own standalone Gradle build, not a module of a root project.
//
// Services stay independent: they share event *contracts*, never a database or a build.
// Each additional service gets its own build for the same reason -- one service's
// dependency bump must not be able to break another's, exactly as if they lived in
// separate repositories.
rootProject.name = "command-service"
