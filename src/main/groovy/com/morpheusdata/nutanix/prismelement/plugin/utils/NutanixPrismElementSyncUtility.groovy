package com.morpheusdata.nutanix.prismelement.plugin.utils

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.ComputeServerInterfaceType
import com.morpheusdata.model.Instance
import com.morpheusdata.model.NetAddress
import com.morpheusdata.model.Network
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.Workload
import com.morpheusdata.model.projection.StorageControllerIdentityProjection
import com.morpheusdata.nutanix.prismelement.plugin.provision.NutanixPrismElementProvisionProvider
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

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
		def ifaceTypes = NutanixPrismElementProvisionProvider.listComputeServerInterfaceTypes()
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

	static void syncVirtualMachineInterfaces(
		MorpheusContext morpheusContext,
		List<Map> nicList,
		ComputeServer server,
		List<Network> networks,
		Collection<ComputeServerInterfaceType> netTypes
	) {
		try {
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
					log.debug("Adding ${addItems?.size()} interfaces")
					def netInterfaces = []
					for (final def nicInfo in addItems) {
						def nicId = nicInfo.mac_address
						def network = networks.find { it.externalId == nicInfo.network_uuid }
						def nicPosition = nicList.indexOf(nicInfo) ?: 0
						def nicType = netTypes.find { it.code.startsWith('nutanix') && it.code.contains(nicInfo.adapter_type as String ?: 'virtio')}
						def netInterface = new ComputeServerInterface(
							externalId: nicId,
							name: generateNicName(server, nicPosition),
							network: network,
							type: nicType,
							macAddress: nicInfo.mac_address)

						if (nicInfo?.ip_address) {
							netInterface.addresses << new NetAddress(type: NetAddress.AddressType.IPV4, address: nicInfo?.ip_address)
						}
						netInterfaces << netInterface
					}

					if (netInterfaces) {
						// TODO: replace with newer api when fixed, use deprecated api for now
						morpheusContext.async.computeServer.computeServerInterface.create(netInterfaces, server).blockingGet()
					}
				}
				.onUpdate { updateItems ->
					log.debug("Updating ${updateItems?.size()} interfaces")
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

						def ipAddress = nicInfo?.ip_addresses?.first() ?: nicInfo?.ip_address
						if (ipAddress && !existingInterface.addresses.find {
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
						// TODO: replace with newer api when fixed, use deprecated api for now
						morpheusContext.services.computeServer.computeServerInterface.save(netInterfaces)
					}
				}
				.onDelete { deleteItems ->
					if (deleteItems) {
						log.debug("Removing ${deleteItems?.size()} interfaces")
						morpheusContext.async.computeServer.computeServerInterface.remove(deleteItems, server).blockingGet()
					}
				}.start()
		} catch (e) {
			log.error("error cacheVirtualMachineInterfaces${e}", e)
		}
	}

	static Map syncVirtualMachineVolumes(MorpheusContext morpheusContext, ComputeServer server, List<Map> diskList) {
		def rtn = [saveRequired: false, maxStorage: 0]
		try {
			// TODO: use FullModelSyncTask when a release is cut and propagated into morpheus-ui
			SyncTask<StorageVolume, Map, StorageVolume> syncTask = new SyncTask<>(
				morpheusContext.async.storageVolume.listById(server.volumes?.collect { it.id }),
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
					def deviceName = generateVolumeDeviceName(diskInfo)
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
							def storageVolumeType = morpheusContext.services.storageVolume.storageVolumeType.find(
								new DataQuery().withFilter('externalId', "nutanix_${addItem.disk_address.device_bus.toUpperCase()}")
							)

							if (storageVolumeType) {
								def maxStorage = addItem.size
								def deviceName = generateVolumeDeviceName(addItem)
								def datastore

								def volume = new StorageVolume(maxStorage: maxStorage, type: storageVolumeType, externalId: volumeId,
									unitNumber: addItem.disk_address.device_index, deviceName: "/dev/${deviceName}", name: volumeId,
									cloudId: server.cloud?.id, displayOrder: addItem.disk_address.device_index)

								if (addItem.storage_container_uuid) {
									volume.datastore = morpheusContext.services.cloud.datastore.find(
										new DataQuery()
											.withFilter('refType', 'ComputeZone')
											.withFilter('refId', server.cloud?.id)
											.withFilter('externalId', addItem.storage_container_uuid)
									)
								}
								volume.deviceDisplayName = deviceName
								if (volume.deviceDisplayName == 'sda') {
									volume.rootVolume = true
								}

								if (maxStorage) {
									rtn.maxStorage += maxStorage
								}
								disks << volume
							}
						}

					}

					if (disks) {
						def result = morpheusContext.async.storageVolume.create(disks, server).blockingGet()
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
						def deviceDisplayName = generateVolumeDeviceName(diskInfo)

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

						if (maxStorage) {
							rtn.maxStorage += maxStorage
						}
					}

					if (disks) {
						rtn.saveRequired = true
						// TODO: replace with newer api when fixed, use deprecated api for now
						morpheusContext.async.storageVolume.save(disks).blockingGet()
					}
				}
				.onDelete { deleteItems ->
					def disks = []
					for (final def existingVolume in deleteItems) {
						log.debug "removing volume ${existingVolume}"
						disks << existingVolume
					}

					if (disks) {
						// TODO: replace with newer api when fixed, use deprecated api for now
						morpheusContext.async.storageVolume.remove(disks, server, false).blockingGet()
						rtn.saveRequired = true
					}
				}.start()
		} catch (e) {
			log.error("error cacheVirtualMachineVolumes ${e}", e)
		}
		return rtn
	}
}
