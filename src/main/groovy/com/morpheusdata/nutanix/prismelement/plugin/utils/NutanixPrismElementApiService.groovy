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

import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.Cloud
import groovy.util.logging.Slf4j
import org.apache.http.client.utils.URIBuilder

/**
 * API service for interfacing with Nutanix Prism Element (NPE)
 *
 * Helpful links:
 * - What is each API version for? -- https://www.nutanix.dev/api-versions/
 */
@Slf4j
class NutanixPrismElementApiService {

	static standardApi = '/PrismGateway/services/rest/v1/'
	static v2Api = '/api/nutanix/v2.0/'

	static testConnection(HttpApiClient client, Map opts) {
		def rtn = [success: false, invalidLogin: false]
		try {
			def apiUrl = getNutanixApiUrl(opts.zone)
			def username = getNutanixUsername(opts.zone)
			def password = getNutanixPassword(opts.zone)
			def headers = buildHeaders(null, username, password)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
			def results = client.callJsonApi(apiUrl, v2Api + 'cluster', null, null, requestOpts, 'GET')
			rtn.success = results?.success && !results?.error

			if (!rtn.success) {
				rtn.invalidLogin = results.statusCode == 401
			}
		} catch (e) {
			log.error("error in testConnection: ${e}", e)
		}
		rtn
	}

	static toList(value) {
		[value].flatten()
	}

	static validateServerConfig(Map opts = [:]) {
		log.debug("validateServerConfig: ${opts}")
		def rtn = [success: false, errors: [:]]
		try {
			if (opts.networkId) {
				// great
			} else if (opts?.networkInterfaces) {
				// JSON (or Map from parseNetworks)
				log.debug("validateServerConfig networkInterfaces: ${opts?.networkInterfaces}")
				opts?.networkInterfaces?.eachWithIndex { nic, index ->
					def networkId = nic.network?.id ?: nic.network.group
					log.debug("network.id: ${networkId}")
					if (!networkId) {
						rtn.errors << [field: 'networkInterface', msg: 'Network is required']
					}
					if (nic.ipMode == 'static' && !nic.ipAddress) {
						rtn.errors = [field: 'networkInterface', msg: 'You must enter an ip address']
					}
				}
			} else if (opts?.networkInterface && !opts?.networkInterface?.network?.id instanceof String) {
				// UI params
				log.debug("validateServerConfig networkInterface: ${opts.networkInterface}")
				toList(opts?.networkInterface?.network?.id)?.eachWithIndex { networkId, index ->
					log.debug("network.id: ${networkId}")
					if (networkId?.length() < 1) {
						rtn.errors << [field: 'networkInterface', msg: 'Network is required']
					}
					if (networkInterface[index].ipMode == 'static' && !networkInterface[index].ipAddress) {
						rtn.errors = [field: 'networkInterface', msg: 'You must enter an ip address']
					}
				}
			} else {
				rtn.errors << [field: 'networkId', msg: 'Network is required']
			}
			if (opts.containsKey('imageId') && !opts.imageId)
				rtn.errors += [field: 'imageId', msg: 'You must choose an image']
			if (opts.containsKey('nodeCount') && (!opts.nodeCount || opts.nodeCount == '')) {
				rtn.errors += [field: 'config.nodeCount', msg: 'You must enter a Host Count']
				rtn.errors += [field: 'nodeCount', msg: 'You must enter a Host Count']
			}
			rtn.success = (rtn.errors.size() == 0)
			log.debug("validateServer results: ${rtn}")
		} catch (e) {
			log.error("error in validateServerConfig: ${e}", e)
		}
		return rtn
	}

