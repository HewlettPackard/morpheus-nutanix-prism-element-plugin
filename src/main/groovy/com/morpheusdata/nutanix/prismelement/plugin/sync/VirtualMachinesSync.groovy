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

package com.morpheusdata.nutanix.prismelement.plugin.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.*
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.nutanix.prismelement.plugin.NutanixPrismElementPlugin
import com.morpheusdata.nutanix.prismelement.plugin.utils.NutanixPrismElementApiService
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

/**
 * Syncs virtual machines from nutanix to the morpheus ComputeServer equivalent
 *
 * It also syncs a virtual machine's NICs, disks and metrics
 */
@Slf4j
class VirtualMachinesSync {
	private final MorpheusContext context
	private final HttpApiClient client
	private final Cloud cloud
	private final Collection<ComputeServerInterfaceType> netTypes
	private final Collection<ComputeServerType> computeServerTypes

	VirtualMachinesSync(
		MorpheusContext context,
		Cloud cloud,
		HttpApiClient client,
		Collection<ComputeServerInterfaceType> netTypes,
		Collection<ComputeServerType> computeServerTypes
	) {
		this.context = context
		this.cloud = cloud
		this.client = client
		this.netTypes = netTypes
		this.computeServerTypes = computeServerTypes
	}

	void execute() {
		log.info("Executing Virtual Machine sync for cloud ${cloud.name}")
		try {
			def authConfig = NutanixPrismElementPlugin.getAuthConfig(context, cloud)
			def listConfig = [:]
			def listResults = NutanixPrismElementApiService.listVirtualMachinesV2(client, authConfig, listConfig)
			if (listResults.success == true) {
				def systemNetworks = context.services.network.list(new DataQuery()
					.withFilter('refType', 'ComputeZone')
					.withFilter('refId', cloud.id))

				def objList = listResults.virtualMachines

				def defaultServerType = computeServerTypes.find { it.code == 'nutanixUnmanaged' }
				def existingItems = context.async.computeServer.listIdentityProjections(
					new DataQuery()
						.withFilter('computeServerType.code', '!=', 'nutanixMetalHypervisor')
				)

				List<ServicePlan> availablePlans = context.services.servicePlan.list(
					new DataQuery()
						.withFilter('active', true)
						.withFilter('deleted', false)
						.withFilter('provisionType.code', 'nutanix')
				)

				List<ResourcePermission> availablePlanPermissions = []
				if (availablePlans) {
					availablePlanPermissions = context.services.resourcePermission.list(
						new DataQuery()
							.withFilter('morpheusServiceType', 'ServicePlan')
							.withFilter('morpheusResourceId', 'in', availablePlans.collect { pl -> pl.id })
					)
				}

				ServicePlan fallbackPlan = context.services.servicePlan.find(new DataQuery().withFilter('code', 'internal-custom-nutanix'))
				OsType osType = context.services.osType.find(new DataQuery().withFilter('code', 'unknown'))

				SyncTask<ComputeServerIdentityProjection, Map, ComputeServer> syncTask = new SyncTask<>(existingItems, objList)
				syncTask.addMatchFunction { ComputeServerIdentityProjection existingItem, Map cloudItem ->
					existingItem.externalId == cloudItem?.externalId
				}.onAdd { List<Map> cloudItems ->
					if (shouldImportExistingVMs(cloud.getConfigProperty('importExisting'))) {
						addMissingVirtualMachines(cloudItems, defaultServerType, systemNetworks, availablePlans, availablePlanPermissions, fallbackPlan, osType)
					}
				}.onUpdate { List<SyncList.UpdateItem<ComputeServer, Map>> updateItems ->
					updateMatchedVirtualMachines(updateItems, availablePlans, availablePlanPermissions, fallbackPlan)
				}.onDelete { deleteItems ->
					// TODO: switch back to bulkRemove once fixed
					context.services.computeServer.remove(deleteItems)
				}.withLoadObjectDetailsFromFinder { updateItems ->
					context.async.computeServer.listById(updateItems.collect { it.existingItem.id } as List<Long>)
				}.start()
			}
		} catch (e) {
			log.error("VirtualMachinesSync error: ${e}", e)
		}
	}

