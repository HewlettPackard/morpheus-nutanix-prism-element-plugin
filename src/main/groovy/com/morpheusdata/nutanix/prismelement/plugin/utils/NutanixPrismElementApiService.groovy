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

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.AccountCredential
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

	static testConnection(HttpApiClient client, RequestConfig reqConfig) {
		def rtn = [success: false, invalidLogin: false]
		try {
			def headers = buildHeaders(null)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
			def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'cluster', reqConfig.username, reqConfig.password, requestOpts, 'GET')
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

	static listContainers(HttpApiClient client, RequestConfig reqConfig) {
		def rtn = [success: false, containers: []]

		def headers = buildHeaders(null)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'storage_containers', reqConfig.username, reqConfig.password, requestOpts, 'GET')
		rtn.success = results?.success && !results?.error
		if (rtn.success) {
			rtn.results = results.data
			results.data?.entities?.each { entity ->
				rtn.containers << [
					id: entity.id,
					uuid: entity.storage_container_uuid,
					clusterUuid: entity.cluster_uuid,
					name: entity.name,
				    maxStorage: entity?.usage_stats?.'storage.capacity_bytes',
				    replicationFactor: entity.replication_factor,
				    freeStorage: entity?.usage_stats?.'storage.free_bytes'
				]
			}
			log.debug("listContainers: ${rtn}")
		}
		return rtn
	}

	static listImages(HttpApiClient client, RequestConfig reqConfig) {
		def rtn = [success: false, images: []]

		//call the api
		def headers = buildHeaders(null)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'images', reqConfig.username, reqConfig.password, requestOpts, 'GET')
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

	static findImage(HttpApiClient client, RequestConfig reqConfig, name) {
		def rtn = [success: false, image: null]

		def headers = buildHeaders(null)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'images', reqConfig.username, reqConfig.password, requestOpts, 'GET')
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

	static checkImageId(HttpApiClient client, RequestConfig reqConfig, imageId) {
		try {
			if (findImageId(client, reqConfig, imageId)?.success) {
				return imageId
			}
		} catch (ex) {
			log.error("Error checking for imageId", ex)
		}
		return null
	}

	static findImageId(HttpApiClient client, RequestConfig reqConfig, imageId) {
		def rtn = [success: false, image: null]
		def headers = buildHeaders(null)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'images', reqConfig.username, reqConfig.password, requestOpts, 'GET')
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

	static loadImage(HttpApiClient client, RequestConfig reqConfig, imageId) {
		def rtn = [success: false, image: null]
		def headers = buildHeaders(null)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'images/' + imageId + '/', reqConfig.username, reqConfig.password, requestOpts, 'GET')
		log.debug("results: ${results}")
		if (results.success == true) {
			rtn.image = results.data
			rtn.success = results.success
		}
		return rtn
	}

	static getTask(HttpApiClient client, RequestConfig reqConfig, taskId) {
		def rtn = [success: false]

		def headers = buildHeaders(null)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'tasks/' + taskId, reqConfig.username, reqConfig.password, requestOpts, 'GET')
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

	static listVirtualMachinesV1(HttpApiClient client, RequestConfig reqConfig) {
		def rtn = [success: false, virtualMachines: [], total: 0]
		try {
			def headers = buildHeaders(null)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
			//page it
			def results = client.callJsonApi(reqConfig.apiUrl, standardApi + 'vms', reqConfig.username, reqConfig.password, requestOpts, 'GET')
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

	static listVirtualMachinesV2(HttpApiClient client, RequestConfig reqConfig) {
		def rtn = [success: false, virtualMachines: [], total: 0]
		try {
			def apiPath = v2Api + 'vms'
			def headers = buildHeaders(null)

			def query = [include_vm_disk_config: 'true', include_vm_nic_config: 'true']
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, queryParams: query)
			//get v1 data
			def vmList = listVirtualMachinesV1(client, reqConfig)
			def results = client.callJsonApi(reqConfig.apiUrl, apiPath, reqConfig.username, reqConfig.password, requestOpts, 'GET')
			//page it
			def keepGoing = true
			if (results.success == true) {
				results.data?.entities?.each { row ->
					def obj = row
					obj.externalId = row.uuid
					obj.legacyVm = vmList.virtualMachines.find { it.externalId == obj.externalId }
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

	static listSnapshotsV2(HttpApiClient client, RequestConfig reqConfig) {
		def rtn = [success: false, snapshots: [], total: 0]
		try {
			def headers = buildHeaders(null)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
			def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'snapshots', reqConfig.username, reqConfig.password, requestOpts, 'GET')
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

	static findVirtualMachine(HttpApiClient client, RequestConfig reqConfig, name) {
		def rtn = [success: false, virtualMachine: null]
		def headers = buildHeaders(null)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'vms', reqConfig.username, reqConfig.password, requestOpts, 'GET')
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

	static findVirtualMachineId(HttpApiClient client, RequestConfig reqConfig, vmId) {
		def rtn = [success: false, virtualMachine: null]
		def headers = buildHeaders(null)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'vms/', reqConfig.username, reqConfig.password, requestOpts, 'GET')
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

	static loadVirtualMachine(HttpApiClient client, RequestConfig reqConfig, vmId) {
		def rtn = [success: false, virtualMachine: null]
		def headers = buildHeaders(null)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers, queryParams: [include_vm_disk_config: 'true', include_vm_nic_config: 'true'])
		def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'vms/' + vmId, reqConfig.username, reqConfig.password, requestOpts, 'GET')

		if (results.success && !results.error) {
			rtn.results = results.data
			rtn.success = results.success
		}
		return rtn
	}

	static getVirtualMachineDisks(HttpApiClient client, RequestConfig reqConfig, vmId) {
		def rtn = [success: false, disks: []]
		def query = [include_vm_disk_config: 'true']
		def headers = buildHeaders(null)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers, queryParams: query)
		// v2 api doesn't let you get just the disks
		def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'vms/' + vmId, reqConfig.username, reqConfig.password, requestOpts, 'GET')
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

	static getVirtualMachineNics(HttpApiClient client, RequestConfig reqConfig, vmId) {
		def rtn = [success: false, nics: []]
		def query = [includeAddressAssignments: 'true']
		def headers = buildHeaders(null)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers, queryParams: query)
		def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'vms/' + vmId + '/nics', reqConfig.username, reqConfig.password, requestOpts, 'GET')
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

	static listNetworks(HttpApiClient client, RequestConfig reqConfig) {
		def rtn = [success: false]
		try {
			def headers = buildHeaders(null)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
			def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'networks/', reqConfig.username, reqConfig.password, requestOpts, 'GET')
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

	static listHosts(HttpApiClient client, RequestConfig reqConfig) {
		def rtn = [success: false, hosts: [], total: 0]
		try {
			def apiPath = v2Api + 'hosts'
			def headers = buildHeaders(null)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
			//page it
			def results = client.callJsonApi(reqConfig.apiUrl, apiPath, reqConfig.username, reqConfig.password, requestOpts, 'GET')
			if (results.success == true) {
				rtn.results = results.data?.entities
				rtn.success = true
			}
		} catch (e) {
			log.error("error listing hosts: ${e}", e)
		}
		return rtn
	}

	static uploadImage(HttpApiClient client, RequestConfig reqConfig, opts) {
		def rtn = [success: false]
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
		def headers = buildHeaders(null)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
		def imageUploadUrl = v2Api + "images"
		def results = client.callJsonApi(reqConfig.apiUrl, imageUploadUrl, reqConfig.username, reqConfig.password, requestOpts, 'POST')
		log.debug("uploadImage: ${results}")
		rtn.success = results?.success && !results?.error
		if (rtn.success == true) {
			rtn.results = results.data
			rtn.taskUuid = rtn.results.task_uuid
			log.debug("results: ${rtn.results}")
		}
		return rtn
	}

	static cloneVmToImage(HttpApiClient client, RequestConfig reqConfig, Map cloneConfig) {
		def rtn = [success: false]
		//config
		def imageName = cloneConfig.name
		def snapshotId = cloneConfig.snapshotId
		//get the snapshot
		def headers = buildHeaders(null)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def snapshotResults = client.callJsonApi(reqConfig.apiUrl, v2Api+ 'snapshots/' + snapshotId, reqConfig.username, reqConfig.password, requestOpts, 'GET')
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
				image_type  : 'DISK_IMAGE',
				vm_disk_clone: [
					container_uuid  : containerId,
					snapshot_group_id: snapshotInfo.group_uuid,
					vm_disk_uuid     : vmDiskId
				]
			]
			log.info("clone to template body: ${body}")
			requestOpts.body = body
			//clone to template
			def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'images', reqConfig.username, reqConfig.password, requestOpts, 'POST')
			log.debug("cloneVmToImage: ${results}")
			rtn.success = results?.success && !results?.error
			if (rtn.success == true) {
				rtn.results = results.data
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

	static createServer(HttpApiClient client, RequestConfig reqConfig, Map opts) {
		def rtn = [success: false]
		if (!opts.imageId) {
			rtn.error = 'Please specify an image type'
		} else if (!opts.name) {
			rtn.error = 'Please specify a name'
		} else {
			rtn = createServerUsingV2Api(client, reqConfig, opts)
		}
		return rtn
	}

	private static createServerUsingV2Api(HttpApiClient client, RequestConfig reqConfig, Map opts) {
		def rtn = [success: false]
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

		def headers = buildHeaders(null)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
		def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'vms', reqConfig.username, reqConfig.password, requestOpts, 'POST')
		log.info("createServer: ${results}")
		//rtn.success = results?.success && results?.error != true
		if (results.success == true && results.data?.task_uuid) {
			def taskId = results.data.task_uuid
			def taskResults = checkTaskReady(client, reqConfig, taskId)
			if (taskResults.success != true && taskResults.errorCode == 500 && opts.uuid) {
				def vmCheckResults = checkVmReady(client, reqConfig, opts.uuid)
				if (vmCheckResults.success == true)
					taskResults = [success: true, error: false, results: [entityList: [[uuid: opts.uuid]], entity_list: [[entity_id: opts.uuid]]]]
			}
			if (taskResults.success && !taskResults.error) {
				def serverId = taskResults.results.entity_list[0].entity_id
				def serverResults = findVirtualMachineId(client, reqConfig, serverId)
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

	static updateServer(HttpApiClient client, RequestConfig reqConfig, Map opts) {
		log.debug("updateServer ${opts}")
		def rtn = [success: false]
		if (!opts.serverId) {
			rtn.error = 'Please specify a Server ID'
		} else {
			def maxMemory = opts.maxMemory.div(ComputeUtility.ONE_MEGABYTE)
			// In the nutanix api vcpu == socket b/c one vcpu per socket
			def maxVcpus = ((opts.maxCores ?: 1) / (opts.coresPerSocket ?: 1)).toLong()
			def body = [memory_mb       : maxMemory,
						num_vcpus       : maxVcpus,
						num_cores_per_vcpu: (opts.coresPerSocket ?: 1)
			]
			log.info("resize server body: ${body}")
			def headers = buildHeaders(null)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
			def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'vms/' + opts.serverId, reqConfig.username, reqConfig.password, requestOpts, 'PUT')
			log.info("updateServer: ${results}")
			if (results.success == true && results.data) {
				def taskId = results.data.task_uuid
				def taskResults = checkTaskReady(client, reqConfig, taskId)
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

	static resizeDisk(HttpApiClient client, RequestConfig reqConfig, vmId, nutanixDiskToResize, Long sizeBytes) {
		log.debug("resizeDisk vm:${vmId}, disk:${nutanixDiskToResize}")
		def rtn = [success: false]
		if (!vmId) {
			rtn.error = 'Please specify a VM ID'
		} else if (!nutanixDiskToResize) {
			rtn.error = 'Please provide a disk definition to resize to'
		} else {
			nutanixDiskToResize.vm_disk_create = [size: sizeBytes, storage_container_uuid: nutanixDiskToResize.storage_container_uuid]
			def body = [vm_disks: [nutanixDiskToResize]]
			log.info("resize disk body: ${body}")
			def headers = buildHeaders(null)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
			def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'vms/' + vmId + '/disks/update', reqConfig.username, reqConfig.password, requestOpts, 'PUT')
			log.info("resizeDisk results: ${results}")
			if (results.success == true && results.data) {
				def taskId = results.data.task_uuid
				def taskResults = checkTaskReady(client, reqConfig, taskId)
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


	static ejectDisk(HttpApiClient client, RequestConfig reqConfig, vmId, diskAddress) {
		log.debug("ejectDisk vm:${vmId}")
		def rtn = [success: false]
		if (!vmId) {
			rtn.error = 'Please specify a VM ID'
		} else if (!diskAddress) {
			rtn.error = 'Please specify a disk address'
		} else {
			def diskToUpdate = [
				disk_address: diskAddress,
				is_empty: true,
			]
			def body = [vm_disks: [diskToUpdate]]
			log.info("eject disk body: ${body}")
			def headers = buildHeaders(null)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
			def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'vms/' + vmId + '/disks/update' , reqConfig.username, reqConfig.password, requestOpts, 'PUT')
			log.info("ejectDisk results: ${results}")
			if (results.success == true && results.data) {
				def taskId = results.data.task_uuid
				def taskResults = checkTaskReady(client, reqConfig, taskId)
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

	static addDisk(HttpApiClient client, RequestConfig reqConfig, Map opts, vmId, sizeGB, type) {
		log.debug("addDisk ${opts}, vm:${vmId}")
		def rtn = [success: false]
		if (!vmId) {
			rtn.error = 'Please specify a VM ID'
		} else if (!sizeGB) {
			rtn.error = 'Please specify a disk size'
		} else {
			def containerId = opts.containerId
			def diskSize = (int) sizeGB * ComputeUtility.ONE_GIGABYTE
			def vmDisks = []
			vmDisks << [vm_disk_create: [size: diskSize, storage_container_uuid: containerId], disk_address: [device_bus: type]]
			def body = [vm_disks: vmDisks]
			log.info("add disk body: ${body}")
			def headers = buildHeaders(null)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
			def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'vms/' + vmId + '/disks/attach', reqConfig.username, reqConfig.password, requestOpts, 'POST')

			log.info("addDisk results: ${results}")

			if (results.success == true && results.data) {
				def taskId = results.data.task_uuid
				def taskResults = checkTaskReady(client, reqConfig, taskId)
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

	static addNic(HttpApiClient client, RequestConfig reqConfig, opts, vmId) {
		log.debug("addNic ${opts}, vm:${vmId}")
		def rtn = [success: false]
		if (!vmId) {
			rtn.error = 'Please specify a VM ID'
		} else {
			def vmNics = []
			def vmNic = [network_uuid: opts.networkUuid]
			if (opts.ipAddress) {
				vmNic.request_ip = true
				vmNic.requested_ip_address = opts.ipAddress
			}
			vmNics << vmNic
			def body = [spec_list: vmNics]
			log.info("add nic body: ${body}")
			def headers = buildHeaders(null)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
			def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'vms/' + vmId + '/nics', reqConfig.username, reqConfig.password, requestOpts, 'POST')

			log.info("addNic results: ${results}")
			if (results.success == true && results.data) {
				def taskId = results.data.task_uuid
				def taskResults = checkTaskReady(client, reqConfig, taskId)
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

	static addCdrom(HttpApiClient client, RequestConfig reqConfig, vmId, cloudFileId, addr = null) {
		log.debug("addDisk vm:${vmId}")
		def rtn = [success: false]
		if (!vmId) {
			rtn.error = 'Please specify a VM ID'
		} else {
			def headers = buildHeaders(null)
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
				vm_disks: vmDisks
			]
			log.info("add disk body: ${body}")
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
			def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'vms/' + vmId + '/disks/attach', reqConfig.username, reqConfig.password, requestOpts, 'POST')

			log.info("addDisk results: ${results}")
			if (results.success == true && results.data) {
				def taskId = results.data.task_uuid
				def taskResults = checkTaskReady(client, reqConfig, taskId)
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

	static deleteDisk(HttpApiClient client, RequestConfig reqConfig, vmId, nutanixDiskObject) {
		log.debug("deleteServerDisk ${nutanixDiskObject}")
		def rtn = [success: false]
		if (!vmId) {
			rtn.error = 'Please specify a VM ID'
		} else if (!nutanixDiskObject) {
			rtn.error = 'Please provide a disk to delete'
		} else {
			def headers = buildHeaders(null)
			def body = [vm_disks: [nutanixDiskObject]]
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
			def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'vms/' + vmId + '/disks/detach', reqConfig.username, reqConfig.password, requestOpts, 'POST')

			log.info("deleteDisk: ${results}")
			if (results.success == true && results.data) {
				def taskId = results.data.task_uuid
				def taskResults = checkTaskReady(client, reqConfig, taskId)
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

	static deleteNic(HttpApiClient client, RequestConfig reqConfig, opts, vmId, nicAddress) {
		log.debug("deleteNic ${opts}")
		def rtn = [success: false]
		if (!vmId) {
			rtn.error = 'Please specify a VM ID'
		} else if (!nicAddress) {
			rtn.error = 'Please specify a nic address'
		} else {
			def headers = buildHeaders(null)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers)

			//requires MAC Address
			if (opts.macAddress) {
				nicAddress = opts.macAddress
			}
			def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'vms/' + vmId + '/nics/' + nicAddress, reqConfig.username, reqConfig.password, requestOpts, 'DELETE')

			log.info("deleteNic: ${results}")
			if (results.success == true && results.data) {
				def taskId = results.data.taskUuid ?: results.data.task_uuid
				def taskResults = checkTaskReady(client, reqConfig, taskId)
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

	static getConsoleUrl(HttpApiClient client, RequestConfig reqConfig, vmId) {
		try {

			HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions()
			requestOpts.ignoreSSL = true
			requestOpts.headers = [
				'Accept': 'text/html',
			]
			requestOpts.body = [
				'j_username': reqConfig.username,
				'j_password': reqConfig.password,
			]
			def resp = client.callApi(reqConfig.apiUrl, "/PrismGateway/j_spring_security_check", null, null, requestOpts)
			if (resp.success) {
				def sessionCookie = resp.getCookie('JSESSIONID')
				if (sessionCookie != null) {

					def apiURL = new URI(reqConfig.apiUrl)
					return [success: true, url: "wss://${apiURL.host}:${apiURL.port}/vnc/vm/${vmId}/proxy", sessionCookie: sessionCookie]
				}
			}
		} catch (ex) {
			log.error("nutanix exception: ${ex.message}", ex)
		}
	}

	static cloneServer(HttpApiClient client, RequestConfig reqConfig, Map opts) {
		log.debug("cloneServer ${opts}")
		def rtn = [success: false]
		if (!opts.serverId && !opts.snapshotId) {
			rtn.error = 'Please specify a VM or Snapshot to clone'
		} else if (!opts.name) {
			rtn.error = 'Please specify a name for new VM'
		} else {
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
			def headers = buildHeaders(null)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)

			if (opts.snapshotId) {
				log.debug("cloning from snapshot ${opts.snapshotId}")
				results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'snapshots/' + opts.snapshotId + '/clone', reqConfig.username, reqConfig.password, requestOpts, 'POST')
			} else if (opts.serverId) {
				log.debug("cloning from server ${opts.serverId}")
				results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'vms/' + opts.serverId + '/clone', reqConfig.username, reqConfig.password, requestOpts, 'POST')
			}
			log.info("cloneServer: ${results}")
			if (results.success == true) {
				def taskId = results.data.task_uuid
				def taskResults = checkTaskReady(client, reqConfig, taskId)
				if (taskResults.success != true && taskResults.errorCode == 500 && opts.uuid) {
					def vmCheckResults = checkVmReady(client, reqConfig, opts.uuid)
					if (vmCheckResults.success == true)
						taskResults = [success: true, error: false, results: [entityList: [[uuid: opts.uuid]], entity_lst: [[entity_id: opts.uuid]]]]
				}
				if (taskResults.success && !taskResults.error) {
					def serverResults = findVirtualMachine(client, reqConfig, opts.name)
					if (serverResults.success == true) {
						def vm = serverResults?.virtualMachine
						if (vm) {
							if (opts.cloudFileId) {
								log.debug("CDROM Detected on Nutanix Clone, Swapping out cloud init file!")
								def cdromDisk = getVirtualMachineDisks(client, reqConfig, vm.uuid)?.disks?.find { it.is_cdrom }
								if (cdromDisk) {
									deleteDisk(client, reqConfig, vm.uuid, cdromDisk)
								}
								addCdrom(client, reqConfig, vm.uuid, opts.cloudFileId, cdromDisk?.disk_address ?: ['device_bus': 'IDE', 'device_index': 0])
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

	static startVm(HttpApiClient client, RequestConfig reqConfig, opts, serverId) {
		def rtn = [success: false]
		def headers = buildHeaders(null)
		def body = [
			transition: "ON",
			vm_logical_timestamp: (opts.timestamp ?: 1)
		]
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
		def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'vms/' + serverId + '/set_power_state', reqConfig.username, reqConfig.password, requestOpts, 'POST')
		log.debug("startVm: ${results}")
		if (results.success == true && results.data) {
			def taskId = results.data.task_uuid
			def taskResults = checkTaskReady(client, reqConfig, taskId)
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

	static stopVm(HttpApiClient client, RequestConfig reqConfig, opts, serverId) {
		def rtn = [success: false]
		//loadVirtualMachine and check power status. Nutanix fails task if VM already powered off.
		def vmResult = loadVirtualMachine(client, reqConfig, serverId)
		if (vmResult?.success) {
			if (vmResult.results?.power_state && vmResult.results?.power_state?.toLowerCase() != "off") {
				def headers = buildHeaders(null)
				def body = [
					transition: "OFF",
					vm_logical_timestamp: (opts.timestamp ?: 1)
				]
				def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
				def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'vms/' + serverId + '/set_power_state', reqConfig.username, reqConfig.password, requestOpts, 'POST')
				log.debug("stopVm: ${results}")
				if (results.success == true && results.data) {
					def taskId = results.data.task_uuid
					def taskResults = checkTaskReady(client, reqConfig, taskId)
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

	static deleteServer(HttpApiClient client, RequestConfig reqConfig, serverId) {
		def rtn = [success: false]
		//cache
		def headers = buildHeaders(null)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'vms/' + serverId , reqConfig.username, reqConfig.password, requestOpts, 'DELETE')
		log.debug("deleteVm: ${results}")
		if (results.success == true && results.data) {
			def taskId = results.data.task_uuid
			def taskResults = checkTaskReady(client, reqConfig, taskId)
			if (taskResults.success && !taskResults.error) {
				rtn.taskUuid = taskId
				rtn.success = true
			} else {
				rtn.msg = 'delete failed'
			}
		}
		return rtn
	}

	static deleteImage(HttpApiClient client, RequestConfig reqConfig, imageId) {
		def rtn = [success: false]
		//cache
		def headers = buildHeaders(null)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'images/' + imageId, reqConfig.username, reqConfig.password, requestOpts, 'DELETE')
		log.debug("deleteImage: ${results}")
		if (results.success == true && results.data) {
			def taskId = results.data.task_uuid
			def taskResults = checkTaskReady(client, reqConfig, taskId)
			if (taskResults.success && !taskResults.error) {
				rtn.taskUuid = taskId
				rtn.success = true
			} else {
				rtn.msg = 'delete failed'
			}
		}
		return rtn
	}

	static getSnapshot(HttpApiClient client, RequestConfig reqConfig, snapshotId) {
		def rtn = [success: false]

		def headers = buildHeaders(null)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'snapshots/' + snapshotId, reqConfig.username, reqConfig.password, requestOpts, 'GET')

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

	static createSnapshot(HttpApiClient client, RequestConfig reqConfig, opts) {
		def rtn = [success: false]
		def vmUuid
		def vmResult = loadVirtualMachine(client, reqConfig, opts.vmId)
		if (vmResult?.success) {
			vmUuid = vmResult.results?.uuid
			def body = [snapshot_specs: [[vm_uuid: vmUuid, snapshot_name: opts.snapshotName]]]
			log.info("Create snapshot body: ${body}")
			def headers = buildHeaders(null)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
			def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'snapshots', reqConfig.username, reqConfig.password, requestOpts, 'POST')

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

	static restoreSnapshot(HttpApiClient client, RequestConfig reqConfig, opts) {
		def rtn = [success: false]
		def vmUuid
		def vmResult = loadVirtualMachine(client, reqConfig, opts.vmId)
		if (vmResult?.success) {
			vmUuid = vmResult.results?.uuid

			def body = [restore_network_configuration: true, snapshot_uuid: opts.snapshotId, uuid: vmUuid]
			log.info("Restore snapshot body: ${body}")
			def headers = buildHeaders(null)
			def requestOpts = new HttpApiClient.RequestOptions(headers: headers, body: body)
			def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'vms/' + vmUuid + '/restore', reqConfig.username, reqConfig.password, requestOpts, 'POST')
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

	static deleteSnapshot(HttpApiClient client, RequestConfig reqConfig, snapshotId) {
		def rtn = [success: false]
		def headers = buildHeaders(null)
		def requestOpts = new HttpApiClient.RequestOptions(headers: headers)
		def results = client.callJsonApi(reqConfig.apiUrl, v2Api + 'snapshots/' + snapshotId, reqConfig.username, reqConfig.password, requestOpts, 'DELETE')
		log.debug("deleteSnapshot: ${results}")
		if (results.success == true && results.data) {
			def taskId = results.data.task_uuid
			def taskResults = checkTaskReady(client, reqConfig, taskId)
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

	static checkServerReady(HttpApiClient client, RequestConfig reqConfig, vmId) {
		def rtn = [success: false]
		try {
			def pending = true
			def attempts = 0
			while (pending) {
				sleep(1000l * 20l)
				def serverDetail = loadVirtualMachine(client, reqConfig, vmId)
				log.debug("serverDetail: ${serverDetail}")
				if (serverDetail.success == true
					&& serverDetail.results.power_state == 'on'
					&& hasIpAddress(serverDetail.results)) {
						rtn.success = true
						rtn.results = serverDetail.results
						rtn.ipAddresses = serverDetail.results.vm_nics?.collectMany {
							(it.ip_addresses ?: []) + (it.ip_address ?: [])
						}?.findAll {
							checkIpv4Ip(it)
						}?.unique()
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
		vm?.vm_nics?.collectMany {
			(it.ip_addresses ?: []) + (it.ip_address ?: [])
		}?.any {
			checkIpv4Ip(it)
		}
	}

	static Map insertContainerImage(HttpApiClient client, RequestConfig reqConfig, opts) {
		def rtn = [success: false]
		log.info("insertContainerImage: ${opts}")
		def image = opts.image
		def matchResults = findImage(client, reqConfig, image.name)
		def match = matchResults.image
		if (match) {
			log.debug("using found image")
			rtn.imageId = match.uuid
			rtn.imageDiskId = match.vm_disk_id
			rtn.success = true
		} else {
			log.debug("inserting image")
			def createResults = uploadImage(client, reqConfig, opts)
			if (createResults.success == true) {
				//wait here?
				def taskId = createResults.taskUuid
				def taskResults = checkTaskReady(client, reqConfig, taskId)
				if (taskResults.success && !taskResults.error) {
					def imageId = taskResults.results.entity_list[0].entity_id
					def imageResults = findImage(client, reqConfig, image.name)
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
						return insertContainerImage(client, reqConfig, opts)
					} else {
						rtn.msg = 'task failed'
					}
				}
			}
		}
		return rtn
	}

	static checkTaskReady(HttpApiClient client, RequestConfig reqConfig, taskId) {
		def rtn = [success: false]
		try {
			if (taskId == null)
				return rtn
			def pending = true
			def attempts = 0
			while (pending) {
				sleep(1000l * 10l)
				def taskDetail = getTask(client, reqConfig, taskId)
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

	static checkVmReady(HttpApiClient client, RequestConfig reqConfig, vmId) {
		def rtn = [success: false]
		try {
			if (vmId == null)
				return rtn
			def pending = true
			def attempts = 0
			while (pending) {
				sleep(1000l * 10l)
				def vmDetails = loadVirtualMachine(client, reqConfig, vmId)
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

	static buildHeaders(Map headers) {
		return (headers ?: [:]) + ['Content-Type': 'application/json;', 'Accept': 'application/json']
	}

	static RequestConfig getRequestConfig(MorpheusContext morpheusContext, Cloud cloud) {
		if (!cloud.accountCredentialLoaded) {
			AccountCredential accountCredential
			try {
				accountCredential = morpheusContext.async.cloud.loadCredentials(cloud.id).blockingGet()
				cloud.accountCredentialLoaded = true
				cloud.accountCredentialData = accountCredential?.data
			} catch (e) {
			}
		}

		def config = new RequestConfig()
		config.apiUrl = cloud.serviceUrl ?: cloud.configMap?.apiUrl
		if (cloud.accountCredentialData && cloud.accountCredentialData.containsKey('username')) {
			config.username = cloud.accountCredentialData['username']
		} else {
			config.username = cloud.serviceUsername ?: cloud.configMap.username
		}
		if (cloud.accountCredentialData && cloud.accountCredentialData.containsKey('password')) {
			config.password = cloud.accountCredentialData['password']
		} else {
			config.password = cloud.servicePassword ?: cloud.configMap.password
		}

		return config
	}
}

class RequestConfig {
	String username
	String password
	String apiUrl

	/**
	 * Sets the API URL for the request, ensuring that the apiUrl is the base url without any path.
	 * <p>
	 * This allows us to be a bit more flexible with the apiUrl configuration, allowing for the full URL to be provided
	 * without any negative consequences for the user.
	 *
	 * @param apiUrl the API URL
	 */
	void setApiUrl(String apiUrl) {
		if (apiUrl) {
			if (apiUrl.startsWith('http')) {
				URIBuilder uriBuilder = new URIBuilder("${apiUrl}")
				uriBuilder.setPath('')
				apiUrl= uriBuilder.build().toString()
			} else {
				apiUrl = 'https://' + apiUrl + ':9440'
			}
		}
		this.apiUrl = apiUrl
	}
}