	static listContainers(HttpApiClient client, Map authConfig) {
		def rtn = [success: false, containers: []]
		def apiMethod = 'GET'
		def apiPath = (v2Api + 'storage_containers')

		def headers = buildHeaders(null, authConfig.username, authConfig.password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(authConfig.apiUrl, apiPath, null, null, requestOpts, apiMethod)
		rtn.success = results?.success && !results?.error
		if (rtn.success) {
			rtn.results = results.data
			results.data?.entities?.each { entity ->
				rtn.containers << [
					id: entity.id,
					uuid: entity.storage_container_uuid,
					clusterUuid: entity.cluster_uuid,
					name: entity.name,
				    maxStorage: entity.max_capacity,
				    replicationFactor: entity.replication_factor,
				    freeStorage: entity?.usage_stats?.'storage.free_bytes'
				]
			}
			log.debug("listContainers: ${rtn}")
		}
		return rtn
	}

	static listImages(HttpApiClient client, Map authConfig) {
		def rtn = [success: false, images: []]

		//call the api
		def headers = buildHeaders(null, authConfig.username, authConfig.password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(authConfig.apiUrl, v2Api + 'images', null, null, requestOpts, 'GET')
		rtn.success = results?.success && !results?.error
		if (rtn.success == true) {
			results.data?.entities?.each { entity ->
				def row = [uuid       : entity.uuid, name: entity.name, imageStatus: entity.image_state, vmDiskId: entity.vm_disk_id, externalId: entity.vm_disk_id,
						   containerId: entity.storage_container_id, containerUuid: entity.storage_container_uuid, deleted: entity.deleted, timestamp: entity.logical_timestamp]
				row.imageType = (entity.image_type == 'DISK_IMAGE' || entity.image_type?.toLowerCase() == 'disk' || entity.image_type == null) ? 'qcow2' : 'iso'
				rtn.images << row
			}
			log.debug("listImages: ${rtn.images}")
		}
		return rtn
	}

	static findImage(HttpApiClient client, opts, name) {
		def rtn = [success: false, image: null]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(apiUrl, v2Api + 'images', null, null, requestOpts, 'GET')
		if (results.success) {
			rtn.results = results.data
			rtn.results.entities?.each { entity ->
				log.debug("find image entity: ${entity} for: ${name}")
				if (rtn.success == false) {
					if (entity.name == name) {
						rtn.image = entity
						rtn.success = true
					}
				}
			}
			log.debug("results: ${rtn.results}")
		}
		return rtn
	}

	static checkImageId(HttpApiClient client, opts, imageId) {
		try {
			if (findImageId(client, opts, imageId)?.success) {
				return imageId
			}
		} catch (ex) {
			log.error("Error checking for imageId", ex)
		}
		return null
	}

	static findImageId(HttpApiClient client, opts, imageId) {
		def rtn = [success: false, image: null]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(apiUrl, v2Api + 'images', null, null, requestOpts, 'GET')
		if (results.success) {
			rtn.results = results.data
			rtn.results.entities?.each { entity ->
				log.debug("find image entity: ${entity} for: ${name}")
				if (rtn.success == false) {
					if (entity.vm_disk_id == imageId) {
						rtn.image = entity
						rtn.success = true
					}
				}
			}
			log.debug("results: ${rtn.results}")
		}
		return rtn
	}

	static loadImage(HttpApiClient client, opts, imageId) {
		def rtn = [success: false, image: null]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(apiUrl, v2Api + 'images/' + imageId + '/', null, null, requestOpts, 'GET')
		log.debug("results: ${results}")
		if (results.success == true) {
			rtn.image = results.data
			rtn.success = results.success
		}
		return rtn
	}

static getTask(HttpApiClient client, Cloud cloud, taskId) {
		def rtn = [success: false]
		def apiUrl = getNutanixApiUrl(cloud)
		def username = getNutanixUsername(cloud)
		def password = getNutanixPassword(cloud)
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(apiUrl, v2Api + 'tasks/' + taskId, null, null, requestOpts, 'GET')
		rtn.success = results?.success && !results?.error

		if (rtn.success == true) {
			rtn.results = results.data //new groovy.json.JsonSlurper().parseText(results.content)
			log.debug("task results: ${rtn.results}")
		} else if (results?.error) {
			rtn.errorCode = results.errorCode
			rtn.error == true
		}
		return rtn
	}

	static listVirtualMachinesV1(HttpApiClient client, Map authConfig, Map opts) {
		def rtn = [success: false, virtualMachines: [], total: 0]
		try {
			def apiPath = standardApi + 'vms'
			def headers = buildHeaders(null, authConfig.username, authConfig.password)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
			//page it
			def results = client.callJsonApi(authConfig.apiUrl, apiPath, null, null, requestOpts, 'GET')
			if (results.success == true) {
				results.data?.entities?.each { row ->
					def obj = row
					obj.externalId = row.uuid
					rtn.virtualMachines << obj
				}
				rtn.success = true
			}
		} catch (e) {
			log.error("error listing virtual machines: ${e}", e)
		}
		return rtn
	}

	static listVirtualMachinesV2(HttpApiClient client, Map authConfig, Map opts) {
		def rtn = [success: false, virtualMachines: [], total: 0]
		try {
			def apiPath = v2Api + 'vms'
			def headers = buildHeaders(null, authConfig.username.toString(), authConfig.password.toString())

			def query = [include_vm_disk_config: 'true', include_vm_nic_config: 'true']
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, queryParams: query)
			//get v1 data
			def vmList = listVirtualMachinesV1(client, authConfig, opts)
			def results = client.callJsonApi(authConfig.apiUrl.toString(), apiPath, null, null, requestOpts, 'GET')
			//page it
			def keepGoing = true
			if (results.success == true) {
				results.data?.entities?.each { row ->
					def obj = row
					obj.externalId = row.uuid
					obj.legacyVm = vmList.virtualMachines.find { it.externalId == obj.externalId }
					//log.debug("legacyVm: ${obj.legacyVm}")
					rtn.virtualMachines << obj
				}
				rtn.total = rtn.virtualMachines?.size()
				rtn.success = true
			} else {
				rtn.msg = results.msg
				rtn.success = false
			}

		} catch (e) {
			log.error("error listing virtual machines: ${e}", e)
		}
		return rtn
	}

	static listSnapshotsV2(HttpApiClient client, Map authConfig, Map opts) {
		def rtn = [success: false, snapshots: [], total: 0]
		try {
			def apiPath = v2Api + 'snapshots'
			def headers = buildHeaders(null, authConfig.username, authConfig.password)

			def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
			def results = client.callJsonApi(authConfig.apiUrl, apiPath, null, null, requestOpts, 'GET')
			//page it
			def keepGoing = true
			if (results.success == true) {
				results.data?.entities?.each { row ->
					def obj = row

					rtn.snapshots << obj
				}
				rtn.total = rtn.snapshots?.size()
				rtn.success = true
			} else {
				rtn.msg = results.msg
				rtn.success = false
			}

		} catch (e) {
			log.error("error listing virtual machines: ${e}", e)
		}
		return rtn
	}


	static findVirtualMachine(HttpApiClient client, opts, name) {
		def rtn = [success: false, virtualMachine: null]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(apiUrl, v2Api + 'vms', null, null, requestOpts, 'GET')
		if (results.success) {
			rtn.results = results.data
			rtn.results.entities?.each { entity ->
				log.debug("find vm entity: ${entity} for: ${name}")
				if (rtn.success == false) {
					if (entity.name == name) {
						rtn.virtualMachine = entity
						rtn.success = true
					}
				}
			}
			log.debug("findVirtualMachines results: ${rtn.results}")
		}
		return rtn
	}

	static findVirtualMachineId(HttpApiClient client, opts, vmId) {
		def rtn = [success: false, virtualMachine: null]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(apiUrl, v2Api + 'vms/', null, null, requestOpts, 'GET')
		if (results.success == true) {
			rtn.results = results.data
			rtn.results.entities?.each { entity ->
				log.debug("find vm entity: ${entity} for: ${vmId}")
				if (rtn.success == false) {
					if (entity.uuid == vmId) {
						rtn.virtualMachine = entity
						rtn.success = true
					}
				}
			}
			log.debug("findVirtualMachineId results: ${rtn.results}")
		}
		return rtn
	}

	static loadVirtualMachine(HttpApiClient client, opts, vmId) {
		def rtn = [success: false, virtualMachine: null]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)

		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers, queryParams: [include_vm_disk_config: 'true', include_vm_nic_config: 'true'])
		def results = client.callJsonApi(apiUrl, v2Api + 'vms/' + vmId, null, null, requestOpts, 'GET')

