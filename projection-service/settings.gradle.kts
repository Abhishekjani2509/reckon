// Independent build, like command-service. The two services share the event contract
// carried on the Kafka topic and nothing else -- not a database, not a Gradle build, not
// a line of code. That is the CQRS boundary made physical.
rootProject.name = "projection-service"
