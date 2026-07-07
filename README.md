# Morpheus Nutanix Prism Element Plugin

This plugin provides a full integration between [Nutanix Prism Element (AHV)](https://www.nutanix.com/products/prism) and [Morpheus](https://morpheusdata.com). It enables cloud inventory sync, VM provisioning, snapshot-based backups, and Nutanix network pool support from within the Morpheus platform.

## Requirements

| Component | Minimum Version |
|-----------|----------------|
| Morpheus | 9.0.0 |
| Nutanix Prism Element | 5.0 |

## Installation

1. Download the latest `.jar` from the [Releases](https://github.com/HewlettPackard/morpheus-nutanix-prism-element-plugin/releases) page, or [build it yourself](#building).
2. In Morpheus, navigate to **Administration → Integrations → Plugins**.
3. Click **Browse** and upload the `.jar` file.
4. The **Nutanix Prism Element** cloud type will appear after the plugin loads.

## Configuration

When adding a Nutanix Prism Element cloud in Morpheus (**Infrastructure → Clouds → Add Cloud**), provide the following:

| Field | Description |
|-------|-------------|
| **API URL** | Prism Element endpoint, e.g. `https://10.100.10.100:9440` |
| **Credentials** | Select local credentials or a stored username/password credential |
| **Username** | Prism Element username |
| **Password** | Prism Element password |
| **Import Existing** | Inventory existing Prism Element VMs |
| **Enable VNC** | Enable hypervisor console access |
| **Default Image Store** | Default Prism Element storage container for images |
| **Enable Network Type Selection** | Allow network type selection during provisioning |
| **Static IP Mode (Windows)** | Choose how static IP settings are applied to Windows VMs |

Credentials can also be stored as a Morpheus [Credential](https://docs.morpheusdata.com/en/latest/administration/credentials/credentials.html) and selected at cloud setup time.

## Features

### Cloud Sync

The following resources are discovered and kept in sync from Prism Element:

- **Networks** — AHV networks and VLAN-backed Morpheus network types
- **Containers** — Prism Element storage containers exposed as Morpheus datastores
- **Images** — virtual images available for provisioning
- **Hosts** — Nutanix AHV hypervisor hosts
- **Virtual Machines** — managed and unmanaged VMs, including power state and metadata
- **Snapshots** — VM snapshots associated with inventoried workloads

Any additions, updates, and removals in Prism Element are automatically reflected in Morpheus on the next sync cycle.

### Provisioning

Virtual machines can be provisioned into Nutanix Prism Element directly from Morpheus using standard instance types and layouts. Supported operations include:

- Create, start, stop, and delete VMs
- Resize CPU, memory, disks, and network interfaces
- Create and remove VM snapshots
- Select images, storage containers, and networks during provisioning
- Provision Linux, Windows, Docker host, and Kubernetes node server types
- Apply cloud-init, Windows static IP customization, and optional VNC console access

### Backups

Nutanix VM snapshots are supported via the Morpheus backup framework. Supported operations include:

- Create VM snapshot backups
- Track snapshot task status and backup result metadata
- Delete snapshot backup results
- Restore snapshots to new workloads through the Morpheus restore workflow

### Network Pool Support

The plugin registers an IPAM provider for Nutanix Prism Element. Morpheus creates a hidden network pool server for each cloud and can associate Morpheus network pools with Nutanix-backed networks for IP allocation workflows.

## Building

```bash
./gradlew shadowJar
```

The plugin JAR will be written to `build/libs/`.

## License

Copyright 2024 Morpheus Data, LLC. Licensed under the [Apache License, Version 2.0](LICENSE).
