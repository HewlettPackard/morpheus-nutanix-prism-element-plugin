/*
 * Copyright 2024 Morpheus Data, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.morpheusdata.nutanix.prismelement.plugin.utils

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
