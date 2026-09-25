# Builds every image docker-compose.e2e.yml runs, in parallel. Paths are relative to the directory
# holding the sibling checkouts (infrastructure, game-client, game-service, timeline-service,
# read-service), so run it from there:
#
#   docker buildx bake --load -f infrastructure/browser-e2e/docker-bake.hcl
#
# The *_TAG variables are the same ones docker-compose.e2e.yml reads.

variable "GAME_SERVICE_TAG" {}
variable "TIMELINE_SERVICE_TAG" {}
variable "READ_SERVICE_TAG" {}
variable "WEB_CLIENT_TAG" {}

group "default" {
  targets = ["game-service", "timeline-service", "read-service", "web-client", "config-server", "e2e-tests"]
}

target "game-service" {
  context = "game-service"
  tags    = ["temporal-rift/game-service:${GAME_SERVICE_TAG}"]
}

target "timeline-service" {
  context = "timeline-service"
  tags    = ["temporal-rift/timeline-service:${TIMELINE_SERVICE_TAG}"]
}

target "read-service" {
  context = "read-service"
  tags    = ["temporal-rift/read-service:${READ_SERVICE_TAG}"]
}

target "web-client" {
  context = "game-client"
  tags    = ["temporal-rift/web-client:${WEB_CLIENT_TAG}"]
}

# Tags must match the `image:` docker-compose.e2e.yml gives these two build-from-source services.
target "config-server" {
  context = "infrastructure/config-server"
  tags    = ["temporal-rift/browser-e2e-config-server:local"]
}

target "e2e-tests" {
  context    = "infrastructure"
  dockerfile = "browser-e2e/Dockerfile"
  tags       = ["temporal-rift/browser-e2e-tests:local"]
}
