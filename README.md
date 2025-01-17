# Operator Build Controller

> :warning: **This project is work-in-progress. It may not function** :warning:

The Operator Build Controller is a Kubernetes controller that coordinates building of operators for the 
[OKDerators catalog](https://github.com/okd-project/okderators-catalog-index). 

It currently supports polling of Git repositories for new commits and building the operator image using 
Tekton pipelines provided by [okd-operator-pipeline](https://github.com/okd-project/okd-operator-pipeline).