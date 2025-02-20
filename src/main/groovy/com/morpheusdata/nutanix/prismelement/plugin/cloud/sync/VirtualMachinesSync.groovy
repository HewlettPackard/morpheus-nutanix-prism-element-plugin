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

package com.morpheusdata.nutanix.prismelement.plugin.cloud.sync

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.*
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import com.morpheusdata.nutanix.prismelement.plugin.utils.NutanixPrismElementApiService
import com.morpheusdata.nutanix.prismelement.plugin.utils.NutanixPrismElementSyncUtility
import groovy.util.logging.Slf4j

/**
 * Syncs virtual machines from nutanix to the morpheus ComputeServer equivalent
 *
 * It also syncs a virtual machine's NICs, disks and metrics
 */
@Slf4j
class VirtualMachinesSync {
	private final MorpheusContext morpheusContext
	private final HttpApiClient client
	private final Cloud cloud
	private final Collection<ComputeServerInterfaceType> netTypes
	private final Collection<ComputeServerType> computeServerTypes

	VirtualMachinesSync(
		MorpheusContext morpheusContext,
		Cloud cloud,
		HttpApiClient client,
		Collection<ComputeServerInterfaceType> netTypes,
		Collection<ComputeServerType> computeServerTypes
	) {
		this.morpheusContext = morpheusContext
		this.cloud = cloud
		this.client = client
		this.netTypes = netTypes
		this.computeServerTypes = computeServerTypes
	}

