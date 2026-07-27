variable "sonarqube_host" {
  description = "SonarQube base URL"
  type        = string
  default     = "http://localhost:9000"
}

variable "jenkins_webhook_url" {
  description = "Jenkins endpoint that receives SonarQube analysis webhooks"
  type        = string
  default     = "http://jenkins.jenkins.svc.cluster.local:8080/sonarqube-webhook/"
}

variable "services" {
  description = "Services that get a SonarQube project + Jenkins webhook. Key is the SonarQube project key, value is the display name."
  type        = map(string)
  default = {
    emailservice          = "emailservice"
    recommendationservice = "recommendationservice"
    loadgenerator         = "loadgenerator"
    checkoutservice       = "checkoutservice"
    adservice             = "adservice"
  }
}
