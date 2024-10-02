package com.morpheusdata.nutanix.prismelement.plugin.util

import com.morpheusdata.model.StorageVolumeType

class NutanixPrismElementStorageUtility {
    private static oneGB = (1024 * 1024 * 1024) as Long

    static Collection<StorageVolumeType> getDefaultStorageVolumes() {
        Collection<StorageVolumeType> volumeTypes = []

        volumeTypes << new StorageVolumeType(
                code: 'nutanix-scsi',
                externalId: 'nutanix_SCSI',
                displayName: 'Nutanix SCSI',
                name: 'scsi',
                description: 'Nutanix - SCSI',
                displayOrder: 1,
                defaultType: true,
                minStorage: oneGB,
                allowSearch: true,
        )

        volumeTypes << new StorageVolumeType(
                code: 'nutanix-sata',
                externalId: 'nutanix_SATA',
                displayName: 'Nutanix SATA',
                name: 'sata',
                description: 'Nutanix - SATA',
                displayOrder: 2,
                defaultType: true,
                minStorage: oneGB,
                allowSearch: true,
        )

        volumeTypes << new StorageVolumeType(
                code: 'nutanix-ide',
                externalId: 'nutanix_IDE',
                displayName: 'Nutanix IDE',
                name: 'ide',
                description: 'Nutanix - IDE',
                displayOrder: 3,
                defaultType: true,
                minStorage: oneGB,
                allowSearch: true,
        )

        volumeTypes
    }
}