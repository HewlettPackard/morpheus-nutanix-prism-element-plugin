package com.morpheusdata.nutanix.prismelement.plugin.utils

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.Instance
import com.morpheusdata.model.NetAddress
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.Workload
import com.morpheusdata.model.projection.StorageControllerIdentityProjection
import com.morpheusdata.nutanix.prismelement.plugin.NutanixPrismElementPluginProvisionProvider
import groovy.util.logging.Slf4j

@Slf4j
class NutanixPrismElementSyncUtility {
	static StorageVolume buildStorageVolume(
		MorpheusContext context,
		Account account,
		ComputeServer server,
		Map volume
	) {
		def storageVolume = new StorageVolume()
		storageVolume.deviceName = volume.deviceName
		storageVolume.name = volume.name ?: storageVolume.deviceName
		storageVolume.deviceDisplayName = volume.deviceDisplayName?: storageVolume.deviceName
		storageVolume.account = account
		storageVolume.maxStorage = (Long) (volume.sizeId ? context.services.accountPrice.get(volume.sizeId.toLong())?.matchValue : (volume.size?.toLong() * ComputeUtility.ONE_GIGABYTE ?: volume.maxStorage?.toLong()))
		StorageVolumeType storageType
		if(volume.storageType)
			storageType = context.services.storageVolume.storageVolumeType.get(volume.storageType?.toLong())
		else
			storageType = context.services.storageVolume.storageVolumeType.find(
				new DataQuery().withFilter('code', 'standard')
			)
		storageVolume.type = storageType
		if(storageType.configurableIOPS) {
			storageVolume.maxIOPS = volume.maxIOPS ? volume.maxIOPS.toInteger() : null
		}
		storageVolume.rootVolume = (volume.rootVolume == true || volume.rootVolume == 'true' || volume.rootVolume == 'on' || volume.rootVolume == 'yes')
		if(volume.datastoreId) {
			Long dsId = volume.datastoreId as Long
			storageVolume.datastoreOption = dsId
			storageVolume.datastore = context.services.cloud.datastore.get(dsId)
			if (storageVolume.datastore) {
				storageVolume.storageServer = context.services.storageServer.get(dsId)
			}
			storageVolume.refType = 'Datastore'
			storageVolume.refId = storageVolume.datastore?.id
		}
		if(volume.controller) {
			if(volume.controller instanceof StorageControllerIdentityProjection)
				storageVolume.controller = volume.controller as StorageControllerIdentityProjection
		}
		if(volume.controllerKey)
			storageVolume.controllerKey = volume.controllerKey
		if(volume.externalId)
			storageVolume.externalId = volume.externalId
		if(volume.internalId)
			storageVolume.internalId = volume.internalId
		if(volume.unitNumber)
			storageVolume.unitNumber = volume.unitNumber

		storageVolume.cloudId = server.cloud?.id
		storageVolume.diskIndex = volume.index.toInteger()
		storageVolume.removable = storageVolume.rootVolume != true
		storageVolume.displayOrder = volume.displayOrder?.toInteger() ?: server?.volumes?.size() ?: 0
		return storageVolume
	}

	static String generateVolumeDeviceName(diskInfo) {
		def deviceName = ''
		def diskAddr = diskInfo.disk_address
		def diskType = diskAddr.device_bus.toUpperCase()
		Integer diskIndex = diskAddr.device_index
		// existing behavior, may need to fix for nvme/virtio
		if (diskType == 'SCSI' ||
			diskType == 'SATA') {
			deviceName += 'sd'
		} else {
			deviceName += 'hd'
		}
		def letterIndex = ''
		do {
			letterIndex += (char)((int)'a' + diskIndex % 26)
			diskIndex /= 26
		} while (diskIndex / 26 > 0)

		// letterIndex was built little-endian, gotta swap it
		return deviceName + letterIndex.reverse()
	}

	static String generateNicName(ComputeServer server, Integer index) {
		String nicName
		if(index == 0 && server.sourceImage?.interfaceName) {
			nicName = server.sourceImage?.interfaceName
		} else {
			if(server.platform == 'windows') {
				nicName = (index == 0) ? 'Ethernet' : 'Ethernet ' + (index + 1)
			} else if(server.platform == 'linux') {
				nicName = "eth${index}"
			} else {
				nicName = "eth${index}"
			}
		}

		return nicName
	}