	void execute() {
		log.info("Executing Virtual Machine sync for cloud ${cloud.name}")
		try {
			def reqConfig = NutanixPrismElementApiService.getRequestConfig(morpheusContext, cloud)
			def listResults = NutanixPrismElementApiService.listVirtualMachinesV2(client, reqConfig)
			if (listResults.success == true) {
				def systemNetworks = morpheusContext.services.network.list(new DataQuery()
					.withFilter('refType', 'ComputeZone')
					.withFilter('refId', cloud.id))

				def objList = listResults.virtualMachines

				def defaultServerType = computeServerTypes.find { it.code == 'nutanixUnmanaged' }
				def existingItems = morpheusContext.async.computeServer.listIdentityProjections(
					new DataQuery()
						.withFilters(
							new DataFilter('zone.id', cloud.id),
							new DataFilter('computeServerType.code', '!=', 'nutanixMetalHypervisor')
						)
				)

				List<ServicePlan> availablePlans = morpheusContext.services.servicePlan.list(
					new DataQuery()
						.withFilter('active', true)
						.withFilter('deleted', false)
						.withFilter('provisionType.code', 'nutanix')
				)

				List<ResourcePermission> availablePlanPermissions = []
				if (availablePlans) {
					availablePlanPermissions = morpheusContext.services.resourcePermission.list(
						new DataQuery()
							.withFilter('morpheusServiceType', 'ServicePlan')
							.withFilter('morpheusResourceId', 'in', availablePlans.collect { pl -> pl.id })
					)
				}

				ServicePlan fallbackPlan = morpheusContext.services.servicePlan.find(new DataQuery().withFilter('code', 'internal-custom-nutanix'))
				OsType osType = morpheusContext.services.osType.find(new DataQuery().withFilter('code', 'unknown'))

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
					morpheusContext.services.computeServer.remove(deleteItems)
				}.withLoadObjectDetailsFromFinder { updateItems ->
					morpheusContext.async.computeServer.listById(updateItems.collect { it.existingItem.id } as List<Long>)
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

			def savedServer = morpheusContext.services.computeServer.create(add)
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
			def volumeResults = NutanixPrismElementSyncUtility.syncVirtualMachineVolumes(morpheusContext, server, cloudItem.vm_disk_info as List<Map>)
			if (volumeResults.saveRequired) {
				// get latest in case of modifications from the volumes
				server = morpheusContext.services.computeServer.get(server.id)
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

		if (server != null && (server.agentInstalled == false || server.powerState == 'off' || server.powerState == 'suspended') && server.status != 'provisioning' && cloudItem.legacyVm) {
			def savedRequired = updateVirtualMachineStats(server, cloudItem.legacyVm as Map)
			if (savedRequired) {
				server = saveAndGet(server)
			}
		}

		if (server != null && server.status != 'resizing') {
			NutanixPrismElementSyncUtility.syncVirtualMachineInterfaces(morpheusContext, cloudItem.vm_nics as List<Map>, server, systemNetworks, netTypes)
		}
	}

	protected ComputeServer saveAndGet(ComputeServer server) {
		def saveSuccessful = morpheusContext.async.computeServer.bulkSave([server]).blockingGet()
		if (!saveSuccessful) {
			log.warn("Error saving server: ${server?.id}")
		}
		return morpheusContext.async.computeServer.get(server.id).blockingGet()
	}

	private static boolean updateVirtualMachineStats(ComputeServer server, Map vm) {
		def updates = false
		def usedStorage
		if(server.agentInstalled) {
			usedStorage = server.usedStorage ?: 0
		} else {
			usedStorage = vm.stats?.controller_user_bytes?.toLong() ?: 0
		}
		def cpuPercent = Math.min (100.0, (vm.stats.hypervisor_cpu_usage_ppm?.toLong() ?: 0) / 10000).toFloat()
		def maxMemory = vm.memoryCapacityInBytes?.toLong() ?: 0
		def usedMemory = (vm.stats && vm.stats['guest.memory_usage_bytes']) ? vm.stats['guest.memory_usage_bytes'].toLong() : 0
		//save it all
		ComputeCapacityInfo capacityInfo = server.getComputeCapacityInfo() ?: new ComputeCapacityInfo()

		if(capacityInfo.usedStorage != usedStorage || server.usedStorage != usedStorage) {
			capacityInfo.usedStorage = usedStorage
			server?.usedStorage = usedStorage
			updates = true
		}

		if(capacityInfo.maxMemory != maxMemory || server.maxMemory != maxMemory) {
			capacityInfo?.maxMemory = maxMemory
			server?.maxMemory = maxMemory
			updates = true
		}

		if(capacityInfo.usedMemory != usedMemory || server.usedMemory != usedMemory) {
			capacityInfo?.usedMemory = usedMemory
			server?.usedMemory = usedMemory
			updates = true
		}

		if(capacityInfo.maxCpu != cpuPercent || server.usedCpu != cpuPercent) {
			capacityInfo?.maxCpu = cpuPercent
			server?.usedCpu = cpuPercent
			updates = true
		}

		def powerState = capacityInfo.maxCpu > 0 ? ComputeServer.PowerState.on : ComputeServer.PowerState.off
		if(server.powerState != powerState) {
			server.powerState = powerState
			updates = true
		}

		if(updates == true) {
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
				if (networkIds) {
					morpheusContext.services.network.list(
						new DataQuery()
							.withFilter('refType', 'ComputeZone')
							.withFilter('refId', cloud.id)
							.withFilter('externalId', 'in', networkIds)
					)
				} else {
					[]
				}
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
			def currentParentServerId = currentServer.parentServer?.id
			if (parentId) {
				currentServer.parentServer = morpheusContext.services.computeServer.find(
					new DataQuery()
						.withFilter('cloud.id', cloud.id)
						.withFilter('externalId', parentId)
				)
			} else if (currentServer.parentServer) {
				currentServer.parentServer = null
			}

			if (currentParentServerId != currentServer.parentServer?.id) {
				save = true
			}

			if (currentServer.computeServerType?.guestVm) {
				NutanixPrismElementSyncUtility.updateServerContainersAndInstances(morpheusContext, plan, currentServer)
			}
			if (save) {
				currentServer = saveAndGet(currentServer)
			}

			performPostSaveSync(currentServer, matchedServer, systemNetworks)
		}
	}

	private static boolean shouldImportExistingVMs(importExisting) {
		importExisting == 'on' || importExisting == 'true' || importExisting == true
	}
}