		if (results.success && !results.error) {
			rtn.results = results.data
			rtn.success = results.success
		}
		return rtn
	}

	static getVirtualMachineDisks(HttpApiClient client, Cloud cloud, vmId) {
		def rtn = [success: false, disks: []]
		def apiUrl = getNutanixApiUrl(cloud)
		def username = getNutanixUsername(cloud)
		def password = getNutanixPassword(cloud)
		def query = [include_vm_disk_config: 'true']
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers, queryParams: query)
		// v2 api doesn't let you get just the disks
		def results = client.callJsonApi(apiUrl, v2Api + 'vms/' + vmId, null, null, requestOpts, 'GET')
		if (results.success == true) {
			rtn.success = true
			rtn.results = results.data //new groovy.json.JsonSlurper().parseText(results.content)
			def disk_info = rtn.results.vm_disk_info
			log.debug("getVirtualMachineDisks results: ${disk_info}")
			rtn.results.vm_disk_info?.each { entity ->
				rtn.disks << entity
			}
		}
		return rtn
	}

	static getVirtualMachineNics(HttpApiClient client, cloud, vmId) {
		def rtn = [success: false, nics: []]
		def apiUrl = getNutanixApiUrl(cloud)
		def username = getNutanixUsername(cloud)
		def password = getNutanixPassword(cloud)
		def query = [includeAddressAssignments: 'true']
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers, queryParams: query)
		def results = client.callJsonApi(apiUrl, v2Api + 'vms/' + vmId + '/nics', null, null, requestOpts, 'GET')
		if (results.success == true) {
			rtn.success = true
			rtn.results = results.data
			log.debug("getVirtualMachineNics results: ${rtn.results}")
			rtn.results.entities?.each { entity ->
				rtn.nics << entity
			}
		}
		return rtn
	}

	static listNetworks(HttpApiClient client, Map opts) {
		def rtn = [success: false]
		try {
			def apiUrl = getNutanixApiUrl(opts.zone)
			def username = getNutanixUsername(opts.zone)
			def password = getNutanixPassword(opts.zone)
			def headers = buildHeaders(null, username.toString(), password.toString())
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
			def results = client.callJsonApi(apiUrl, v2Api + 'networks/', null, null, requestOpts, 'GET')
			rtn.success = results?.success && !results?.error
			if (rtn.success == true) {
				rtn.results = results.data //new groovy.json.JsonSlurper().parseText(results.content)
				log.debug("network results: ${rtn.results}")
			}
		} catch (e) {
			log.error("error listing networks: ${e}", e)
		}
		return rtn
	}

	static listHosts(HttpApiClient client, Map authConfig) {
		def rtn = [success: false, hosts: [], total: 0]
		try {
			def apiPath = v2Api + 'hosts'
			def headers = buildHeaders(null, authConfig.username, authConfig.password)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
			//page it
			def results = client.callJsonApi(authConfig.apiUrl, apiPath, null, null, requestOpts, 'GET')
			if (results.success == true) {
				rtn.results = results.data?.entities
				rtn.success = true
			}
		} catch (e) {
			log.error("error listing hosts: ${e}", e)
		}
		return rtn
	}

	static uploadImage(HttpApiClient client, opts) {
		def rtn = [success: false]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		def imageUrl
		def image = opts.image
		if (image.imageUrl) {
			imageUrl = image.imageUrl
		} else if (image.imageFile) {
			def imageFile = image.imageFile
			imageUrl = imageFile.getURL(new Date(new Date().time + 20l * 60000l)).toString()
		}
		log.debug "uploadImage imageUrl: ${imageUrl}"
		imageUrl = imageUrl.replaceAll(" ", "%20")
		def containerId = opts.containerId
		def body = [name: image.name, image_type: image.imageType == 'iso' ? 'iso' : 'disk_image', image_import_spec: [storage_container_uuid: containerId, url: imageUrl]]
		log.info("Upload Image Body: ${body}")
		//cache
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
		def imageUploadUrl = v2Api + "images"
		def results = client.callJsonApi(apiUrl, imageUploadUrl, null, null, requestOpts, 'POST')
		log.debug("uploadImage: ${results}")
		rtn.success = results?.success && !results?.error
		if (rtn.success == true) {
			rtn.results = results.data
			rtn.taskUuid = rtn.results.task_uuid
			log.debug("results: ${rtn.results}")
		}
		return rtn
	}

	static cloneVmToImage(HttpApiClient client, Map authConfig, Map cloneConfig) {
		def rtn = [success: false]
		//api auth
		def apiUrl = authConfig.apiUrl
		def username = authConfig.username
		def password = authConfig.password
		//config
		def imageName = cloneConfig.name
		def snapshotId = cloneConfig.snapshotId
		//get the snapshot
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def snapshotResults = client.callJsonApi(apiUrl, v2Api+ 'snapshots/' + snapshotId, null, null, requestOpts, 'GET')
		println("snapshotResults: ${snapshotResults}")
		if (snapshotResults?.success && !snapshotResults?.error) {
			def snapshotInfo = snapshotResults.data
			log.debug("snapshot info: ${rtn.results}")
			def vmDisks = snapshotInfo.vm_create_spec.vm_disks?.findAll { it.is_cdrom != true }
			println("disks: ${vmDisks}")
			def vmDiskId = vmDisks?.size() > 0 ? vmDisks[0].vm_disk_clone?.disk_address?.vmdisk_uuid : null
			def containerId = vmDisks?.size() > 0 ? vmDisks[0].vm_disk_clone?.storage_container_uuid : null
			rtn.containerId = containerId
			//throw error if null
			//groupUuid
			def body = [
				name       : imageName,
				imageType  : 'DISK_IMAGE',
				vm_disk_clone: [
					container_uuid  : containerId,
					snapshot_group_id: snapshotInfo.group_uuid,
					vm_disk_uuid     : vmDiskId
				]
			]
			log.info("clone to template body: ${body}")
			//clone to template
			def results = client.callJsonApi(apiUrl, v2Api + 'images', null, null, requestOpts + [body: body], 'POST')
			log.debug("cloneVmToImage: ${results}")
			rtn.success = results?.success && !results?.error
			if (rtn.success == true) {
				rtn.results = results.data //new groovy.json.JsonSlurper().parseText(results.content)
				rtn.taskUuid = rtn.results.task_uuid
				log.debug("results: ${rtn.results}")
			} else if (results?.error) {
				rtn.errorCode = results.error_code
				rtn.error == true
			}
		} else if (snapshotResults?.error) {
			rtn.errorCode = snapshotResults.error_code
			rtn.error == true
		}
		return rtn
	}

	static createServer(HttpApiClient client, Map opts) {
		def rtn = [success: false]
		if (!opts.imageId) {
			rtn.error = 'Please specify an image type'
		} else if (!opts.name) {
			rtn.error = 'Please specify a name'
		} else {
			rtn = createServerUsingV2Api(client, opts)
		}
		return rtn
	}

	private static createServerUsingV2Api(HttpApiClient client, Map opts) {
		def rtn = [success: false]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		def containerId = opts.containerId
		def osDiskSize = (long) opts.maxStorage
		def maxMemory = (int) opts.maxMemory.div(ComputeUtility.ONE_MEGABYTE)

		def osDisk = [vm_disk_clone: [disk_address: [vmdisk_uuid: opts.imageId], minimum_size: osDiskSize]]
		def rootType = opts.rootVolume?.type?.name
		def diskTypes = [rootType]
		if (rootType == 'sata') {
			osDisk.disk_address = osDisk.disk_address ?: [:]
			osDisk.disk_address['device_bus'] = 'SATA'
		} else if (rootType == 'ide') {
			osDisk.disk_address = osDisk.disk_address ?: [:]
			osDisk.disk_address['device_bus'] = 'IDE'
		} else if (rootType == 'scsi') {
			osDisk.disk_address = osDisk.disk_address ?: [:]
			osDisk.disk_address['device_bus'] = 'SCSI'
		}
		def vmDisks = [osDisk]
		if (opts.dataDisks?.size() > 0) {
			opts.dataDisks?.each { disk ->
				def diskContainerId
				if (disk.datastore?.externalId) {
					diskContainerId = disk.datastore.externalId
				}
				def dataDiskSize = (long) disk.maxStorage
				def vmDataDisk = [vm_disk_create: [size: dataDiskSize, storage_container_uuid: diskContainerId ?: containerId]]
				def diskType = disk.type?.name
				diskTypes << diskType
				if (rootType == 'sata') {
					vmDataDisk.disk_address = vmDataDisk.disk_address ?: [:]
					vmDataDisk.disk_address['device_bus'] = 'SATA'
				} else if (rootType == 'ide') {
					vmDataDisk.disk_address = vmDataDisk.disk_address ?: [:]
					vmDataDisk.disk_address['device_bus'] = 'IDE'
				} else if (rootType == 'scsi') {
					vmDataDisk.disk_address = vmDataDisk.disk_address ?: [:]
					vmDataDisk.disk_address['device_bus'] = 'SCSI'
				}
				vmDisks << vmDataDisk
			}
		}
		diskTypes = diskTypes?.unique()
		if (opts.cloudFileId)
			vmDisks << [is_cdrom: true, vm_disk_clone: [disk_address: [vmdisk_uuid: opts.cloudFileId], minimum_size: (ComputeUtility.ONE_MEGABYTE)]]
		//def cloudInitDisk = [vmDiskClone:[vmDiskUuid:1]]
		def vmNics = []
		//nic network
		if (opts.networkConfig?.primaryInterface?.network?.uniqueId) { //new style multi network
			def vmNic = [network_uuid: opts.networkConfig.primaryInterface.network.uniqueId]
			if (opts.networkConfig?.primaryInterface?.ipAddress && opts.networkConfig?.primaryInterface?.network?.type?.code?.contains('Managed')) {
				vmNic.requested_ip_address = opts.networkConfig.primaryInterface.ipAddress
				vmNic.request_ip = true
			}
			if (opts.networkConfig.primaryInterface.type?.code == 'nutanix.E1000') {
				vmNic.model = 'e1000'
			}
			vmNics << vmNic
			//extra networks
			opts.networkConfig.extraInterfaces?.each { extraInterface ->
				if (extraInterface.network?.uniqueId) {
					vmNic = [network_uuid: extraInterface.network?.uniqueId]
					if (extraInterface?.ipAddress && extraInterface?.network?.type?.code?.contains('Managed')) {
						vmNic.requested_ip_address = extraInterface.ipAddress
						vmNic.request_ip = true
					}
					if (extraInterface.type?.code == 'nutanix.E1000') {
						vmNic.model = 'e1000'
					}
					vmNics << vmNic
				}
			}
		}

		//create request body
		def numVcpus = ((opts.maxCores ?: 1) / (opts.coresPerSocket ?: 1)).toLong()
		def body = [memory_mb         : ((int) maxMemory),
					name              : opts.name,
					num_vcpus         : numVcpus,
					num_cores_per_vcpu: opts.coresPerSocket ?: 1,
					vm_nics           : vmNics,
					vm_disks          : vmDisks
		]
		if (opts.cloudConfig) {
			body.vm_customization_config = [
				userdata: opts.cloudConfig
			]
		}
		if (opts.uuid)
			body.uuid = opts.uuid
		//set the boot config if more than one bus types
		if (diskTypes.size() > 1 || opts.uefi == true) {
			body.boot = [boot_device_type: 'disk', disk_address: [device_bus: rootType, device_index: 0]]
		}
		if (opts.uefi == true) {
			body.boot = body.boot ?: [:]
			body.boot['secure_boot'] = true
			body.boot['uefi_boot'] = true
			body.machine_type = "Q35"
		}
		log.debug("create server body: ${body}")

		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
		def results = client.callJsonApi(apiUrl, v2Api + 'vms', null, null, requestOpts, 'POST')
		log.info("createServer: ${results}")
		//rtn.success = results?.success && results?.error != true
		if (results.success == true && results.data?.task_uuid) {
			def taskId = results.data.task_uuid
			def taskResults = checkTaskReady(client, opts.zone, taskId)
			if (taskResults.success != true && taskResults.errorCode == 500 && opts.uuid) {
				def vmCheckResults = checkVmReady(client, opts, opts.uuid)
				if (vmCheckResults.success == true)
					taskResults = [success: true, error: false, results: [entityList: [[uuid: opts.uuid]], entity_list: [[entity_id: opts.uuid]]]]
			}
			if (taskResults.success && !taskResults.error) {
				def serverId = taskResults.results.entity_list[0].entity_id
				def serverResults = findVirtualMachineId(client, opts, serverId)
				if (serverResults.success == true) {
					def vm = serverResults?.virtualMachine
					if (vm) {
						rtn.taskUuid = taskId
						rtn.results = vm
						rtn.serverId = vm.uuid
						rtn.completeTime = taskResults.completeTime
						rtn.success = true
					}
				} else {
					rtn.msg = 'could not find template'
				}
			} else {
				rtn.msg = 'task failed'
			}
		} else {
			log.error("error creating nutanix vm: ${results}")
			rtn.msg = 'error creating nutanix vm'
		}
		return rtn
	}

	static updateServer(HttpApiClient client, Map opts) {
		log.debug("updateServer ${opts}")
		def rtn = [success: false]
		if (!opts.serverId) {
			rtn.error = 'Please specify a Server ID'
		} else {
			def apiUrl = getNutanixApiUrl(opts.zone)
			def username = getNutanixUsername(opts.zone)
			def password = getNutanixPassword(opts.zone)
			def maxMemory = opts.maxMemory.div(ComputeUtility.ONE_MEGABYTE)
			// In the nutanix api vcpu == socket b/c one vcpu per socket
			def maxVcpus = ((opts.maxCores ?: 1) / (opts.coresPerSocket ?: 1)).toLong()
			def body = [memoryMb       : maxMemory,
						numVcpus       : maxVcpus,
						numCoresPerVcpu: (opts.coresPerSocket ?: 1)
			]
			log.info("resize server body: ${body}")
			def headers = buildHeaders(null, username.toString(), password.toString())
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
			def results = client.callJsonApi(apiUrl, v2Api + 'vms/' + opts.serverId, null, null, requestOpts, 'PUT')
			log.info("updateServer: ${results}")
			if (results.success == true && results.data) {
				def taskId = results.data.task_uuid
				def taskResults = checkTaskReady(client, opts.zone, taskId)
				if (taskResults.success && !taskResults.error) {
					rtn.taskUuid = taskId
					rtn.success = true
				} else {
					rtn.msg = 'resize failed'
				}
			}
		}
		return rtn
	}

	static resizeDisk(HttpApiClient client, cloud, vmId, nutanixDiskToResize, Long sizeBytes) {
		log.debug("resizeDisk ${cloud}, vm:${vmId}, disk:${nutanixDiskToResize}")
		def rtn = [success: false]
		if (!vmId) {
			rtn.error = 'Please specify a VM ID'
		} else if (!nutanixDiskToResize) {
			rtn.error = 'Please provide a disk definition to resize to'
		} else {
			def apiUrl = getNutanixApiUrl(cloud)
			def username = getNutanixUsername(cloud)
			def password = getNutanixPassword(cloud)
			nutanixDiskToResize.vm_disk_create = [size: sizeBytes, storage_container_uuid: nutanixDiskToResize.storage_container_uuid]
			def body = [vm_disks: [nutanixDiskToResize]]
			log.info("resize disk body: ${body}")
			def headers = buildHeaders(null, username, password)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
			def results = client.callJsonApi(apiUrl, v2Api + 'vms/' + vmId + '/disks/update', null, null, requestOpts, 'PUT')
			log.info("resizeDisk results: ${results}")
			if (results.success == true && results.data) {
				def taskId = results.data.task_uuid
				def taskResults = checkTaskReady(client, cloud, taskId)
				if (taskResults.success && !taskResults.error) {
					rtn.taskUuid = taskId
					rtn.success = true
				} else {
					rtn.msg = 'resize disk failed'
				}
			}
		}
		return rtn
	}


	static ejectDisk(HttpApiClient client, opts, vmId, diskAddress) {
		log.debug("ejectDisk ${opts}, vm:${vmId}")
		def rtn = [success: false]
		if (!vmId) {
			rtn.error = 'Please specify a VM ID'
		} else if (!diskAddress) {
			rtn.error = 'Please specify a disk address'
		} else {
			def apiUrl = getNutanixApiUrl(opts.zone)
			def username = getNutanixUsername(opts.zone)
			def password = getNutanixPassword(opts.zone)
			def diskToUpdate = [
				disk_address: diskAddress,
				is_empty: true,
			]
			def body = [vm_disks: [diskToUpdate]]
			log.info("eject disk body: ${body}")
			def headers = buildHeaders(null, username, password)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
			def results = client.callJsonApi(apiUrl, v2Api + 'vms/' + vmId + '/disks/update' , null, null, requestOpts, 'PUT')
			log.info("ejectDisk results: ${results}")
			if (results.success == true && results.data) {
				def taskId = results.data.task_uuid
				def taskResults = checkTaskReady(client, opts.zone, taskId)
				if (taskResults.success && !taskResults.error) {
					rtn.taskUuid = taskId
					rtn.success = true
				} else {
					rtn.msg = 'eject disk failed'
				}
			}
		}
		return rtn
	}

	static addDisk(HttpApiClient client, Map opts, vmId, sizeGB, type) {
		log.debug("addDisk ${opts}, vm:${vmId}")
		def rtn = [success: false]
		if (!vmId) {
			rtn.error = 'Please specify a VM ID'
		} else if (!sizeGB) {
			rtn.error = 'Please specify a disk size'
		} else {
			def apiUrl = getNutanixApiUrl(opts.zone)
			def username = getNutanixUsername(opts.zone)
			def password = getNutanixPassword(opts.zone)
			def containerId = opts.containerId
			def diskSize = (int) sizeGB * ComputeUtility.ONE_GIGABYTE
			def vmDisks = []
			vmDisks << [vm_disk_create: [size: diskSize, storage_container_uuid: containerId], disk_address: [device_bus: type]]
			def body = [vm_disks: vmDisks]
			log.info("add disk body: ${body}")
			def headers = buildHeaders(null, username.toString(), password.toString())
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
			def results = client.callJsonApi(apiUrl, v2Api + 'vms/' + vmId + '/disks/attach', null, null, requestOpts, 'POST')

			log.info("addDisk results: ${results}")

			if (results.success == true && results.data) {
				def taskId = results.data.task_uuid
				def taskResults = checkTaskReady(client, opts.zone, taskId)
				if (taskResults.success && !taskResults.error) {
					rtn.taskUuid = taskId
					rtn.success = true
				} else {
					rtn.msg = 'add disk failed'
				}
			}
		}
		return rtn
	}

	static addNic(HttpApiClient client, opts, vmId) {
		log.debug("addNic ${opts}, vm:${vmId}")
		def rtn = [success: false]
		if (!vmId) {
			rtn.error = 'Please specify a VM ID'
		} else {
			def apiUrl = getNutanixApiUrl(opts.zone)
			def username = getNutanixUsername(opts.zone)
			def password = getNutanixPassword(opts.zone)
			def vmNics = []
			def vmNic = [network_uuid: opts.networkUuid]
			if (opts.ipAddress) {
				vmNic.request_ip = true
				vmNic.requested_ip_address = opts.ipAddress
			}
			vmNics << vmNic
			def body = [spec_list: vmNics]
			log.info("add nic body: ${body}")
			def headers = buildHeaders(null, username, password)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
			def results = client.callJsonApi(apiUrl, v2Api + 'vms/' + vmId + '/nics', null, null, requestOpts, 'POST')

			log.info("addNic results: ${results}")
			if (results.success == true && results.data) {
				def taskId = results.data.task_uuid
				def taskResults = checkTaskReady(client, opts.zone, taskId)
				if (taskResults.success && !taskResults.error) {
					rtn.taskUuid = taskId
					rtn.success = true
				} else {
					rtn.msg = 'add nic failed'
				}
			}
		}
		return rtn
	}

	static addCdrom(HttpApiClient client, opts, vmId, cloudFileId, addr = null) {
		log.debug("addDisk ${opts}, vm:${vmId}")
		def rtn = [success: false]
		if (!vmId) {
			rtn.error = 'Please specify a VM ID'
		} else {
			def apiUrl = getNutanixApiUrl(opts.zone)
			def username = getNutanixUsername(opts.zone)
			def password = getNutanixPassword(opts.zone)

			def headers = buildHeaders(null, username, password)
			def vmDisks = []
			vmDisks << [
				is_cdrom: true,
				disk_address: addr,
				vm_disk_clone: [
					disk_address: [
						vmdisk_uuid: cloudFileId
					],
					minimum_size: ComputeUtility.ONE_MEGABYTE
				]
			]
			def body = [
				disks: vmDisks
			]
			log.info("add disk body: ${body}")
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
			def results = client.callJsonApi(apiUrl, v2Api + 'vms/' + vmId + '/disks', null, null, requestOpts, 'POST')

			log.info("addDisk results: ${results}")
			if (results.success == true && results.data) {
				def taskId = results.data.task_uuid
				def taskResults = checkTaskReady(client, opts.zone, taskId)
				if (taskResults.success && !taskResults.error) {
					rtn.taskUuid = taskId
					rtn.success = true
				} else {
					rtn.msg = 'add disk failed'
				}
			}
		}
		return rtn
	}

	static deleteDisk(HttpApiClient client, cloud, vmId, nutanixDiskObject) {
		log.debug("deleteServerDisk ${nutanixDiskObject}")
		def rtn = [success: false]
		if (!vmId) {
			rtn.error = 'Please specify a VM ID'
		} else if (!nutanixDiskObject) {
			rtn.error = 'Please provide a disk to delete'
		} else {
			def apiUrl = getNutanixApiUrl(cloud)
			def username = getNutanixUsername(cloud)
			def password = getNutanixPassword(cloud)
			def headers = buildHeaders(null, username, password)
			def body = [vm_disks: [nutanixDiskObject]]
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
			def results = client.callJsonApi(apiUrl, v2Api + 'vms/' + vmId + '/disks/detach', null, null, requestOpts, 'POST')

			log.info("deleteDisk: ${results}")
			if (results.success == true && results.data) {
				def taskId = results.data.task_uuid
				def taskResults = checkTaskReady(client, cloud, taskId)
				if (taskResults.success && !taskResults.error) {
					rtn.taskUuid = taskId
					rtn.success = true
				} else {
					rtn.msg = 'delete disk failed'
				}
			}
		}
		return rtn
	}

	static deleteNic(HttpApiClient client, opts, vmId, nicAddress) {
		log.debug("deleteNic ${opts}")
		def rtn = [success: false]
		if (!vmId) {
			rtn.error = 'Please specify a VM ID'
		} else if (!nicAddress) {
			rtn.error = 'Please specify a nic address'
		} else {
			def apiUrl = getNutanixApiUrl(opts.zone)
			def username = getNutanixUsername(opts.zone)
			def password = getNutanixPassword(opts.zone)
			def headers = buildHeaders(null, username, password)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers)

			//requires MAC Address
			if (opts.macAddress) {
				nicAddress = opts.macAddress
			}
			def results = client.callJsonApi(apiUrl, v2Api + 'vms/' + vmId + '/nics/' + nicAddress, null, null, requestOpts, 'DELETE')

			log.info("deleteNic: ${results}")
			if (results.success == true && results.data) {
				def taskId = results.data.taskUuid ?: results.data.task_uuid
				def taskResults = checkTaskReady(client, opts.zone, taskId)
				if (taskResults.success && !taskResults.error) {
					rtn.taskUuid = taskId
					rtn.success = true
				} else {
					rtn.msg = 'delete nic failed'
				}
			}
		}
		return rtn
	}

	static getConsoleUrl(HttpApiClient client, opts, vmId) {
		try {
			def apiUrl = getNutanixApiUrl(opts.zone)
			def username = getNutanixUsername(opts.zone)
			def password = getNutanixPassword(opts.zone)

			HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions()
			requestOpts.ignoreSSL = true
			requestOpts.headers = [
				'Accept': 'text/html',
			]
			requestOpts.body = [
				'j_username': username,
				'j_password': password,
			]
			def resp = client.callApi(apiUrl, "/PrismGateway/j_spring_security_check", null, null, requestOpts)
			if (resp.success) {
				def sessionCookie = resp.getCookie('JSESSIONID')
				if (sessionCookie != null) {

					def apiURL = new URI(apiUrl)
					return [success: true, url: "wss://${apiURL.host}:${apiURL.port}/vnc/vm/${vmId}/proxy", sessionCookie: sessionCookie]
				}
			}
		} catch (ex) {
			log.error("nutanix exception: ${ex.message}", ex)
		}
	}

	static cloneServer(HttpApiClient client, Map opts) {
		log.debug("cloneServer ${opts}")
		def rtn = [success: false]
		if (!opts.serverId && !opts.snapshotId) {
			rtn.error = 'Please specify a VM or Snapshot to clone'
		} else if (!opts.name) {
			rtn.error = 'Please specify a name for new VM'
		} else {
			def apiUrl = getNutanixApiUrl(opts.zone)
			def username = getNutanixUsername(opts.zone)
			def password = getNutanixPassword(opts.zone)
			def specs = [name: opts.name]
			if (opts.uuid)
				specs.uuid = opts.uuid
			def specList = [specs]
			def body = [spec_list: specList]
			// CloudInit is defined for /vms/{uuid}/clone but not /snapshots/{uuid}/clone
			if (opts.cloudConfig && opts.serverId) {
				body.vm_customization_config = [
					userdata: opts.cloudConfig
				]
				//if sysprep - its not an iso install
				if (opts.platform == 'platform')
					body.vm_customization_config.fresh_install = false
			}
			log.info("clone server body: ${body}")
			def results = [success: false]
			def headers = buildHeaders(null, username.toString(), password.toString())
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)

			if (opts.snapshotId) {
				log.debug("cloning from snapshot ${opts.snapshotId}")
				results = client.callJsonApi(apiUrl, v2Api + 'snapshots/' + opts.snapshotId + '/clone', null, null, requestOpts, 'POST')
			} else if (opts.serverId) {
				log.debug("cloning from server ${opts.serverId}")
				results = client.callJsonApi(apiUrl, v2Api + 'vms/' + opts.serverId + '/clone', null, null, requestOpts, 'POST')
			}
			log.info("cloneServer: ${results}")
			if (results.success == true) {
				def taskId = results.data.task_uuid
				def taskResults = checkTaskReady(client, opts.zone as Cloud, taskId)
				if (taskResults.success != true && taskResults.errorCode == 500 && opts.uuid) {
					def vmCheckResults = checkVmReady(client, opts, opts.uuid)
					if (vmCheckResults.success == true)
						taskResults = [success: true, error: false, results: [entityList: [[uuid: opts.uuid]], entity_lst: [[entity_id: opts.uuid]]]]
				}
				if (taskResults.success && !taskResults.error) {
					def serverResults = findVirtualMachine(client, opts, opts.name)
					if (serverResults.success == true) {
						def vm = serverResults?.virtualMachine
						if (vm) {
							if (opts.cloudFileId) {
								log.debug("CDROM Detected on Nutanix Clone, Swapping out cloud init file!")
								def cdromDisk = getVirtualMachineDisks(client, opts.zone, vm.uuid)?.disks?.find { it.is_cdrom }
								if (cdromDisk) {
									deleteDisk(client, opts.zone, vm.uuid, cdromDisk)
								}
								addCdrom(client, opts, vm.uuid, opts.cloudFileId, cdromDisk?.disk_address ?: ['device_bus': 'IDE', 'device_index': 0])
							}
							rtn.taskUuid = taskId
							rtn.results = vm
							rtn.serverId = vm.uuid
							rtn.completeTime = taskResults.completeTime
							rtn.success = true
						}
					} else {
						rtn.msg = 'could not find template'
					}
				} else {
					rtn.msg = 'task failed'
				}
			}
		}
		return rtn
	}

	static startVm(HttpApiClient client, opts, serverId) {
		def rtn = [success: false]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)

		def headers = buildHeaders(null, username, password)
		def body = [
			transition: "ON",
			vm_logical_timestamp: (opts.timestamp ?: 1)
		]
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
		def results = client.callJsonApi(apiUrl, v2Api + 'vms/' + serverId + '/set_power_state', null, null, requestOpts, 'POST')
		log.debug("startVm: ${results}")
		if (results.success == true && results.data) {
			def taskId = results.data.task_uuid
			def taskResults = checkTaskReady(client, opts.zone, taskId)
			def taskSuccess = taskResults.success && (!taskResults.error || taskResults.results?.metaResponse?.error == 'kInvalidState')
			if (taskSuccess == true) {
				rtn.taskUuid = taskId
				rtn.success = true
			} else {
				rtn.msg = 'power on failed'
			}
		}
		return rtn
	}

	static stopVm(HttpApiClient client, opts, serverId) {
		def rtn = [success: false]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		//loadVirtualMachine and check power status. Nutanix fails task if VM already powered off.
		def vmResult = loadVirtualMachine(client, opts, serverId)
		if (vmResult?.success) {
			if (vmResult.results?.power_state && vmResult.results?.power_state?.toLowerCase() != "off") {
				def headers = buildHeaders(null, username, password)
				def body = [
					transition: "OFF",
					logicalTimestamp: (opts.timestamp ?: 1)
				]
				def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
				def results = client.callJsonApi(apiUrl, v2Api + 'vms/' + serverId + '/set_power_state', null, null, requestOpts, 'POST')
				log.debug("stopVm: ${results}")
				if (results.success == true && results.data) {
					def taskId = results.data.task_uuid
					def taskResults = checkTaskReady(client, opts.zone, taskId)
					def taskSuccess = taskResults.success && (!taskResults.error || taskResults.results?.metaResponse?.error == 'kInvalidState')
					if (taskSuccess == true) {
						rtn.taskUuid = taskId
						rtn.success = true
					} else {
						rtn.success = false
						rtn.msg = 'power off failed'
					}
				}
			} else {
				//powered off so do nothing
				rtn.success = true
			}
		} else {
			rtn.msg = "VM not found: ${serverId}"
		}
		return rtn
	}

	static deleteServer(HttpApiClient client, opts, serverId) {
		def rtn = [success: false]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		//cache
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(apiUrl, v2Api + 'vms/' + serverId , null, null, requestOpts, 'DELETE')
		log.debug("deleteVm: ${results}")
		if (results.success == true && results.data) {
			def taskId = results.data.task_uuid
			def taskResults = checkTaskReady(client, opts.zone, taskId)
			if (taskResults.success && !taskResults.error) {
				rtn.taskUuid = taskId
				rtn.success = true
			} else {
				rtn.msg = 'delete failed'
			}
		}
		return rtn
	}

	static deleteImage(HttpApiClient client, opts, imageId) {
		def rtn = [success: false]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		//cache
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(apiUrl, v2Api + 'images/' + imageId, null, null, requestOpts, 'DELETE')
		log.debug("deleteImage: ${results}")
		if (results.success == true && results.data) {
			def taskId = results.data.task_uuid
			def taskResults = checkTaskReady(client, opts.zone, taskId)
			if (taskResults.success && !taskResults.error) {
				rtn.taskUuid = taskId
				rtn.success = true
			} else {
				rtn.msg = 'delete failed'
			}
		}
		return rtn
	}


	static getSnapshot(HttpApiClient client, opts, snapshotId) {
		def rtn = [success: false]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)

		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(apiUrl, v2Api + 'snapshots/' + snapshotId, null, null, requestOpts, 'GET')

		rtn.success = results?.success && !results?.error
		if (rtn.success == true) {
			rtn.results = results.data
			log.debug("task results: ${rtn.results}")
		} else if (results?.error) {
			rtn.errorCode = results.errorCode
			rtn.error == true
		}
		return rtn
	}

	static createSnapshot(HttpApiClient client, opts) {
		def rtn = [success: false]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		def vmUuid
		def vmResult = loadVirtualMachine(client, opts, opts.vmId)
		if (vmResult?.success) {
			vmUuid = vmResult.results?.uuid
			def body = [snapshot_specs: [[vm_uuid: vmUuid, snapshot_name: opts.snapshotName]]]
			log.info("Create snapshot body: ${body}")
			def headers = buildHeaders(null, username, password)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
			def results = client.callJsonApi(apiUrl, v2Api + 'snapshots', null, null, requestOpts, 'POST')

			rtn.success = results?.success && !results?.error
			if (rtn.success == true) {
				rtn.results = [taskUuid: results.data.task_uuid]
				log.trace("createSnapshot: ${rtn.results}")
			}
		} else {
			rtn.msg = "VM not found: ${opts.vmId}"
		}
		return rtn
	}

	static restoreSnapshot(HttpApiClient client, opts) {
		def rtn = [success: false]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		def vmUuid
		def vmResult = loadVirtualMachine(client, opts, opts.vmId)
		if (vmResult?.success) {
			vmUuid = vmResult.results?.uuid

			def body = [restore_network_configuration: true, snapshot_uuid: opts.snapshotId, uuid: vmUuid]
			log.info("Restore snapshot body: ${body}")
			def headers = buildHeaders(null, username, password)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
			def results = client.callJsonApi(apiUrl, v2Api + 'vms/' + vmUuid + '/restore', null, null, requestOpts, 'POST')
			rtn.success = results?.success && !results?.error
			if (rtn.success == true) {
				rtn.results = results.data
				log.info("restoreSnapshot: ${rtn.results}")
			}
		} else {
			rtn.msg = "VM not found: ${opts.vmId}"
		}
		return rtn
	}

	static deleteSnapshot(HttpApiClient client, opts, snapshotId) {
		def rtn = [success: false]
		def apiUrl = getNutanixApiUrl(opts.zone)
		def username = getNutanixUsername(opts.zone)
		def password = getNutanixPassword(opts.zone)
		def headers = buildHeaders(null, username, password)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(apiUrl, v2Api + 'snapshots/' + snapshotId, null, null, requestOpts, 'DELETE')
		log.debug("deleteSnapshot: ${results}")
		if (results.success == true && results.data) {
			def taskId = results.data.task_uuid
			def taskResults = checkTaskReady(client, opts.zone, taskId)
			if (taskResults.success && !taskResults.error) {
				rtn.taskUuid = taskId
				rtn.success = true
			} else {
				rtn.msg = 'delete failed'
			}
		}
		return rtn
	}

	static checkIpv4Ip(ipAddress) {
		def rtn = false
		if (ipAddress) {
			if (ipAddress.indexOf('.') > 0 && !ipAddress.startsWith('169'))
				rtn = true
		}
		return rtn
	}

	static checkServerReady(HttpApiClient client, opts, vmId) {
		def rtn = [success: false]
		try {
			def pending = true
			def attempts = 0
			while (pending) {
				sleep(1000l * 20l)
				def serverDetail = loadVirtualMachine(client, opts, vmId)
				log.debug("serverDetail: ${serverDetail}")
				if (serverDetail.success == true
					&& serverDetail.results.power_state == 'on'
					&& hasIpAddress(serverDetail.results)) {
						rtn.success = true
						rtn.results = serverDetail.results
						rtn.ipAddresses = serverDetail.results.vm_nics?.collect {
							it.ip_addresses
						}?.flatten()?.findAll {
							checkIpv4Ip(it)
						}
						pending = false
				}
				attempts++
				if (attempts > 60)
					pending = false
			}
		} catch (e) {
			log.error("An Exception Has Occurred", e)
		}
		return rtn
	}

	static boolean hasIpAddress(Map vm) {
		vm?.vm_nics?.collect {
			it.ip_addresses
		}?.flatten()
		?.any {
			checkIpv4Ip(it)
		}
	}

	static Map insertContainerImage(HttpApiClient client, opts) {
		def rtn = [success: false]
		log.info("insertContainerImage: ${opts}")
		def image = opts.image
		def matchResults = findImage(client, opts, image.name)
		def match = matchResults.image
		if (match) {
			log.debug("using found image")
			rtn.imageId = match.uuid
			rtn.imageDiskId = match.vm_disk_id
			rtn.success = true
		} else {
			log.debug("inserting image")
			def createResults = uploadImage(client, opts)
			if (createResults.success == true) {
				//wait here?
				def taskId = createResults.taskUuid
				def taskResults = checkTaskReady(client, opts.zone, taskId)
				if (taskResults.success && !taskResults.error) {
					def imageId = taskResults.results.entity_list[0].entity_id
					def imageResults = findImage(client, opts, image.name)
					if (imageResults.success == true) {
						def vmImage = imageResults?.image
						if (vmImage) {
							rtn.imageId = vmImage.uuid
							rtn.imageDiskId = vmImage.vm_disk_id
							rtn.success = true
						}
					} else {
						rtn.msg = 'could not find template'
					}
				} else {
					//if https lets try at http - nutanix validates certs
					def imageUrl = image.imageUrl ?: image.imageFile.getURL(new Date(new Date().time + 20l * 60000l)).toString()
					if (imageUrl.startsWith('https')) {
						imageUrl = imageUrl.replaceAll('https', 'http')
						opts.image.imageUrl = imageUrl
						return insertContainerImage(client, opts)
					} else {
						rtn.msg = 'task failed'
					}
				}
			}
		}
		return rtn
	}

	static checkTaskReady(HttpApiClient client, Cloud cloud, taskId) {
		def rtn = [success: false]
		try {
			if (taskId == null)
				return rtn
			def pending = true
			def attempts = 0
			while (pending) {
				sleep(1000l * 10l)
				def taskDetail = getTask(client, cloud, taskId)
				log.info("taskDetail - ${taskId}: ${taskDetail}")
				if (taskDetail.success == true && taskDetail.results.progress_status) {
					if (taskDetail.results.progress_status == 'Succeeded') {
						rtn.success = true
						rtn.results = taskDetail.results
						pending = false
					} else if (taskDetail.results.progress_status == 'Failure' || taskDetail.results.progress_status == 'Failed') {
						rtn.error = true
						rtn.results = taskDetail.results
						rtn.success = true
						pending = false
					}
				} else if (taskDetail.errorCode == 500) {
					log.warn("task api call - 500 error (bug in nutanix)")
					rtn.errorCode = taskDetail.errorCode
					pending = false
				}
				attempts++
				if (attempts > 350)
					pending = false
			}
		} catch (e) {
			log.error("An Exception Has Occurred", e)
		}
		return rtn
	}

	static checkVmReady(HttpApiClient client, opts, vmId) {
		def rtn = [success: false]
		try {
			if (vmId == null)
				return rtn
			def pending = true
			def attempts = 0
			while (pending) {
				sleep(1000l * 10l)
				def vmDetails = loadVirtualMachine(client, opts, vmId)
				log.debug("vmDetails - ${vmId}: ${vmDetails}")
				if (vmDetails.success == true && vmDetails.results?.power_state) {
					rtn.success = true
					pending = false
				}
				attempts++
				if (attempts > 150)
					pending = false
			}
		} catch (e) {
			log.error("An Exception Has Occurred", e)
		}
		return rtn
	}

	//v3
	static buildHeaders(Map headers, String username, String password) {
		headers = (headers ?: [:]) + ['Content-Type': 'application/json;', 'Accept': 'application/json']
		if (username && password) {
			def creds = "${username}:${password}"
			headers['Authorization'] = "Basic ${creds.getBytes().encodeBase64().toString()}".toString()
		}
		return headers
	}

	//utils
	static getNutanixApiUrl(Cloud cloud) {
		def apiUrl = cloud.serviceUrl ?: cloud.getConfigMap()?.apiUrl
		if (apiUrl) {
			if (apiUrl.startsWith('http')) {
				URIBuilder uriBuilder = new URIBuilder("${apiUrl}")
				uriBuilder.setPath('')
				return uriBuilder.build().toString()
			}
			return 'https://' + apiUrl + ':9440'
		}
		throw new Exception('no nutanix api url specified')
	}

	static getNutanixUsername(Cloud cloud) {
		def rtn = cloud.serviceUsername ?: cloud.accountCredentialData?.username ?: cloud.getConfigProperty('username')
		if (!rtn) {
			throw new Exception('no nutanix username specified')
		}
		return rtn
	}

	static getNutanixPassword(Cloud cloud) {
		def rtn = cloud.servicePassword ?: cloud.accountCredentialData?.password ?: cloud.getConfigProperty('password')
		if (!rtn) {
			throw new Exception('no nutanix password specified')
		}
		return rtn
	}


}