	static def buildComputeServerInterface(
		MorpheusContext context,
		ComputeServer server,
		networkInterface
	) {
		def index = server.interfaces.size()
		def computeServerInterface = new ComputeServerInterface()
		computeServerInterface.name =  networkInterface.nicName ?: generateNicName(server, index)
		computeServerInterface.displayOrder = index
		computeServerInterface.macAddress = networkInterface.macAddress
		computeServerInterface.ipMode = networkInterface.ipMode
		computeServerInterface.replaceHostRecord = (
			networkInterface.replaceHostRecord == true ||
				networkInterface.replaceHostRecord  == 'true' ||
				networkInterface.replaceHostRecord  == 'on' ||
				networkInterface.replaceHostRecord  == 'yes'
		)
		computeServerInterface.primaryInterface = networkInterface.isPrimary != null ? networkInterface.isPrimary : index == 0
		def ifaceTypes = NutanixPrismElementPluginProvisionProvider.listComputeServerInterfaceTypes()
		// If we got a type id use that to find the type, otherwise just find the type marked as default
		def matchFn = networkInterface.networkInterfaceTypeId ?
			{ it.code == networkInterface.networkInterfaceTypeId.toLong() } : { it.defaultType == true }
		computeServerInterface.type = ifaceTypes.find {matchFn}
		if(networkInterface.externalId) {
			computeServerInterface.externalId = networkInterface.externalId
		}
		if(networkInterface.externalType) {
			computeServerInterface.externalType = networkInterface.externalType
		}
		computeServerInterface.network = context.services.network.get(networkInterface.id.toLong())
		if (networkInterface.subnet) {
			computeServerInterface.subnet = context.services.networkSubnet.get(networkInterface.subnet.toLong())
		}
		if(computeServerInterface.network) {
			computeServerInterface.networkDomain = computeServerInterface.network.networkDomain
		}
		if((computeServerInterface.subnet?.pool?.id ?: computeServerInterface.network?.pool?.id) && (computeServerInterface.ipMode != 'dhcp' || !computeServerInterface.ipMode)) {
			def poolId = computeServerInterface.subnet?.pool?.id ?: computeServerInterface.network?.pool?.id
			computeServerInterface.networkPool = context.services.network.pool.get(poolId.toLong())
			computeServerInterface.dhcp = computeServerInterface.networkPool?.dhcpServer
			if(computeServerInterface.ipMode != 'pool') {
				if( networkInterface.ipAddress) {
					computeServerInterface.publicIpAddress = networkInterface.ipAddress
					computeServerInterface.addresses.add(new NetAddress(type:NetAddress.AddressType.IPV4, address: networkInterface.ipAddress))
				}
				computeServerInterface.dhcp = false
			}
		}
		def networkOrSubnet = computeServerInterface.subnet ?: computeServerInterface.network
		if(networkOrSubnet?.dhcpServer && (computeServerInterface.ipMode == 'dhcp' || !computeServerInterface.ipMode)) {
			computeServerInterface.dhcp = true
			if(!(networkInterface instanceof ComputeServerInterface) && networkInterface.forceIpSet?.ipAddress ) {
				computeServerInterface.publicIpAddress = networkInterface.forceIpSet.publicIpAddress
				computeServerInterface.addresses.add(new NetAddress(type:NetAddress.AddressType.IPV4, address:networkInterface.forceIpSet.ipAddress))
			}
		} else if(!(networkInterface instanceof ComputeServerInterface) && networkInterface.ipAddress ) {

			computeServerInterface.publicIpAddress = networkInterface.ipAddress
			computeServerInterface.addresses.add(new NetAddress(type:NetAddress.AddressType.IPV4, address: networkInterface.ipAddress))

			computeServerInterface.dhcp = false
		}
		return computeServerInterface
	}

	private static getWorkloadsForServer(ComputeServer currentServer, MorpheusContext morpheusContext) {
		def workloads = []
		def projections = morpheusContext.async.cloud.listCloudWorkloadProjections(currentServer.cloud.id).filter { it.serverId == currentServer.id }.toList().blockingGet()
		for(proj in projections) {
			workloads << morpheusContext.services.workload.get(proj.id)
		}
		workloads
	}

	static updateServerContainersAndInstances(MorpheusContext context, ServicePlan plan, ComputeServer currentServer) {
		log.debug "updateServerContainersAndInstances: ${currentServer}"
		try {
			def instanceIds = []
			def workloads = getWorkloadsForServer(currentServer, context)
			for(Workload workload in workloads) {
				def update = false
				if (plan) {
					workload.plan = plan
					update = true
				}
				if (
					workload.maxCores != currentServer.maxCores ||
					workload.maxMemory != currentServer.maxMemory ||
					workload.coresPerSocket != currentServer.coresPerSocket ||
					workload.maxStorage != currentServer.maxStorage
				) {
					workload.maxCores = currentServer.maxCores
					workload.maxMemory = currentServer.maxMemory
					workload.coresPerSocket = currentServer.coresPerSocket
					workload.maxStorage = currentServer.maxStorage
					update = true
				}
				if (update) {
					def instanceId = workload.instance?.id
					context.services.workload.save(workload)

					if(instanceId) {
						instanceIds << instanceId
					}
				}
			}
			if(instanceIds) {
				def instancesToSave = []
				def instances = context.services.instance.listById(instanceIds)
				instances.each { Instance instance ->
					if(plan || instance.plan.code == 'terraform.default') {
						if (instance.containers.every { cnt -> (cnt.plan?.id == currentServer.plan.id &&
							cnt.maxMemory == currentServer.maxMemory &&
							cnt.maxCores == currentServer.maxCores &&
							cnt.coresPerSocket == currentServer.coresPerSocket) || cnt.server.id == currentServer.id }) {
							log.debug("Changing Instance Plan To : ${plan?.name} - memory: ${currentServer.maxMemory} for ${instance.name} - ${instance.id}")
							if(plan) {
								instance.plan = plan
							}
							instance.maxCores = currentServer.maxCores
							instance.maxMemory = currentServer.maxMemory
							instance.maxStorage = currentServer.maxStorage
							instance.coresPerSocket = currentServer.coresPerSocket
							instancesToSave << instance
						}
					}
				}
				if(instancesToSave.size() > 0) {
					context.services.instance.bulkSave(instancesToSave)
				}
			}
		} catch(e) {
			log.error "Error in updateServerContainersAndInstances: ${e}", e
		}
	}
}