	void addMissingVirtualMachines(
		List<Map> addList,
		ComputeServerType defaultServerType,
		List<Network> systemNetworks,
		List<ServicePlan> availablePlans,
		List<ResourcePermission> availablePlanPermissions,
		ServicePlan fallbackPlan,
		OsType osType
	) {
		addList?.each {
			log.debug("adding missing vm: {}", it.externalId)

			def add = buildVm(it, osType, defaultServerType)
			add.plan = SyncUtils.findServicePlanBySizing(availablePlans, add.maxMemory, add.maxCores, null, fallbackPlan, null, cloud.account, availablePlanPermissions)

			def savedServer = context.services.computeServer.create(add)
			if (savedServer) {
				performPostSaveSync(savedServer, it, systemNetworks)
			}
		}
	}

	private ComputeServer buildVm(Map cloudItem, OsType osType, ComputeServerType defaultServerType) {
		def vm = new ComputeServer(
			account: cloud.account,
			externalId: cloudItem.externalId,
			name: cloudItem.name,
			sshUsername: 'root',
			osType: 'unknown',
			serverOs: osType
		)
		def ipAddress
		cloudItem.vm_nics.each { nic ->
			if (ipAddress == null && nic.ip_address) {
				ipAddress = nic.ip_address
			}
		}
		//ip config
		vm.externalIp = ipAddress
		vm.sshHost = ipAddress
		vm.internalIp = ipAddress
		//power
		vm.powerState = cloudItem.power_state.toUpperCase() == 'ON' ? ComputeServer.PowerState.on : ComputeServer.PowerState.off
		vm.apiKey = UUID.randomUUID()
		vm.computeServerType = defaultServerType
		vm.provision = false
		vm.singleTenant = true
		vm.cloud = cloud
		vm.lvmEnabled = false
		vm.managed = false
		vm.discovered = true
		vm.serverType = 'unmanaged'
		vm.hostname = cloudItem.legacyVm?.hostName
		vm.status = 'provisioned'
		vm.maxCores = (cloudItem.num_vcpus ?: 0) * (cloudItem.num_cores_per_vcpu ?: 0)
		vm.coresPerSocket = cloudItem.num_cores_per_vcpu ?: 1
		vm.maxMemory = (cloudItem.memory_mb ?: 0) * ComputeUtility.ONE_MEGABYTE
		return vm
	}

	private void performPostSaveSync(ComputeServer server, Map cloudItem, List<Network> systemNetworks) {
		if (server.status != 'resizing') {
			def volumeResults = cacheVirtualMachineVolumes(cloudItem.vm_disk_info as List<Map>, server)
			if (volumeResults.saveRequired) {
				// get latest in case of modifications from the volumes
				server = context.services.computeServer.get(server.id)
				if (!server.computeCapacityInfo) {
					server.maxStorage = volumeResults.maxStorage
					server.capacityInfo = new ComputeCapacityInfo(
						maxCores: server.maxCores,
						maxMemory: server.maxMemory,
						maxStorage: server.maxStorage,
					)
				} else if (volumeResults.maxStorage != server.maxStorage) {
					server.maxStorage = volumeResults.maxStorage
					server.capacityInfo.maxCores = server.maxCores
					server.capacityInfo.maxMemory = server.maxMemory
					server.capacityInfo.maxStorage = server.maxStorage
				}

				server = saveAndGet(server)
			}
		}

		if (server.status != 'provisioning' && cloudItem.legacyVm) {
			def savedRequired = updateVirtualMachineStats(server, cloudItem.legacyVm)
			if (savedRequired) {
				server = saveAndGet(server)
			}
		}

		if (server.status != 'resizing') {
			cacheVirtualMachineInterfaces(cloudItem.vm_nics as List<Map>, server, systemNetworks, netTypes)
		}
	}

