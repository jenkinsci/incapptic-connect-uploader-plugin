# incapptic Connect Uploader Plugin

Integrate the incapptic Connect platform with Jenkins. Upload artifacts automatically to the incapptic Connect platform after each successful output from Jenkins CI.

# Usage - pipeline plugin #


In order to make use of pipeline plugin, the Jenkinsfile should
include the following command at any stage of deployment, after the 
respective artifact has been created.

```groovy
#!groovy

node {
    stage('notify') {
        uploadToIncappticConnect url: 'https://instance.incapptic.net/apps/upload-artifact', appId: 1, mask: '**/app-release-unsigned.apk'
    }
}
```
Even though the personal token can also be provided as a `token` parameter, 
this is strongly discouraged. Instead a `token` parameter 
should be added to the project configuration. 
To do this, select the checkbox that says "This project is parametrized", 
and then add a parameter with the default value. 

The plugin parameter has precedence over the project parameter.

## Parameters ##

### url ###
This parameter should point to incapptic Connects' upload service, 
typically with the path value `/apps/upload-artifact.` 

### token ###
The personal token configured in incapptic Connects' administration panel. 

### appId ###
The (numerically valued) ID of the application that you 
are uploading artifacts to. This application must have its API enabled 
through incapptic Connects' admin interface. 

### mask ###
Used to specify the file location of an artifact to be sent to the incapptic
 Connect service. This can simply be an absolute path but can
 also include expandable wildcard patterns.

# Usage - standard plugin #

Standard plugin configurations need to specify 
a post build action with parameters: 
* url,
* token,
* appId,
* mask

provided. They have the same meaning as in the pipeline plugin.

### *incapptic Connect GmbH*
