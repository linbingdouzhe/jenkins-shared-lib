resource "sonarqube_project" "service" {
  for_each   = var.services
  project    = each.key
  name       = each.value
  visibility = "public"
}

resource "sonarqube_webhook" "jenkins" {
  for_each = var.services
  name     = "jenkins"
  url      = var.jenkins_webhook_url
  project  = sonarqube_project.service[each.key].project
}