	protected ComputeServer saveAndGet(ComputeServer server) {
		def saveSuccessful = context.async.computeServer.bulkSave([server]).blockingGet()
		if (!saveSuccessful) {
			log.warn("Error saving server: ${server?.id}")
		}
		return context.async.computeServer.get(server.id).blockingGet()
	}

	private static boolean updateVirtualMachineStats(ComputeServer server, Map vm) {
		def updates = false
		def capacityInfo = server.capacityInfo ?: new ComputeCapacityInfo(maxStorage: server.maxStorage)

		//memory
		def maxMemory = vm.memoryCapacityInBytes?.toLong() ?: 0
		def usedMemory = (vm.stats && vm.stats['guest.memory_usage_bytes']) ? vm.stats['guest.memory_usage_bytes'].toLong() : 0
		if (maxMemory != capacityInfo.maxMemory || usedMemory != capacityInfo.usedMemory) {
			server.maxMemory = maxMemory
			server.usedMemory = usedMemory
			capacityInfo.maxMemory = maxMemory
			capacityInfo.usedMemory = usedMemory
			updates = true
		}

		// storage
		def usedStorage
		if (server.agentInstalled) {
			usedStorage = server.usedStorage ?: 0
		} else {
			usedStorage = vm.stats?.controller_user_bytes?.toLong() ?: 0
		}
		if (usedStorage != capacityInfo.usedStorage) {
			server.usedStorage = usedStorage
			capacityInfo.usedStorage = usedStorage
			updates = true
		}

		//cpu
		def usedCpu = Math.min(100.0, (vm.stats.hypervisor_cpu_usage_ppm?.toLong() ?: 0) / 1000000)
		if (usedCpu != server.usedCpu) {
			capacityInfo.maxCpu = (Float) usedCpu
			server.usedCpu = (Float) usedCpu
			updates = true
		}

		if (updates) {
			server.capacityInfo = capacityInfo
		}

		return updates
	}

	private void updateMatchedVirtualMachines(
		List<SyncList.UpdateItem<ComputeServer, Map>> updateList,
		List<ServicePlan> availablePlans,
		List<ResourcePermission> availablePlanPermissions,
		ServicePlan fallbackPlan
	) {
		def systemNetworks = updateList.collect { it.masterItem.vm_nics?.collect { it.network_uuid } }
			.flatten()
			.unique()
			.with { networkIds ->
				context.services.network.list(
					new DataQuery()
						.withFilter('refType', 'ComputeZone')
						.withFilter('refId', cloud.id)
						.withFilter('externalId', 'in', networkIds)
				)
			}

		for (final def update in updateList) {
			ComputeServer currentServer = update.existingItem
			Map matchedServer = update.masterItem
			if (!currentServer || currentServer.status == 'provisioning') {
				continue
			}

			def ipAddress
			matchedServer.vm_nics.each { nic ->
				if (ipAddress == null && nic.ip_address) {
					ipAddress = nic.ip_address
				}
			}

			def save = false

			def powerState = matchedServer.power_state?.toUpperCase() == 'ON' ? ComputeServer.PowerState.on : ComputeServer.PowerState.off
			if (powerState != currentServer.powerState) {
				currentServer.powerState = powerState
				save = true
			}

			//should only update ip information if its on
			if (powerState == ComputeServer.PowerState.on && ipAddress) {
				def existingSshHost = currentServer.sshHost
				if (ipAddress != currentServer.externalIp) {
					if (currentServer.externalIp == existingSshHost)
						currentServer.sshHost = ipAddress
					currentServer.externalIp = ipAddress
					save = true
				}
				if (ipAddress != currentServer.internalIp) {
					if (currentServer.internalIp == existingSshHost)
						currentServer.sshHost = ipAddress
					currentServer.internalIp = ipAddress
					save = true
				}
			}

			def name = matchedServer.name
			if (name != currentServer.name) {
				currentServer.name = name
				save = true
			}

			def maxCores = (matchedServer.num_cores_per_vcpu ?: 0) * (matchedServer.num_vcpus ?: 0)
			if (currentServer.maxCores != maxCores) {
				currentServer.maxCores = maxCores
				save = true
			}

			def coresPerSocket = matchedServer.num_cores_per_vcpu ?: 1
			if (currentServer.coresPerSocket != coresPerSocket) {
				currentServer.coresPerSocket = coresPerSocket
				save = true
			}

			def maxMemory = (matchedServer.memory_mb ?: 0) * ComputeUtility.ONE_MEGABYTE
			if (currentServer.maxMemory != maxMemory) {
				currentServer.maxMemory = maxMemory
				save = true
			}

			ServicePlan plan = SyncUtils.findServicePlanBySizing(availablePlans, currentServer.maxMemory, currentServer.maxCores, null, fallbackPlan, currentServer.plan, currentServer.account, availablePlanPermissions)
			if (currentServer.plan?.id != plan?.id) {
				currentServer.plan = plan
				save = true
			}

			def parentId = matchedServer.host_uuid
			if (parentId) {
				currentServer.parentServer = context.services.computeServer.find(
					new DataQuery()
						.withFilter('cloud.id', cloud.id)
						.withFilter('externalId', parentId)
				)
			}

			if (save) {
				context.services.computeServer.save(currentServer)
			}

			performPostSaveSync(currentServer, matchedServer, systemNetworks)
		}
	}

