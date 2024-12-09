# Morpheus Nutanix Prism Element Plugin

This library provides an integration between Nutanix Prism Element and Morpheus.

### Requirements

Nutanix Prism Element - Version 5.0 or greater, allowing for support of the v2.0 API.

### Building

`./gradlew shadowJar`

### Configuration

The following options are required when setting up a Morpheus Cloud to a Nutanix Prism Element environment using this plugin:

- API URL: The URL of the Nutanix Prism Element API (e.g., https://10.100.10.100:9440/)
- Username: The username to authenticate with the Nutanix Prism Element API
- Password: The password to authenticate with the Nutanix Prism Element API

#### Features

Backup: VM snapshots can be created and restored from Morpheus.

Cloud Sync: Datastores, networks, images, hosts, snapshots and virtual machines are fetched from Nutanix and inventoried in Morpheus. Any additions, updates, and removals to these objects are reflected in Morpheus.

Provisioning: Virtual machines can be provisioned from Morpheus via this plugin.
