# Morpheus Nutanix Prism Element Plugin

The Morpheus Nutanix Prism Element Plugin integrates Morpheus with Nutanix Prism Element (AHV) to provide virtual machine provisioning, backup via snapshots, IPAM (network pool management), and full cloud synchronisation. The plugin communicates with the Nutanix Prism Element REST API (v2.0).

## Table of Contents

- [Features](#features)
- [Requirements](#requirements)
- [Repository structure](#repository-structure)
- [Building the plugin](#building-the-plugin)
- [License](#license)
- [Installing](#installing)
- [Detailed Usage Steps](#detailed-usage-steps)
- [API Endpoints](#api-endpoints)

---

## Features

### Virtual Machine Provisioning

Provision and decommission AHV virtual machines from Morpheus. Supports image selection, network assignment, storage container selection, CPU, memory, and disk configuration.

### Backup via Snapshots

Back up and restore AHV VMs using Nutanix snapshots managed through the Morpheus backup framework.

### IPAM / Network Pool Management

Manage IP address pools hosted on the Nutanix cluster directly from Morpheus. Supports automatic IP allocation from Nutanix-managed pools.

### Cloud Sync

Morpheus synchronises the following Nutanix Prism Element resources for inventory:

- Virtual machines
- Storage containers
- Images (disk images)
- Networks
- Hosts
- Snapshots

---

## Requirements

| Requirement | Version |
|-------------|---------|
| Morpheus | 9.0.0 or later |
| Java | 11 or later |
| Gradle | Use the included Gradle wrapper (`./gradlew`) |

Additional prerequisites:

- A Nutanix cluster running Prism Element with the REST API accessible from the Morpheus appliance
- A Nutanix user account with admin or equivalent API permissions
- Network access from the Morpheus appliance to the Nutanix Prism Element API (default port: 9440 over HTTPS)

---

## Repository structure

```
src/main/groovy/com/morpheusdata/nutanix/prismelement/plugin/
├── NutanixPrismElementPlugin.groovy               - Plugin entry point; registers all providers
├── NutanixPrismElementOptionSourceProvider.groovy - UI option source data
├── backup/
│   ├── NutanixPrismElementBackupProvider.groovy       - BackupProvider implementation
│   ├── NutanixPrismElementBackupTypeProvider.groovy   - Backup type (snapshot-based)
│   ├── NutanixPrismElementBackupExecutionProvider.groovy - Backup execution
│   └── NutanixPrismElementBackupRestoreProvider.groovy   - Restore from snapshot
├── cloud/
│   ├── NutanixPrismElementCloudProvider.groovy        - CloudProvider implementation
│   └── sync/
│       ├── ContainersSync.groovy                       - Syncs storage containers
│       ├── HostsSync.groovy                            - Syncs hosts
│       ├── ImagesSync.groovy                           - Syncs disk images
│       ├── NetworkSync.groovy                          - Syncs networks
│       ├── SnapshotsSync.groovy                        - Syncs snapshots
│       └── VirtualMachinesSync.groovy                  - Syncs VMs
├── dataset/
│   ├── NutanixPrismElementImageStoreDatasetProvider.groovy
│   ├── NutanixPrismElementProvisionImageDatasetProvider.groovy
│   └── NutanixPrismElementVirtualImageDatasetProvider.groovy
├── network/
│   └── NutanixPrismElementNetworkPoolProvider.groovy  - IPAM/network pool provider
├── provision/
│   └── NutanixPrismElementProvisionProvider.groovy    - ProvisionProvider implementation
└── utils/
    └── NutanixPrismElementApiService.groovy            - Nutanix REST API client
src/main/resources/i18n/               - Internationalisation message bundles
src/main/resources/scribe/             - Seed/migration scripts
src/test/groovy/                        - Spock unit and integration tests
build.gradle, gradle.properties         - Build configuration and plugin metadata
```

---

## Building the plugin

Run the following command to compile and package the plugin jar:

```bash
./gradlew clean build
```

The packaged jar will be written to `build/libs/`.

To execute tests, use the following command:

```bash
./gradlew test
```

---

## License

This project is licensed under the Apache License 2.0.

See the [LICENSE](LICENSE) file for details.

---

## Installing

1. Build the plugin (see [Building the plugin](#building-the-plugin)) or download a released jar.
2. In Morpheus, navigate to **Administration > Integrations > Plugins**.
3. Click **Add** and upload the `morpheus-nutanix-prism-element-plugin-<version>.jar` from `build/libs/`.
4. Navigate to **Infrastructure > Clouds > Add** and select **Nutanix Prism Element** to configure the integration.

---

## Detailed Usage Steps

### Adding a Nutanix Prism Element Cloud

1. Go to **Infrastructure > Clouds > Add**.
2. Select **Nutanix Prism Element** as the cloud type.
3. Enter a **Name**, the Prism Element **API URL** (e.g. `https://prism.example.com:9440`), and provide credentials.
4. Save. Morpheus connects to Prism Element and begins syncing hosts, VMs, images, networks, and storage containers.

### Provisioning a Virtual Machine

1. Go to **Provisioning > Instances > Add**.
2. Select an AHV-backed instance type.
3. Choose the target **Group**, **Cloud**, and configure plan, image, network, and storage container.
4. Provision. Morpheus creates the VM on the Nutanix cluster.

### Taking a Backup

1. From an instance detail page, navigate to the **Backups** tab.
2. Click **Backup Now** to trigger an on-demand Nutanix snapshot.

### Restoring from a Snapshot

1. From the instance **Backups** tab, select a completed snapshot.
2. Click **Restore** and confirm. Morpheus restores the VM from the selected snapshot.

---

## API Endpoints

This plugin communicates with the **Nutanix Prism Element REST API** at `https://<prism-host>:9440`. Authentication uses HTTP Basic credentials.

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/nutanix/v2.0/cluster` | GET | Validate connectivity and get cluster info |
| `/api/nutanix/v2.0/storage_containers` | GET | List storage containers |
| `/api/nutanix/v2.0/images` | GET | List images |
| `/api/nutanix/v2.0/images` | POST | Upload an image |
| `/api/nutanix/v2.0/images/{id}/` | GET | Get image details |
| `/api/nutanix/v2.0/images/{id}/` | DELETE | Delete an image |
| `/api/nutanix/v2.0/vms` | GET | List VMs |
| `/api/nutanix/v2.0/vms` | POST | Create a VM |
| `/api/nutanix/v2.0/vms/{id}` | GET | Get VM details |
| `/api/nutanix/v2.0/vms/{id}` | PUT | Update a VM |
| `/api/nutanix/v2.0/vms/{id}` | DELETE | Delete a VM |
| `/api/nutanix/v2.0/snapshots` | GET | List snapshots |
| `/api/nutanix/v2.0/snapshots` | POST | Create a snapshot |
| `/api/nutanix/v2.0/snapshots/{id}` | DELETE | Delete a snapshot |
| `/PrismGateway/services/rest/v1/networks` | GET | List networks |
| `/PrismGateway/services/rest/v1/hosts` | GET | List hosts |