	private cacheVirtualMachineVolumes(List<Map> diskList, ComputeServer server) {
		def rtn = [saveRequired: false, maxStorage: 0]
		try {

			// TODO: use FullModelSyncTask when a release is cut and propagated into morpheus-ui
			SyncTask<StorageVolume, Map, StorageVolume> syncTask = new SyncTask<>(
				Observable.fromIterable(server.volumes),
				diskList,
			).withLoadObjectDetails { List<SyncTask.UpdateItemDto<StorageVolume, Map>> items ->
				Observable.fromIterable(items.collect { item ->
					new SyncTask.UpdateItem<StorageVolume, Map>(existingItem: item.existingItem, masterItem: item.masterItem)
				})
			}

			syncTask
				.addMatchFunction { StorageVolume morpheusVolume, diskInfo ->
					morpheusVolume?.externalId == diskInfo.disk_address.vmdisk_uuid
				}
				.addMatchFunction { StorageVolume morpheusVolume, diskInfo ->
					def deviceName = generateVolumeDeviceName(diskInfo, diskList)
					morpheusVolume.deviceDisplayName == deviceName && morpheusVolume.type.externalId == "nutanix_${diskInfo.disk_address.device_bus.toUpperCase()}"
				}
				.addMatchFunction { StorageVolume morpheusVolume, diskInfo ->
					def indexPos = diskInfo.disk_address?.device_index ?: diskInfo.device_properties?.disk_address?.device_index ?: 0
					if (diskInfo.disk_address?.device_bus?.toUpperCase() == 'SATA' || diskInfo.device_properties?.disk_address?.adapter_type == 'SATA') {
						indexPos += diskList.count { it.device_properties?.disk_address?.adapter_type == 'SCSI' || it.disk_address?.device_bus?.toUpperCase() == 'SCSI' }
					}
					morpheusVolume.displayOrder == indexPos
				}
				.onAdd { addItems ->
					def disks = []
					for (final def addItem in addItems) {
						def volumeId = addItem.disk_address.vmdisk_uuid

						if (addItem.is_cdrom != true) {
							def storageVolumeType = context.services.storageVolume.storageVolumeType.find(
								new DataQuery().withFilter('externalId', "nutanix_${addItem.disk_address.device_bus.toUpperCase()}")
							)

							if (storageVolumeType) {
								def maxStorage = addItem.size
								def deviceName = generateVolumeDeviceName(addItem, diskList)
								def datastore

								def volume = new StorageVolume(maxStorage: maxStorage, type: storageVolumeType, externalId: volumeId,
									unitNumber: addItem.disk_address.device_index, deviceName: "/dev/${deviceName}", name: volumeId,
									cloudId: server.cloud?.id, displayOrder: addItem.disk_address.device_index)

								if (addItem.storage_container_uuid) {
									volume.datastore = context.services.cloud.datastore.find(
										new DataQuery()
											.withFilter('refType', 'ComputeZone')
											.withFilter('refId', cloud.id)
											.withFilter('externalId', addItem.storage_container_uuid)
									)
								}
								volume.deviceDisplayName = deviceName
								if (volume.deviceDisplayName == 'sda')
									volume.rootVolume = true
								rtn.maxStorage += maxStorage
								disks << volume
							}
						}

					}

					if (disks) {
						def result = context.async.storageVolume.create(disks, server).blockingGet()
						if (!result) {
							log.error("failed to create storage volume(s) for server $server.name")
						}
						rtn.saveRequired = true
					}
				}
				.onUpdate { updateItems ->
					def disks = []
					for (final def updateMap in updateItems) {
						log.debug("processing update item: ${updateMap}")
						StorageVolume existingVolume = updateMap.existingItem
						def diskInfo = updateMap.masterItem

						def volumeId = diskInfo.disk_address.vmdisk_uuid

						def save = false
						def maxStorage = diskInfo.size
						if (existingVolume.maxStorage != maxStorage) {
							existingVolume.maxStorage = maxStorage
							save = true
						}
						if (existingVolume.unitNumber != diskInfo.disk_address.device_index?.toString()) {
							existingVolume.unitNumber = diskInfo.disk_address.device_index
							save = true
						}
						def deviceDisplayName = generateVolumeDeviceName(diskInfo, diskList)

						if (deviceDisplayName != existingVolume.deviceDisplayName) {
							existingVolume.deviceDisplayName = deviceDisplayName
							save = true
						}
						def rootVolume = deviceDisplayName == 'sda'
						if (rootVolume != existingVolume.rootVolume) {
							existingVolume.rootVolume = rootVolume
							save = true
						}
						if (existingVolume.externalId != volumeId) {
							existingVolume.externalId = volumeId
							save = true
						}

						if (save) {
							disks << existingVolume
						}
						rtn.maxStorage += maxStorage
					}

					if (disks) {
						rtn.saveRequired = true
						context.services.storageVolume.bulkSave(disks)
					}
				}
				.onDelete { deleteItems ->
					def disks = []
					for (final def existingVolume in deleteItems) {
						log.debug "removing volume ${existingVolume}"
						disks << existingVolume
					}

					if (disks) {
						context.async.storageVolume.remove(disks, server, false).blockingGet()
						rtn.saveRequired = true
					}
				}.start()
		} catch (e) {
			log.error("error cacheVirtualMachineVolumes ${e}", e)
		}
		return rtn
	}

