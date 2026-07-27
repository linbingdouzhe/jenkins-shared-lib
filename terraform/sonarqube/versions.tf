terraform {
  required_version = ">= 1.5"

  required_providers {
    sonarqube = {
      source  = "jdamata/sonarqube"
      version = "~> 0.16"
    }
  }
}

provider "sonarqube" {
  host = var.sonarqube_host
  # token is read from the SONARQUBE_TOKEN env var, never store it in this repo
}