	private static String generateVolumeDeviceName(diskInfo, List<Map> diskList) {
		def deviceName = ''
		if (!diskInfo.device_properties) {
			if (diskInfo.disk_address.device_bus.toUpperCase() == 'SCSI'
				|| diskInfo.disk_address.device_bus.toUpperCase() == 'SATA') {
				deviceName += 'sd'
			} else {
				deviceName += 'hd'
			}
		} else {
			if (diskInfo.device_properties.disk_address.adapter_type == 'SCSI'
				|| diskInfo.device_properties.disk_address.adapter_type == 'SATA') {
				deviceName += 'sd'
			} else {
				deviceName += 'hd'
			}
		}


		def letterIndex = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l']
		def indexPos = diskInfo.disk_address?.device_index ?: diskInfo.device_properties?.disk_address?.device_index ?: 0
		if (diskInfo.disk_address?.device_bus?.toUpperCase() == 'SATA'
			|| diskInfo.device_properties?.disk_address?.adapter_type == 'SATA') {
			indexPos += diskList.count { it.device_properties?.disk_address?.adapter_type == 'SCSI' || it.disk_address?.device_bus?.toUpperCase() == 'SCSI' }
		}
		deviceName += letterIndex[indexPos]

		return deviceName

	}

	private static boolean shouldImportExistingVMs(importExisting) {
		importExisting == 'on' || importExisting == 'true' || importExisting == true
	}

	private void cacheVirtualMachineInterfaces(List<Map> nicList, ComputeServer server, List<Network> networks, Collection<ComputeServerInterfaceType> netTypes) {
		try {
			log.debug("Nic List: {}", nicList)

			// TODO: use FullModelSyncTask when a release is cut and propagated into morpheus-ui
			SyncTask<ComputeServerInterface, Map, ComputeServerInterface> syncTask = new SyncTask<>(Observable.fromIterable(server.interfaces), nicList)
				.withLoadObjectDetails { List<SyncTask.UpdateItemDto<ComputeServerInterface, Map>> items ->
					Observable.fromIterable(items.collect { item ->
						new SyncTask.UpdateItem<ComputeServerInterface, Map>(existingItem: item.existingItem, masterItem: item.masterItem)
					})
				}

			syncTask
				.addMatchFunction { ComputeServerInterface serverInterface, nicInfo ->
					serverInterface?.externalId == nicInfo.mac_address
				}
				.addMatchFunction { ComputeServerInterface serverInterface, nicInfo ->
					serverInterface.ipAddress == nicInfo.ip_address
				}
				.onAdd { addItems ->
					def netInterfaces = []
					for (final def nicInfo in addItems) {
						def nicId = nicInfo.mac_address
						def network = networks.find { it.externalId == nicInfo.network_uuid }
						def nicPosition = nicList.indexOf(nicInfo) ?: 0

						def nicType = netTypes.find { it.code.startsWith('nutanix') && it.externalId == (nicInfo.adapter_type ?: 'virtio') }
						def netInterface = new ComputeServerInterface(
							externalId: nicId,
							name: "eth${nicPosition}",
							network: network,
							type: nicType,
							macAddress: nicInfo.mac_address)

						netInterface.addresses << new NetAddress(type: NetAddress.AddressType.IPV4, address: nicInfo?.ip_address)
						netInterfaces << netInterface
					}

					if (netInterfaces) {
						context.async.computeServer.computeServerInterface.create(netInterfaces, server).blockingGet()
					}
				}
				.onUpdate { updateItems ->
					def netInterfaces = []
					for (final def updateMap in updateItems) {
						log.debug("processing update item: {}", updateMap)
						ComputeServerInterface existingInterface = updateMap.existingItem
						def nicInfo = updateMap.masterItem
						def nicId = nicInfo.mac_address

						def network = networks.find { it.externalId == nicInfo.network_uuid }
						def save = false
						if (network && existingInterface.network?.id != network?.id) {
							existingInterface.network = network
							save = true
						}

						if (nicInfo.mac_address != existingInterface.macAddress) {
							existingInterface.macAddress = nicInfo.mac_address
							save = true
						}

						def ipAddress = nicInfo.ip_endpoint_list ? nicInfo.ip_endpoint_list.first().ip : null
						if (!existingInterface.addresses.find {
							it.type == NetAddress.AddressType.IPV4 && it.address == ipAddress
						}) {
							existingInterface.addresses << new NetAddress(NetAddress.AddressType.IPV4, ipAddress)
							save = true
						}

						if (existingInterface.externalId != nicId) {
							existingInterface.externalId = nicId
							save = true
						}

						if (save) {
							netInterfaces << existingInterface
						}
					}
					if (netInterfaces) {
						context.services.computeServer.computeServerInterface.bulkSave(netInterfaces)
					}
				}
				.onDelete { deleteItems ->
					if (deleteItems) {
						context.async.computeServer.computeServerInterface.remove(deleteItems, server).blockingGet()
					}
				}.start()
		} catch (e) {
			log.error("error cacheVirtualMachineVolumes ${e}", e)
		}
	}
}
